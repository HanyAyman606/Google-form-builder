package com.formbuilder.ui;

import com.formbuilder.AppState;
import com.formbuilder.model.FormSection;
import com.formbuilder.model.Question;
import com.formbuilder.model.QuestionData;
import com.formbuilder.model.QuestionType;
import com.formbuilder.service.JsonReaderService;
import com.formbuilder.service.PythonRunner;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.*;
import javafx.stage.FileChooser;

// PDFBox 3.x imports
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;

import java.io.File;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.awt.image.BufferedImage;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.ResourceBundle;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;

/**
 * Import panel — v8
 *
 * FIXES applied here:
 * 1. PDFBox 3.x API: Loader.loadPDF(File) instead of PDDocument.load(File)
 * 2. FXML ToggleGroup wired entirely in code (not FXML) — avoids FXML parse errors
 * 3. Spinner valueFactory set in code, not inline FXML — avoids FXML validation errors
 *
 * WORKFLOW:
 *   Pick PDF → choose Full PDF or Page Range → (optional) Preview pages
 *   → Crop → questions appear as thumbnails below
 *
 * NUMBERING (v8):
 *   questions_divider.py writes "questionNumber" = real number from PDF text.
 *   Sub-questions: q.number = parentNumber, q.subLabel = "i"/"ii"/...
 *   displayNumber() returns "10-i", "16-iii" automatically.
 */
public class ImportPanelController implements Initializable {

    // ── FXML fields ────────────────────────────────────────────────────────
    @FXML private Button    btnPickPdf;
    @FXML private Label     lblPdfName;
    @FXML private Label     lblPageCount;

    @FXML private RadioButton rbFullPdf;
    @FXML private RadioButton rbSplitFirst;
    @FXML private VBox        splitOptionsBox;

    @FXML private Spinner<Integer> spnPageFrom;
    @FXML private Spinner<Integer> spnPageTo;

    @FXML private Button     btnPreviewPages;
    @FXML private ScrollPane previewScroll;
    @FXML private HBox       previewStrip;

    @FXML private Button     btnCropFromPdf;
    @FXML private ProgressBar progressBar;
    @FXML private Label      statusLabel;

    @FXML private Label      countLabel;
    @FXML private FlowPane   thumbnailPane;

    @FXML private Button     btnBrowseScreenshots;

    // Hidden compat fields
    @FXML private TextField  tfQuestionNumbers;
    @FXML private Label      lblSolutionPdfName;
    @FXML private Button     btnPickSolutionPdf;
    @FXML private Button     btnAnalyze;

    // ── State ──────────────────────────────────────────────────────────────
    private final AppState state     = AppState.getInstance();
    private final File     scriptDir = new File(System.getProperty("user.dir"));

    private File selectedPdf  = null;
    private int  pdfPageCount = 0;

    // ═══════════════════════════════════════════════════════════════════════
    // INIT
    // ═══════════════════════════════════════════════════════════════════════

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        progressBar.setVisible(false);
        previewScroll.setVisible(false);
        previewScroll.setManaged(false);
        splitOptionsBox.setVisible(false);
        splitOptionsBox.setManaged(false);
        btnCropFromPdf.setDisable(true);

        // Wire ToggleGroup in code (FXML inline ToggleGroup causes parse errors)
        ToggleGroup modeGroup = new ToggleGroup();
        rbFullPdf.setToggleGroup(modeGroup);
        rbSplitFirst.setToggleGroup(modeGroup);
        rbFullPdf.setSelected(true);

        // Wire spinners in code (avoids FXML inline valueFactory errors)
        spnPageFrom.setValueFactory(
            new SpinnerValueFactory.IntegerSpinnerValueFactory(1, 9999, 1));
        spnPageTo.setValueFactory(
            new SpinnerValueFactory.IntegerSpinnerValueFactory(1, 9999, 1));

        modeGroup.selectedToggleProperty().addListener((obs, old, sel) -> {
            boolean split = (sel == rbSplitFirst);
            splitOptionsBox.setVisible(split);
            splitOptionsBox.setManaged(split);
            previewScroll.setVisible(false);
            previewScroll.setManaged(false);
            previewStrip.getChildren().clear();
            updateCropButton();
        });

        refreshThumbnails();
    }

    // ═══════════════════════════════════════════════════════════════════════
    // PICK PDF
    // ═══════════════════════════════════════════════════════════════════════

    @FXML
    private void onPickPdf() {
        FileChooser fc = new FileChooser();
        fc.setTitle("Select Question PDF");
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("PDF", "*.pdf"));
        File file = fc.showOpenDialog(btnPickPdf.getScene().getWindow());
        if (file == null) return;

        selectedPdf = file;
        lblPdfName.setText(file.getName());

        // PDFBox 3.x: Loader.loadPDF(File) — NOT PDDocument.load(File)
        pdfPageCount = countPdfPages(file);
        lblPageCount.setText(pdfPageCount > 0 ? pdfPageCount + " pages" : "");

        if (pdfPageCount > 0) {
            ((SpinnerValueFactory.IntegerSpinnerValueFactory) spnPageFrom.getValueFactory())
                .setMax(pdfPageCount);
            ((SpinnerValueFactory.IntegerSpinnerValueFactory) spnPageTo.getValueFactory())
                .setMax(pdfPageCount);
            spnPageTo.getValueFactory().setValue(pdfPageCount);
        }

        updateCropButton();
        statusLabel.setText("PDF loaded. Choose Full PDF or Page Range, then click Crop.");
    }

    /**
     * Count PDF pages — PDFBox 3.x API.
     * PDFBox 3.x removed PDDocument.load(File).
     * Use org.apache.pdfbox.Loader.loadPDF(File) instead.
     */
    private int countPdfPages(File pdfFile) {
        try (PDDocument doc = Loader.loadPDF(pdfFile)) {
            return doc.getNumberOfPages();
        } catch (Exception e) {
            System.err.println("Could not count pages: " + e.getMessage());
            return 0;
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // PREVIEW PAGES  (split mode only)
    // ═══════════════════════════════════════════════════════════════════════

    @FXML
    private void onPreviewPages() {
        if (selectedPdf == null) { showError("Pick a PDF first."); return; }

        int from = spnPageFrom.getValue();
        int to   = spnPageTo.getValue();
        if (from > to) { showError("'From' page must be ≤ 'To' page."); return; }

        previewStrip.getChildren().clear();
        previewScroll.setVisible(true);
        previewScroll.setManaged(true);
        statusLabel.setText("Generating page previews…");

        Task<List<Image>> task = new Task<>() {
            @Override
            protected List<Image> call() throws Exception {
                List<Image> images = new ArrayList<>();
                // PDFBox 3.x: Loader.loadPDF
                try (PDDocument doc = Loader.loadPDF(selectedPdf)) {
                    PDFRenderer renderer = new PDFRenderer(doc);
                    for (int p = from - 1; p <= to - 1 && p < doc.getNumberOfPages(); p++) {
                        BufferedImage bi = renderer.renderImageWithDPI(p, 72);
                        ByteArrayOutputStream baos = new ByteArrayOutputStream();
                        javax.imageio.ImageIO.write(bi, "png", baos);
                        images.add(new Image(new ByteArrayInputStream(baos.toByteArray())));
                    }
                }
                return images;
            }
        };

        task.setOnSucceeded(e -> {
            List<Image> imgs = task.getValue();
            int startPage = from;
            for (int i = 0; i < imgs.size(); i++) {
                final int pageNum = startPage + i;
                ImageView iv = new ImageView(imgs.get(i));
                iv.setFitHeight(160);
                iv.setPreserveRatio(true);
                Label lbl = new Label("Page " + pageNum);
                lbl.setStyle("-fx-font-size:11px; -fx-text-fill:#555;");
                VBox box = new VBox(4, iv, lbl);
                box.setAlignment(Pos.CENTER);
                box.setPadding(new Insets(6));
                box.setStyle("-fx-border-color:#dde1e5; -fx-border-radius:6;"
                           + "-fx-background-color:white; -fx-background-radius:6;");
                previewStrip.getChildren().add(box);
            }
            statusLabel.setText("Showing pages " + from + "–" + to
                + ". Click ▶ Crop when ready.");
        });

        task.setOnFailed(e ->
            statusLabel.setText("Preview failed: " + task.getException().getMessage()));

        new Thread(task, "preview-thread").start();
    }

    // ═══════════════════════════════════════════════════════════════════════
    // CROP
    // ═══════════════════════════════════════════════════════════════════════

    @FXML
    private void onCropFromPdf() {
        if (selectedPdf == null)           { showError("Please select a PDF first."); return; }
        if (state.getProjectDir() == null) { showError("Create a project first (File → New)."); return; }

        final Integer pageFrom;
        final Integer pageTo;

        if (rbSplitFirst.isSelected()) {
            pageFrom = spnPageFrom.getValue();
            pageTo   = spnPageTo.getValue();
            if (pageFrom > pageTo) { showError("'From' page must be ≤ 'To' page."); return; }
        } else {
            pageFrom = null;
            pageTo   = null;
        }

        Task<List<Question>> task = new Task<>() {
            @Override
            protected List<Question> call() throws Exception {
                String rangeInfo = (pageFrom != null)
                    ? "pages " + pageFrom + "–" + pageTo : "full PDF";
                updateMessage("Running Python cropping pipeline (" + rangeInfo + ")…");

                List<String> extraArgs = new ArrayList<>();
                if (pageFrom != null) {
                    extraArgs.add("--page-from");
                    extraArgs.add(String.valueOf(pageFrom));
                    extraArgs.add("--page-to");
                    extraArgs.add(String.valueOf(pageTo));
                }

                PythonRunner.run(selectedPdf.getAbsolutePath(), scriptDir, extraArgs);

                updateMessage("Reading JSON output…");

                File outputJson = new File(scriptDir, "output/output.json");
                if (!outputJson.exists()) {
                    throw new RuntimeException(
                        "output.json not found at: " + outputJson.getAbsolutePath() + "\n"
                        + "Make sure questions_divider.py writes output to the 'output' folder.");
                }

                List<QuestionData> raw = JsonReaderService.read(outputJson.getAbsolutePath());
                return convertToQuestions(raw);
            }
        };

        bindProgress(task);

        task.setOnSucceeded(e -> {
            unbindProgress();
            List<Question> questions = task.getValue();
            state.getQuestions().addAll(questions);
            state.markDirty();
            autoSetupSections();
            refreshThumbnails();

            if (!state.getQuestions().isEmpty())
                state.setSelectedQuestion(state.getQuestions().get(0));

            long mcqCount     = questions.stream().filter(q -> q.getType() == QuestionType.MCQ).count();
            long writtenCount = questions.stream().filter(q -> q.getType() == QuestionType.PROBLEM).count();
            long subCount     = questions.stream().filter(Question::isSubQuestion).count();

            statusLabel.setText("Imported " + questions.size() + " question(s)  "
                + "(MCQ: " + mcqCount + "  Written: " + writtenCount
                + (subCount > 0 ? "  Sub-questions: " + subCount : "") + "). "
                + "Go to Answers tab to set correct answers.");
        });

        task.setOnFailed(e -> {
            unbindProgress();
            showError("Import failed:\n" + task.getException().getMessage());
        });

        new Thread(task, "python-import-thread").start();
    }

    // ═══════════════════════════════════════════════════════════════════════
    // CONVERT QuestionData → Question  (v8 real numbering)
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Rules:
     *   - Standalone (parentNumber == 0): use questionNumber from JSON as q.number.
     *     If questionNumber == 0 (not extracted), fall back to lastKnownReal + 1.
     *   - Sub-question (parentNumber > 0): q.number = parentNumber (the REAL parent
     *     number), q.subLabel = "i"/"ii"/... → displayNumber() = "10-ii", "16-iii"
     */
    /**
     * Converts raw QuestionData list into Question objects using REAL question numbers.
     * Progress updates go to statusLabel via Platform.runLater (no callback needed).
     */
    private List<Question> convertToQuestions(List<QuestionData> raw) throws Exception {
        List<Question> converted = new ArrayList<>();
        File imagesDir = new File(state.getProjectDir(), "images");
        if (!imagesDir.exists()) imagesDir.mkdirs();

        int fileSeq    = state.getQuestions().size() + 1;
        int total      = raw.size();

        // Sequential display counter — increments for each NEW standalone question
        // or the FIRST sub-question of a new group.
        // Sub-questions that share a parent all get the SAME display number.
      int displayNum = 0;
int lastRawParent = -1;

        for (QuestionData qd : raw) {
            Question q = new Question();
            boolean isSub = qd.parentNumber > 0
                && qd.subLabel != null && !qd.subLabel.isBlank();

            if (isSub) {
                // First sub-question of a NEW parent group → advance counter once
                if (qd.parentNumber != lastRawParent) {
                    displayNum++;
                    lastRawParent = qd.parentNumber;
                }
                // All siblings of this group share the same displayNum
                q.setNumber(displayNum);
                q.setParentNumber(displayNum);
                q.setSubLabel(qd.subLabel);
            } else {
                // Standalone question → always advance counter
                displayNum++;
                lastRawParent = -1;
                q.setNumber(displayNum);
                q.setParentNumber(0);
                q.setSubLabel(null);
            }

            // Copy image into project images dir
            File source = new File(qd.image);
            if (!source.isAbsolute()) source = new File(scriptDir, qd.image);
            File dest = new File(imagesDir, "q_" + fileSeq + ".png");
            if (source.exists()) {
                Files.copy(source.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING);
                q.setImagePath("images/" + dest.getName());
            } else {
                System.err.println("Warning: image not found: " + source.getAbsolutePath());
                q.setImagePath(null);
            }

            // Type
            boolean isWritten = qd.type != null
                && (qd.type.equalsIgnoreCase("written") || qd.type.equalsIgnoreCase("problem"));
            q.setType(isWritten ? QuestionType.PROBLEM : QuestionType.MCQ);

            q.setChoiceA(""); q.setChoiceB(""); q.setChoiceC(""); q.setChoiceD("");

            converted.add(q);
            fileSeq++;

            // Update status label on FX thread
            final int done = converted.size();
            Platform.runLater(() ->
                statusLabel.setText("Processed " + done + " / " + total + " questions…"));
        }
        return converted;
    }

    // ═══════════════════════════════════════════════════════════════════════
    // SCREENSHOTS
    // ═══════════════════════════════════════════════════════════════════════

    @FXML
    private void onBrowseScreenshots() {
        if (state.getProjectDir() == null) { showError("Create a project first (File → New)."); return; }

        FileChooser fc = new FileChooser();
        fc.setTitle("Select Screenshot Images");
        fc.getExtensionFilters().add(
            new FileChooser.ExtensionFilter("Images", "*.png", "*.jpg", "*.jpeg"));

        List<File> files = fc.showOpenMultipleDialog(btnBrowseScreenshots.getScene().getWindow());
        if (files == null || files.isEmpty()) return;

        int number = state.getQuestions().size() + 1;
        List<Question> imported = new ArrayList<>();

        try {
            File imagesDir = new File(state.getProjectDir(), "images");
            imagesDir.mkdirs();
            for (File src : files) {
                Question q = new Question();
                q.setNumber(number);
                q.setParentNumber(0);
                q.setSubLabel(null);
                String ext = src.getName().contains(".")
                    ? src.getName().substring(src.getName().lastIndexOf('.')) : ".png";
                File dest = new File(imagesDir, "q_" + number + ext);
                Files.copy(src.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING);
                q.setImagePath("images/" + dest.getName());
                q.setType(QuestionType.MCQ);
                q.setChoiceA(""); q.setChoiceB(""); q.setChoiceC(""); q.setChoiceD("");
                imported.add(q);
                number++;
            }
            state.getQuestions().addAll(imported);
            state.markDirty();
            autoSetupSections();
            refreshThumbnails();
            if (!state.getQuestions().isEmpty())
                state.setSelectedQuestion(state.getQuestions().get(0));
            statusLabel.setText("Imported " + imported.size() + " screenshot(s).");
        } catch (Exception e) {
            showError("Screenshot import failed: " + e.getMessage());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // AUTO-SECTIONS
    // ═══════════════════════════════════════════════════════════════════════

    private void autoSetupSections() {
        if (!state.getSections().isEmpty()) return;
        long mcq     = state.getQuestions().stream().filter(q -> q.getType() == QuestionType.MCQ).count();
        long written = state.getQuestions().stream().filter(q -> q.getType() == QuestionType.PROBLEM).count();
        if (mcq == 0 && written == 0) return;
        if (mcq > 0) {
            FormSection s = new FormSection("MCQ Questions");
            s.setDescription("Multiple choice questions");
            state.getSections().add(s);
            state.getQuestions().stream().filter(q -> q.getType() == QuestionType.MCQ)
                .forEach(q -> { q.setSectionId(s.getId()); s.getQuestionIds().add(q.getId()); });
        }
        if (written > 0) {
            FormSection s = new FormSection("Written Questions");
            s.setDescription("Problem / written answer questions");
            state.getSections().add(s);
            state.getQuestions().stream().filter(q -> q.getType() == QuestionType.PROBLEM)
                .forEach(q -> { q.setSectionId(s.getId()); s.getQuestionIds().add(q.getId()); });
        }
        state.markDirty();
    }

    // ═══════════════════════════════════════════════════════════════════════
    // THUMBNAILS
    // ═══════════════════════════════════════════════════════════════════════

    private void refreshThumbnails() {
        thumbnailPane.getChildren().clear();
        for (Question q : state.getQuestions()) thumbnailPane.getChildren().add(buildThumbnail(q));
        countLabel.setText(state.getQuestions().size() + " question(s)");
    }

    private VBox buildThumbnail(Question q) {
        ImageView iv = new ImageView();
        iv.setFitWidth(120); iv.setFitHeight(90); iv.setPreserveRatio(true);
        if (q.getImagePath() != null && state.getProjectDir() != null) {
            File img = new File(state.getProjectDir(), q.getImagePath());
            if (img.exists()) iv.setImage(new Image(img.toURI().toString()));
        }
        String typeTag = q.getType() == QuestionType.MCQ ? "MCQ" : "Written";
        Label label = new Label("Q" + q.displayNumber() + " [" + typeTag + "]");
        label.setStyle("-fx-font-size:11px;");
        VBox box = new VBox(5, iv, label);
        box.setAlignment(Pos.CENTER);
        box.setStyle("-fx-padding:5; -fx-border-color:lightgray; -fx-cursor:hand;"
                   + "-fx-border-radius:4; -fx-background-color:white;");
        box.setOnMouseClicked(e -> state.setSelectedQuestion(q));
        return box;
    }

    // ═══════════════════════════════════════════════════════════════════════
    // HELPERS
    // ═══════════════════════════════════════════════════════════════════════

    private void updateCropButton() {
        btnCropFromPdf.setDisable(selectedPdf == null);
        if (selectedPdf != null) {
            btnCropFromPdf.setText(rbSplitFirst.isSelected()
                ? "▶  Crop Selected Range" : "▶  Crop Full PDF");
        }
    }

    private void bindProgress(Task<?> task) {
        progressBar.setVisible(true);
        progressBar.progressProperty().bind(task.progressProperty());
        statusLabel.textProperty().bind(task.messageProperty());
    }

    private void unbindProgress() {
        progressBar.progressProperty().unbind();
        statusLabel.textProperty().unbind();
        progressBar.setVisible(false);
    }

    private void showError(String msg) {
        Alert alert = new Alert(Alert.AlertType.ERROR, msg, ButtonType.OK);
        alert.setTitle("Import Error");
        alert.showAndWait();
    }

    // Hidden compat FXML handlers
    @FXML private void onPickSolutionPdf() {}
    @FXML private void onNumbersChanged()  {}
    @FXML private void onAnalyze() {
        new Alert(Alert.AlertType.INFORMATION,
            "Please fill in choices and correct answers manually in the Answers tab.",
            ButtonType.OK).showAndWait();
    }
}