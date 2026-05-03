package com.formbuilder.ui;

import com.formbuilder.AppState;
import com.formbuilder.model.FormSection;
import com.formbuilder.model.Question;
import com.formbuilder.model.QuestionType;
import com.formbuilder.model.UploadStatus;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.*;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Rectangle;
import javafx.stage.FileChooser;

import java.io.File;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.*;

/**
 * Question Editor — v4 (Usability Rewrite)
 *
 * NEW FEATURES:
 *  1. Drag-and-drop reordering in the list (full group-aware)
 *  2. Swap Mode — click any two questions to exchange their positions
 *  3. Written questions shown as bordered exam cards with dot lines (like images)
 *  4. Sub-label pill buttons (i, ii, iii…) — one click sets the label
 *  5. Live "Q14-ii" preview while editing sub-question fields
 *  6. Swap indicator highlights swapping targets in orange dashes
 */
public class QuestionEditorController implements Initializable {

    // ── Left list ──────────────────────────────────────────────────────────
    @FXML private ListView<Question> questionList;
    @FXML private Button             btnSwapMode;

    // ── Nav bar ────────────────────────────────────────────────────────────
    @FXML private Button btnPrev;
    @FXML private Button btnNext;
    @FXML private Label  counterLabel;

    // ── Right editor ───────────────────────────────────────────────────────
    @FXML private ImageView               questionImage;
    @FXML private Label                   confidenceLabel;
    @FXML private Label                   sectionHintLabel;
    @FXML private ComboBox<QuestionType>  typeCombo;
    @FXML private ComboBox<FormSection>   sectionCombo;

    @FXML private TextField tfChoiceA;
    @FXML private TextField tfChoiceB;
    @FXML private TextField tfChoiceC;
    @FXML private TextField tfChoiceD;

    @FXML private RadioButton rbA, rbB, rbC, rbD;
    @FXML private ToggleGroup answerGroup;

    @FXML private Spinner<Integer> pointSpinner;
    @FXML private Label            validationLabel;

    // ── Written question preview card (new) ───────────────────────────────
    @FXML private VBox  writtenCardPreview;   // shown only for PROBLEM type
    @FXML private Label writtenCardNumLabel;
    @FXML private Label writtenAnswerHint;

    // ── Sub-question fields ────────────────────────────────────────────────
    @FXML private CheckBox         chkSubQuestion;
    @FXML private Spinner<Integer> spnParentNumber;
    @FXML private ComboBox<String> cmbSubLabel;
    @FXML private Label            subPreviewLabel;   // live "Q14-ii" preview
    @FXML private FlowPane         subLabelPillPane;  // pill buttons i–x

    // ── Choices section (hidden for PROBLEM type) ─────────────────────────
    @FXML private VBox choicesSection;

    // ── State ──────────────────────────────────────────────────────────────
    private final AppState state   = AppState.getInstance();
    private       Question current = null;
    private       boolean  loading = false;

    // Swap mode
    private boolean  swapMode      = false;
    private Question swapFirstPick = null;

    // Drag-and-drop
    private static final DataFormat DRAG_FORMAT =
        new DataFormat("application/x-question-drag");

    private static final List<String> SUB_LABELS =
        List.of("i","ii","iii","iv","v","vi","vii","viii","ix","x",
                "a","b","c","d","e","f");

    // ── Init ───────────────────────────────────────────────────────────────

    @Override
    public void initialize(URL location, ResourceBundle resources) {

        questionList.setItems(state.getQuestions());
        questionList.setCellFactory(lv -> new DraggableQuestionCell());

        questionList.getSelectionModel().selectedItemProperty().addListener(
            (obs, old, q) -> { loadQuestion(q); updateCounter(); }
        );

        state.selectedQuestionProperty().addListener((obs, old, q) -> {
            if (q != null && !q.equals(questionList.getSelectionModel().getSelectedItem())) {
                questionList.getSelectionModel().select(q);
                questionList.scrollTo(q);
            }
        });

        typeCombo.getItems().setAll(QuestionType.values());
        typeCombo.valueProperty().addListener((o, a, b) -> {
            if (!loading) updateTypeVisibility();
        });

        sectionCombo.setItems(state.getSections());
        sectionCombo.setCellFactory(lv -> new SectionCell());
        sectionCombo.setButtonCell(new SectionCell());

        pointSpinner.setValueFactory(
            new SpinnerValueFactory.IntegerSpinnerValueFactory(1, 100, 1));

        spnParentNumber.setValueFactory(
            new SpinnerValueFactory.IntegerSpinnerValueFactory(1, 999, 1));

        cmbSubLabel.getItems().setAll(SUB_LABELS);

        spnParentNumber.setDisable(true);
        cmbSubLabel.setDisable(true);

        // Build pill buttons for sub-labels
        buildSubLabelPills();

        chkSubQuestion.selectedProperty().addListener((obs, old, sel) -> {
            spnParentNumber.setDisable(!sel);
            cmbSubLabel.setDisable(!sel);
            subLabelPillPane.setDisable(!sel);
            if (!loading && current != null) {
                if (!sel) {
                    current.setParentNumber(0);
                    current.setSubLabel(null);
                    state.markDirty();
                    renumber();
                }
                updateSubPreviewLabel();
            }
        });

        // Live sub-preview update
        spnParentNumber.valueProperty().addListener((o, a, b) -> updateSubPreviewLabel());
        cmbSubLabel.valueProperty().addListener((o, a, b) -> {
            updateSubPreviewLabel();
            if (!loading && current != null && b != null) {
                current.setSubLabel(b);
                renumber();
                questionList.refresh();
            }
        });

        wireListeners();

        if (!state.getQuestions().isEmpty()) {
            questionList.getSelectionModel().selectFirst();
        }
        updateCounter();

        // Keyboard shortcuts
        questionList.sceneProperty().addListener((obs, oldScene, newScene) -> {
            if (newScene != null) {
                newScene.getAccelerators().put(
                    new KeyCodeCombination(KeyCode.RIGHT, KeyCombination.CONTROL_DOWN), this::onNext);
                newScene.getAccelerators().put(
                    new KeyCodeCombination(KeyCode.LEFT, KeyCombination.CONTROL_DOWN), this::onPrev);
            }
        });
    }

    // ── Sub-label pill builder ─────────────────────────────────────────────

    private void buildSubLabelPills() {
        if (subLabelPillPane == null) return;
        subLabelPillPane.getChildren().clear();
        for (String label : SUB_LABELS) {
            Button pill = new Button(label);
            pill.setStyle(
                "-fx-background-color:#f4f0ff; -fx-text-fill:#8e44ad;"
                + "-fx-border-color:#d2b4f0; -fx-border-radius:20;"
                + "-fx-background-radius:20; -fx-font-size:11px;"
                + "-fx-padding:3 9; -fx-cursor:hand;");
            pill.setOnAction(e -> {
                cmbSubLabel.setValue(label);
                highlightPill(pill);
                if (current != null) {
                    current.setSubLabel(label);
                    if (chkSubQuestion.isSelected() && spnParentNumber.getValue() != null) {
                        current.setParentNumber(spnParentNumber.getValue());
                    }
                    state.markDirty();
                    renumber();
                    questionList.refresh();
                    updateSubPreviewLabel();
                }
            });
            subLabelPillPane.getChildren().add(pill);
        }
    }

    private void highlightPill(Button active) {
        subLabelPillPane.getChildren().forEach(node -> {
            if (node instanceof Button b) {
                boolean sel = b == active;
                b.setStyle(
                    "-fx-background-color:" + (sel ? "#8e44ad" : "#f4f0ff") + ";"
                    + "-fx-text-fill:" + (sel ? "white" : "#8e44ad") + ";"
                    + "-fx-border-color:#d2b4f0; -fx-border-radius:20;"
                    + "-fx-background-radius:20; -fx-font-size:11px;"
                    + "-fx-padding:3 9; -fx-cursor:hand;");
            }
        });
    }

    private void updateSubPreviewLabel() {
        if (subPreviewLabel == null) return;
        if (!chkSubQuestion.isSelected()) {
            subPreviewLabel.setText(current != null ? "Q" + current.getNumber() : "");
            return;
        }
        Integer parent = spnParentNumber.getValue();
        String  sub    = cmbSubLabel.getValue();
        if (parent != null && sub != null && !sub.isBlank()) {
            subPreviewLabel.setText("Q" + parent + "-" + sub);
        }
    }

    // ── Swap Mode ──────────────────────────────────────────────────────────

    @FXML
    private void onToggleSwapMode() {
        swapMode = !swapMode;
        swapFirstPick = null;

        if (btnSwapMode != null) {
            if (swapMode) {
                btnSwapMode.setText("✕ Exit Swap Mode");
                btnSwapMode.setStyle(
                    "-fx-background-color:#e67e22; -fx-text-fill:white;"
                    + "-fx-background-radius:5; -fx-border-width:0;"
                    + "-fx-font-weight:bold; -fx-cursor:hand; -fx-padding:7 10;");
            } else {
                btnSwapMode.setText("⇄ Swap Mode");
                btnSwapMode.setStyle(
                    "-fx-background-color:transparent; -fx-text-fill:#e67e22;"
                    + "-fx-border-color:#e67e22; -fx-border-width:1;"
                    + "-fx-background-radius:5; -fx-border-radius:5;"
                    + "-fx-cursor:hand; -fx-padding:7 10;");
            }
        }

        questionList.refresh();

        if (swapMode) {
            showInfo("Swap mode ON — click a question in the list to pick the FIRST, " +
                     "then click another to swap them. Click ✕ Exit Swap Mode when done.");
        }
    }

    /** Called when an item is clicked in swap mode. */
    private void handleSwapClick(Question q) {
        if (swapFirstPick == null) {
            swapFirstPick = q;
            questionList.refresh();
        } else if (swapFirstPick != q) {
            performSwap(swapFirstPick, q);
            swapFirstPick = null;
            questionList.refresh();
        } else {
            // Clicked same one twice — deselect
            swapFirstPick = null;
            questionList.refresh();
        }
    }

    private void performSwap(Question a, Question b) {
        List<Question> qs = state.getQuestions();
        // Swap entire groups
        int aStart = groupStartIndex(qs.indexOf(a));
        int aEnd   = groupEndIndex(qs.indexOf(a));
        int bStart = groupStartIndex(qs.indexOf(b));
        int bEnd   = groupEndIndex(qs.indexOf(b));

        List<Question> groupA = new ArrayList<>(qs.subList(aStart, aEnd + 1));
        List<Question> groupB = new ArrayList<>(qs.subList(bStart, bEnd + 1));

        // Remove both groups (higher index first to avoid shifts)
        int lo = Math.min(aStart, bStart);
        int hi = Math.max(aEnd,   bEnd);
        for (int i = hi; i >= lo; i--) qs.remove(i);

        // Re-insert in swapped order
        if (aStart < bStart) {
            qs.addAll(lo, groupB);
            qs.addAll(lo + groupB.size(), groupA);
        } else {
            qs.addAll(lo, groupA);
            qs.addAll(lo + groupA.size(), groupB);
        }

        renumber();
        state.markDirty();
        questionList.refresh();
    }

    // ── Navigation ─────────────────────────────────────────────────────────

    @FXML private void onNext() {
        int idx = questionList.getSelectionModel().getSelectedIndex();
        if (idx < state.getQuestions().size() - 1) {
            questionList.getSelectionModel().select(idx + 1);
            questionList.scrollTo(idx + 1);
        }
    }

    @FXML private void onPrev() {
        int idx = questionList.getSelectionModel().getSelectedIndex();
        if (idx > 0) {
            questionList.getSelectionModel().select(idx - 1);
            questionList.scrollTo(idx - 1);
        }
    }

    // ── Add question ───────────────────────────────────────────────────────

    @FXML
    private void onAddQuestion() {
        if (state.getProjectDir() == null) {
            showError("Create or open a project first (File → New).");
            return;
        }

        Question q = new Question();
        q.setType(QuestionType.MCQ);
        q.setChoiceA(""); q.setChoiceB(""); q.setChoiceC(""); q.setChoiceD("");
        q.setCorrectAnswer('A');
        q.setPointValue(1);

        int insertAfter = questionList.getSelectionModel().getSelectedIndex();

        if (insertAfter < 0 || state.getQuestions().isEmpty()) {
            state.getQuestions().add(q);
        } else {
            int groupEnd = groupEndIndex(insertAfter);
            state.getQuestions().add(groupEnd + 1, q);
        }

        renumber();
        state.markDirty();

        int newIdx = state.getQuestions().indexOf(q);
        questionList.getSelectionModel().select(newIdx);
        questionList.scrollTo(newIdx);
        updateCounter();
    }

    // ── Duplicate as Sub-question ──────────────────────────────────────────

    /**
     * Duplicates the selected question as a sub-question sibling.
     *
     * • If the selected Q is standalone → converts it to Q-i, creates copy as Q-ii.
     * • If already a sub-question → creates copy as the next sub-label (iii, iv, …).
     */
    @FXML
    private void onDuplicateAsSub() {
        if (current == null) { showError("Select a question first."); return; }
        if (state.getProjectDir() == null) {
            showError("Create or open a project first (File → New)."); return;
        }

        List<Question> qs = state.getQuestions();
        int currentIdx = questionList.getSelectionModel().getSelectedIndex();

        if (!current.isSubQuestion()) {
            // Current is standalone → make it sub-question "i"
            current.setSubLabel("i");
            current.setParentNumber(current.getNumber());
        }

        // Find the next available sub-label in the group
        int parentNum = current.getParentNumber() > 0 ? current.getParentNumber() : current.getNumber();
        String nextLabel = findNextSubLabel(parentNum);

        // Create the copy
        Question copy = current.copy();
        copy.setSubLabel(nextLabel);
        copy.setParentNumber(parentNum);

        // Insert right after the end of the current group
        int groupEnd = groupEndIndex(currentIdx);
        qs.add(groupEnd + 1, copy);

        renumber();
        state.markDirty();

        // Select the new copy
        int newIdx = qs.indexOf(copy);
        questionList.getSelectionModel().select(newIdx);
        questionList.scrollTo(newIdx);
        updateCounter();
    }

    /**
     * Finds the next unused sub-label for a given parent number.
     * Scans the existing sub-questions to determine the highest used label,
     * then returns the next one in sequence.
     */
    private String findNextSubLabel(int parentNum) {
        Set<String> used = new HashSet<>();
        for (Question q : state.getQuestions()) {
            if (q.getParentNumber() == parentNum && q.getSubLabel() != null) {
                used.add(q.getSubLabel());
            }
        }
        for (String label : SUB_LABELS) {
            if (!used.contains(label)) return label;
        }
        // Fallback: if all labels used, return roman numeral beyond the list
        return String.valueOf(used.size() + 1);
    }

    // ── Delete ─────────────────────────────────────────────────────────────

    @FXML
    private void onDeleteQuestion() {
        if (current == null) return;

        int idx = questionList.getSelectionModel().getSelectedIndex();

        if (current.getParentNumber() == 0) {
            boolean hasChildren = state.getQuestions().stream()
                .anyMatch(q -> q.getParentNumber() == current.getNumber());
            if (hasChildren) {
                Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                    "Q" + current.displayNumber() + " has sub-questions.\n" +
                    "Delete just this question or the whole group?",
                    new ButtonType("Delete All"),
                    new ButtonType("Delete Only This"),
                    ButtonType.CANCEL);
                confirm.setTitle("Delete Question Group");
                Optional<ButtonType> result = confirm.showAndWait();
                if (result.isEmpty() || result.get() == ButtonType.CANCEL) return;

                if (result.get().getText().equals("Delete All")) {
                    int parentNum = current.getNumber();
                    state.getQuestions().removeIf(q -> q.getParentNumber() == parentNum);
                }
            } else {
                if (!confirmDeleteSingle(current)) return;
            }
        } else {
            if (!confirmDeleteSingle(current)) return;
        }

        state.getQuestions().remove(current);
        renumber();
        state.markDirty();

        if (!state.getQuestions().isEmpty()) {
            int next = Math.min(idx, state.getQuestions().size() - 1);
            questionList.getSelectionModel().select(next);
        } else {
            current = null;
            clearEditor();
        }
        updateCounter();
    }

    private boolean confirmDeleteSingle(Question q) {
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
            "Delete Q" + q.displayNumber() + "? This cannot be undone.",
            ButtonType.YES, ButtonType.CANCEL);
        confirm.setTitle("Delete Question");
        return confirm.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.YES;
    }

    // ── Move Up / Down ─────────────────────────────────────────────────────

    @FXML private void onMoveUp() {
        int idx = questionList.getSelectionModel().getSelectedIndex();
        if (idx <= 0) return;
        int groupStart = groupStartIndex(idx);
        int groupEnd   = groupEndIndex(idx);
        int aboveEnd   = groupStart - 1;
        int aboveStart = groupStartIndex(aboveEnd);

        List<Question> qs    = state.getQuestions();
        List<Question> above = new ArrayList<>(qs.subList(aboveStart, groupStart));
        List<Question> group = new ArrayList<>(qs.subList(groupStart, groupEnd + 1));

        for (int i = aboveStart; i <= groupEnd; i++) qs.remove(aboveStart);
        qs.addAll(aboveStart, group);
        qs.addAll(aboveStart + group.size(), above);

        renumber(); state.markDirty();
        int newIdx = qs.indexOf(current);
        questionList.getSelectionModel().select(newIdx);
        questionList.scrollTo(newIdx);
    }

    @FXML private void onMoveDown() {
        int idx = questionList.getSelectionModel().getSelectedIndex();
        if (idx < 0) return;

        List<Question> qs = state.getQuestions();
        int groupStart = groupStartIndex(idx);
        int groupEnd   = groupEndIndex(idx);
        if (groupEnd >= qs.size() - 1) return;

        int belowStart = groupEnd + 1;
        int belowEnd   = groupEndIndex(belowStart);

        List<Question> group = new ArrayList<>(qs.subList(groupStart, groupEnd + 1));
        List<Question> below = new ArrayList<>(qs.subList(belowStart, belowEnd + 1));

        for (int i = groupStart; i <= belowEnd; i++) qs.remove(groupStart);
        qs.addAll(groupStart, below);
        qs.addAll(groupStart + below.size(), group);

        renumber(); state.markDirty();
        int newIdx = qs.indexOf(current);
        questionList.getSelectionModel().select(newIdx);
        questionList.scrollTo(newIdx);
    }

    // ── Move to specific position ──────────────────────────────────────────

    @FXML
    private void onMoveToPosition() {
        if (current == null) { showError("Select a question first."); return; }

        List<Question> qs = state.getQuestions();
        int totalPositions = qs.size();
        if (totalPositions <= 1) return;

        int currentIdx = questionList.getSelectionModel().getSelectedIndex();

        // Determine the group boundaries of the selected question
        int gStart = groupStartIndex(currentIdx);
        int gEnd   = groupEndIndex(currentIdx);
        int groupSize = gEnd - gStart + 1;

        // Ask the user for the target position number
        TextInputDialog dialog = new TextInputDialog(String.valueOf(current.getNumber()));
        dialog.setTitle("Move to Position");
        dialog.setHeaderText("Move Q" + current.displayNumber()
            + (groupSize > 1 ? " (group of " + groupSize + ")" : "")
            + " to position:");
        dialog.setContentText("New position (1 – " + totalPositions + "):");

        Optional<String> result = dialog.showAndWait();
        if (result.isEmpty()) return;

        int targetPos;
        try {
            targetPos = Integer.parseInt(result.get().trim());
        } catch (NumberFormatException e) {
            showError("Please enter a valid number.");
            return;
        }

        if (targetPos < 1 || targetPos > totalPositions) {
            showError("Position must be between 1 and " + totalPositions + ".");
            return;
        }

        // Find the list index for the target position.
        // We need to count by "position slots" — each standalone Q is 1 slot,
        // each sub-question group is 1 slot.
        int targetListIdx = positionToListIndex(targetPos);
        if (targetListIdx < 0 || targetListIdx >= qs.size()) {
            targetListIdx = Math.max(0, Math.min(targetListIdx, qs.size() - 1));
        }

        // If the target is the same group, nothing to do
        int tgStart = groupStartIndex(targetListIdx);
        if (tgStart == gStart) return;

        // Extract the group
        List<Question> group = new ArrayList<>(qs.subList(gStart, gEnd + 1));

        // Remove the group from the list
        for (int i = gStart; i <= gEnd; i++) qs.remove(gStart);

        // Recalculate target index after removal
        int insertAt;
        if (tgStart > gStart) {
            // Target was after the removed group — adjust
            insertAt = tgStart - groupSize;
        } else {
            insertAt = tgStart;
        }
        insertAt = Math.max(0, Math.min(insertAt, qs.size()));

        // Insert the group at the new position
        qs.addAll(insertAt, group);

        renumber();
        state.markDirty();

        // Re-select the moved question
        int newIdx = qs.indexOf(current);
        if (newIdx >= 0) {
            questionList.getSelectionModel().select(newIdx);
            questionList.scrollTo(newIdx);
        }
    }

    /**
     * Converts a 1-based position number to a list index.
     * Position 1 = index 0, position 2 = the start of the 2nd group, etc.
     * Each sub-question group counts as one position.
     */
    private int positionToListIndex(int pos) {
        List<Question> qs = state.getQuestions();
        int slot = 0;
        int i = 0;
        while (i < qs.size()) {
            slot++;
            if (slot == pos) return i;
            // Skip over sub-question group
            int end = groupEndIndex(i);
            i = end + 1;
        }
        return qs.size(); // past the end
    }

    // ── Save Changes ───────────────────────────────────────────────────────

    @FXML
    private void onSaveChanges() {
        if (state.getProjectFile() == null) {
            showError("Use File → Save As to save this project first.");
            return;
        }
        try {
            new com.formbuilder.service.ProjectService()
                .save(state.toProjectData(), state.getProjectFile());
            state.markClean();
            validationLabel.setText("Saved ✓");
            validationLabel.setStyle("-fx-text-fill:#27ae60; -fx-font-weight:bold;");
        } catch (Exception e) {
            showError("Save failed: " + e.getMessage());
        }
    }

    // ── Replace image ──────────────────────────────────────────────────────

    @FXML
    private void onReplaceImage() {
        if (current == null) { showError("Select a question first."); return; }
        if (state.getProjectDir() == null) { showError("No project directory set."); return; }

        FileChooser fc = new FileChooser();
        fc.setTitle("Choose replacement image");
        fc.getExtensionFilters().add(
            new FileChooser.ExtensionFilter("Images", "*.png", "*.jpg", "*.jpeg"));
        File src = fc.showOpenDialog(questionImage.getScene().getWindow());
        if (src == null) return;

        try {
            File imagesDir = new File(state.getProjectDir(), "images");
            imagesDir.mkdirs();
            String ext  = src.getName().substring(src.getName().lastIndexOf('.'));
            File   dest = new File(imagesDir, "q_" + current.getNumber() + "_replaced" + ext);
            Files.copy(src.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING);

            current.setImagePath("images/" + dest.getName());
            current.setUploadStatus(UploadStatus.PENDING);
            current.setDriveImageUrl(null);
            state.markDirty();

            questionImage.setImage(new Image(dest.toURI().toString(), true));
        } catch (Exception e) {
            showError("Failed to replace image: " + e.getMessage());
        }
    }

    // ── Paste image from clipboard ─────────────────────────────────────────

    @FXML
    private void onPasteImage() {
        if (current == null) { showError("Select a question first."); return; }
        if (state.getProjectDir() == null) { showError("No project directory set."); return; }

        Clipboard clipboard = Clipboard.getSystemClipboard();
        if (!clipboard.hasImage()) {
            showError("No image found in clipboard. Please copy an image first.");
            return;
        }

        Image pastedImage = clipboard.getImage();
        try {
            File imagesDir = new File(state.getProjectDir(), "images");
            imagesDir.mkdirs();

            String filename = "q_" + current.getNumber() + "_pasted_" + System.currentTimeMillis() + ".png";
            File dest = new File(imagesDir, filename);

            int iw = (int) pastedImage.getWidth();
            int ih = (int) pastedImage.getHeight();
            java.awt.image.BufferedImage buffered = new java.awt.image.BufferedImage(iw, ih, java.awt.image.BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < ih; y++) {
                for (int x = 0; x < iw; x++) {
                    javafx.scene.paint.Color fxColor = pastedImage.getPixelReader().getColor(x, y);
                    int r = (int) (fxColor.getRed() * 255);
                    int g = (int) (fxColor.getGreen() * 255);
                    int b = (int) (fxColor.getBlue() * 255);
                    int a = (int) (fxColor.getOpacity() * 255);
                    buffered.setRGB(x, y, (a << 24) | (r << 16) | (g << 8) | b);
                }
            }
            javax.imageio.ImageIO.write(buffered, "png", dest);

            current.setImagePath("images/" + dest.getName());
            current.setUploadStatus(UploadStatus.PENDING);
            current.setDriveImageUrl(null);
            state.markDirty();

            questionImage.setImage(new Image(dest.toURI().toString(), true));
        } catch (Exception e) {
            showError("Failed to paste image: " + e.getMessage());
        }
    }

    // ── Crop image ─────────────────────────────────────────────────────────

    @FXML
    private void onCropImage() {
        if (current == null) { showError("Select a question first."); return; }
        if (state.getProjectDir() == null) { showError("No project directory set."); return; }
        if (current.getImagePath() == null || current.getImagePath().isBlank()) {
            showError("This question has no image to crop."); return;
        }

        File imageFile = new File(state.getProjectDir(), current.getImagePath());
        if (!imageFile.exists()) {
            showError("Image file not found: " + current.getImagePath()); return;
        }

        ImageCropDialog cropDialog = new ImageCropDialog(
            questionImage.getScene().getWindow(), imageFile);
        cropDialog.showAndWait();

        if (cropDialog.isCropped()) {
            File croppedFile = cropDialog.getResultFile();
            // Update path relative to project dir
            String relativePath = state.getProjectDir().toPath()
                .relativize(croppedFile.toPath()).toString().replace('\\', '/');
            current.setImagePath(relativePath);
            current.setUploadStatus(UploadStatus.PENDING);
            current.setDriveImageUrl(null);
            state.markDirty();

            questionImage.setImage(new Image(croppedFile.toURI().toString(), true));
        }
    }

    // ── Confirm manually ───────────────────────────────────────────────────

    @FXML
    private void onConfirmManually() {
        if (current == null) return;
        current.setVisionConfidence(1.0);
        state.markDirty();
        updateValidation(current);
        questionList.refresh();
        confidenceLabel.setText("✓ Manually confirmed.");
        confidenceLabel.setStyle("-fx-text-fill: green;");
    }

    // ── Type visibility toggle ─────────────────────────────────────────────

    /**
     * Shows/hides the MCQ choices section and the written question card preview
     * depending on the selected question type.
     */
    private void updateTypeVisibility() {
        if (current == null) return;
        boolean isWritten = typeCombo.getValue() == QuestionType.PROBLEM;

        if (choicesSection != null)    choicesSection.setVisible(!isWritten);
        if (choicesSection != null)    choicesSection.setManaged(!isWritten);
        if (writtenCardPreview != null) writtenCardPreview.setVisible(isWritten);
        if (writtenCardPreview != null) writtenCardPreview.setManaged(isWritten);

        if (isWritten && writtenCardNumLabel != null) {
            writtenCardNumLabel.setText("" + current.displayNumber());
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // DRAG-AND-DROP — handled in cell class
    // ══════════════════════════════════════════════════════════════════

    /** Processes a drop from index draggedIdx onto index targetIdx. */
    void processDrop(int draggedIdx, int targetIdx) {
        if (draggedIdx == targetIdx) return;
        List<Question> qs = state.getQuestions();

        int gStart = groupStartIndex(draggedIdx);
        int gEnd   = groupEndIndex(draggedIdx);

        List<Question> group = new ArrayList<>(qs.subList(gStart, gEnd + 1));
        // Remove the group
        for (int i = gStart; i <= gEnd; i++) qs.remove(gStart);

        // Adjust target after removal
        int adj = targetIdx > gEnd ? targetIdx - group.size() : targetIdx;
        int insertAt = Math.max(0, Math.min(adj, qs.size()));

        qs.addAll(insertAt, group);
        renumber();
        state.markDirty();

        int newIdx = qs.indexOf(current);
        if (newIdx >= 0) {
            questionList.getSelectionModel().select(newIdx);
            questionList.scrollTo(newIdx);
        }
    }

    /**
     * Renumbers all questions sequentially.
     *
     * Standalone questions get consecutive major numbers.
     * Sub-question groups share a major number. Group boundaries are detected by:
     *   1. Transition from standalone to sub → new group
     *   2. Different original parentNumber between contiguous subs → new group
     *   3. Duplicate subLabel within what would be one group → new group
     *      (e.g. two "i" labels = two separate groups that got merged by mistake)
     */
    private void renumber() {
        List<Question> qs = state.getQuestions();
        int major = 0;
        boolean prevWasSub = false;
        int currentGroupOrigParent = -1;
        Set<String> usedLabelsInGroup = new HashSet<>();

        for (int i = 0; i < qs.size(); i++) {
            Question q = qs.get(i);
            boolean isSub = q.getSubLabel() != null
                && !q.getSubLabel().isBlank();

            if (isSub) {
                int origParent = q.getParentNumber();

                // Detect whether this starts a NEW group
                boolean newGroup = !prevWasSub                            // first sub after standalone
                    || origParent != currentGroupOrigParent               // different original parent
                    || usedLabelsInGroup.contains(q.getSubLabel());       // duplicate label → split

                if (newGroup) {
                    major++;
                    currentGroupOrigParent = origParent;
                    usedLabelsInGroup.clear();
                }

                usedLabelsInGroup.add(q.getSubLabel());
                q.setParentNumber(major);
                q.setNumber(major);
                prevWasSub = true;
            } else {
                // Standalone question: always gets the next major number.
                major++;
                q.setParentNumber(0);
                q.setSubLabel(null);
                q.setNumber(major);
                prevWasSub = false;
                currentGroupOrigParent = -1;
                usedLabelsInGroup.clear();
            }
        }

        questionList.refresh();
        updateCounter();
    }

    // ── Group helpers ──────────────────────────────────────────────────────

    /**
     * Finds the first list index of the group containing the question at idx.
     * A "group" is a run of contiguous sub-questions sharing the same parentNumber.
     * A standalone question (parentNumber == 0 or no subLabel) is its own group.
     */
    private int groupStartIndex(int idx) {
        List<Question> qs = state.getQuestions();
        if (idx < 0 || idx >= qs.size()) return idx;
        Question q = qs.get(idx);

        // Standalone question — it IS the group start
        if (!q.isSubQuestion()) return idx;

        // Sub-question — walk backwards while parentNumber matches
        int parentNum = q.getParentNumber();
        int start = idx;
        for (int i = idx - 1; i >= 0; i--) {
            Question prev = qs.get(i);
            if (prev.isSubQuestion() && prev.getParentNumber() == parentNum) {
                start = i;
            } else {
                break;
            }
        }
        return start;
    }

    /**
     * Finds the last list index of the group containing the question at idx.
     */
    private int groupEndIndex(int idx) {
        List<Question> qs = state.getQuestions();
        if (idx < 0 || idx >= qs.size()) return idx;
        Question q = qs.get(idx);

        // Standalone question — it IS the group end
        if (!q.isSubQuestion()) return idx;

        // Sub-question — walk forwards while parentNumber matches
        int parentNum = q.getParentNumber();
        int end = idx;
        for (int i = idx + 1; i < qs.size(); i++) {
            Question next = qs.get(i);
            if (next.isSubQuestion() && next.getParentNumber() == parentNum) {
                end = i;
            } else {
                break;
            }
        }
        return end;
    }

    // ── Load question into form ────────────────────────────────────────────

    private void loadQuestion(Question q) {
        current = q;
        if (q == null) { clearEditor(); return; }

        loading = true;

        // Image
        if (q.getImagePath() != null && state.getProjectDir() != null) {
            File img = new File(state.getProjectDir(), q.getImagePath());
            questionImage.setImage(img.exists() ? new Image(img.toURI().toString(), true) : null);
        } else {
            questionImage.setImage(null);
        }

        // Confidence
        if (q.getVisionConfidence() > 0) {
            String pct = String.format("%.0f%%", q.getVisionConfidence() * 100);
            confidenceLabel.setText("Confidence: " + pct +
                (q.getVisionConfidence() < 0.70 ? " — low, review!" :
                 q.getVisionConfidence() < 0.90 ? " — medium" : " — high"));
            confidenceLabel.setStyle(q.getVisionConfidence() < 0.70
                ? "-fx-text-fill:#e74c3c;"
                : q.getVisionConfidence() < 0.90
                    ? "-fx-text-fill:#e67e22;"
                    : "-fx-text-fill:#27ae60;");
        } else {
            confidenceLabel.setText("");
        }

        sectionHintLabel.setText(
            q.getSectionHint() != null ? "Section hint: " + q.getSectionHint() : "");

        typeCombo.setValue(q.getType());
        sectionCombo.setValue(q.getSectionId() != null
            ? state.getSections().stream()
                .filter(s -> s.getId().equals(q.getSectionId()))
                .findFirst().orElse(null)
            : null);

        tfChoiceA.setText(nvl(q.getChoiceA()));
        tfChoiceB.setText(nvl(q.getChoiceB()));
        tfChoiceC.setText(nvl(q.getChoiceC()));
        tfChoiceD.setText(nvl(q.getChoiceD()));

        pointSpinner.getValueFactory().setValue(q.getPointValue());

        boolean isSub = q.isSubQuestion();
        chkSubQuestion.setSelected(isSub);
        spnParentNumber.setDisable(!isSub);
        cmbSubLabel.setDisable(!isSub);
        if (subLabelPillPane != null) subLabelPillPane.setDisable(!isSub);
        if (isSub) {
            spnParentNumber.getValueFactory().setValue(q.getParentNumber());
            cmbSubLabel.setValue(q.getSubLabel());
            // Highlight the matching pill
            if (subLabelPillPane != null) {
                subLabelPillPane.getChildren().forEach(node -> {
                    if (node instanceof Button b) {
                        boolean active = b.getText().equals(q.getSubLabel());
                        b.setStyle(
                            "-fx-background-color:" + (active ? "#8e44ad" : "#f4f0ff") + ";"
                            + "-fx-text-fill:" + (active ? "white" : "#8e44ad") + ";"
                            + "-fx-border-color:#d2b4f0; -fx-border-radius:20;"
                            + "-fx-background-radius:20; -fx-font-size:11px;"
                            + "-fx-padding:3 9; -fx-cursor:hand;");
                    }
                });
            }
        } else {
            spnParentNumber.getValueFactory().setValue(1);
            cmbSubLabel.setValue(null);
        }

        answerGroup.selectToggle(switch (q.getCorrectAnswer()) {
            case 'B' -> rbB;
            case 'C' -> rbC;
            case 'D' -> rbD;
            default  -> rbA;
        });

        updateTypeVisibility();
        updateSubPreviewLabel();
        updateValidation(q);
        loading = false;
    }

    // ── Listeners ──────────────────────────────────────────────────────────

    private void wireListeners() {
        tfChoiceA.textProperty().addListener((o, a, b) -> writeBack());
        tfChoiceB.textProperty().addListener((o, a, b) -> writeBack());
        tfChoiceC.textProperty().addListener((o, a, b) -> writeBack());
        tfChoiceD.textProperty().addListener((o, a, b) -> writeBack());
        typeCombo.valueProperty().addListener((o, a, b) -> writeBack());
        sectionCombo.valueProperty().addListener((o, a, b) -> writeBack());
        pointSpinner.valueProperty().addListener((o, a, b) -> writeBack());
        answerGroup.selectedToggleProperty().addListener((o, a, b) -> writeBack());
        spnParentNumber.valueProperty().addListener((o, a, b) -> writeBack());
        cmbSubLabel.valueProperty().addListener((o, a, b) -> writeBack());
    }

    private void writeBack() {
        if (loading || current == null) return;

        current.setChoiceA(tfChoiceA.getText());
        current.setChoiceB(tfChoiceB.getText());
        current.setChoiceC(tfChoiceC.getText());
        current.setChoiceD(tfChoiceD.getText());

        if (typeCombo.getValue() != null)    current.setType(typeCombo.getValue());
        if (pointSpinner.getValue() != null) current.setPointValue(pointSpinner.getValue());

        if (chkSubQuestion.isSelected()) {
            if (spnParentNumber.getValue() != null) current.setParentNumber(spnParentNumber.getValue());
            current.setSubLabel(cmbSubLabel.getValue() != null ? cmbSubLabel.getValue() : "");
        } else {
            current.setParentNumber(0);
            current.setSubLabel(null);
        }

        FormSection sec = sectionCombo.getValue();
        current.setSectionId(sec != null ? sec.getId() : null);

        Toggle selected = answerGroup.getSelectedToggle();
        if      (selected == rbB) current.setCorrectAnswer('B');
        else if (selected == rbC) current.setCorrectAnswer('C');
        else if (selected == rbD) current.setCorrectAnswer('D');
        else                      current.setCorrectAnswer('A');

        if (current.getUploadStatus() == UploadStatus.UPLOADED) {
            current.setUploadStatus(UploadStatus.PENDING);
            current.setDriveImageUrl(null);
        }

        state.markDirty();
        updateValidation(current);
        questionList.refresh();
        updateCounter();
        updateTypeVisibility();
        updateSubPreviewLabel();
    }

    // ── Validation ─────────────────────────────────────────────────────────

    private void updateValidation(Question q) {
        if (q.isReadyForExport()) {
            validationLabel.setText("✓ Ready for export");
            validationLabel.setStyle("-fx-text-fill:#27ae60; -fx-font-weight:bold;");
        } else {
            StringBuilder reason = new StringBuilder("Not ready — ");
            if (q.getImagePath() == null || q.getImagePath().isBlank())
                reason.append("no image; ");
            if (q.getType() == QuestionType.MCQ &&
                (isBlank(q.getChoiceA()) || isBlank(q.getChoiceB()) ||
                 isBlank(q.getChoiceC()) || isBlank(q.getChoiceD())))
                reason.append("missing choices; ");
            if (q.getVisionConfidence() > 0 && q.getVisionConfidence() < 0.70)
                reason.append("low confidence — confirm manually; ");
            validationLabel.setText(reason.toString().replaceAll("; $", ""));
            validationLabel.setStyle("-fx-text-fill:#e74c3c;");
        }
    }

    // ── Counter ────────────────────────────────────────────────────────────

    private void updateCounter() {
        if (counterLabel == null) return;
        int total = state.getQuestions().size();
        int idx   = questionList.getSelectionModel().getSelectedIndex();
        if (total == 0)   counterLabel.setText("No questions");
        else if (idx < 0) counterLabel.setText("0 / " + total);
        else              counterLabel.setText(
            "Q " + state.getQuestions().get(idx).displayNumber() + " / " + total);
    }

    // ── Clear editor ───────────────────────────────────────────────────────

    private void clearEditor() {
        questionImage.setImage(null);
        tfChoiceA.clear(); tfChoiceB.clear(); tfChoiceC.clear(); tfChoiceD.clear();
        confidenceLabel.setText("");
        sectionHintLabel.setText("");
        validationLabel.setText("");
        if (counterLabel != null) counterLabel.setText("No questions");
        chkSubQuestion.setSelected(false);
        spnParentNumber.setDisable(true);
        cmbSubLabel.setDisable(true);
        if (subLabelPillPane != null) subLabelPillPane.setDisable(true);
        if (writtenCardPreview != null) { writtenCardPreview.setVisible(false); writtenCardPreview.setManaged(false); }
        if (choicesSection != null) { choicesSection.setVisible(true); choicesSection.setManaged(true); }
    }

    // ══════════════════════════════════════════════════════════════════
    // INNER CELL — drag-and-drop + swap mode highlighting
    // ══════════════════════════════════════════════════════════════════

    private class DraggableQuestionCell extends ListCell<Question> {

        private static final String DROP_STYLE =
            "-fx-border-color:#2980b9; -fx-border-width:2 0 0 0;";

        @Override
        protected void updateItem(Question q, boolean empty) {
            super.updateItem(q, empty);
            if (empty || q == null) {
                setGraphic(null); setText(null);
                setStyle("");
                return;
            }

            // Status dot
            Color dotColor = switch (q.getUploadStatus()) {
                case UPLOADED -> Color.web("#2980b9");
                case FAILED   -> Color.web("#e67e22");
                default -> q.getVisionConfidence() > 0 && q.getVisionConfidence() < 0.70
                    ? Color.web("#e74c3c") : Color.web("#27ae60");
            };
            Circle dot = new Circle(5, dotColor);

            // Drag handle
            Label handle = new Label("⠿");
            handle.setStyle("-fx-font-size:13px; -fx-text-fill:#aaa; -fx-cursor:open-hand;");

            // Sub-question indent + label
            boolean isSub = q.getParentNumber() > 0
                && q.getSubLabel() != null && !q.getSubLabel().isBlank();
            Label lbl = new Label((isSub ? "  ↳ " : "") + "Q" + q.displayNumber());
            lbl.setStyle("-fx-font-size:12px; -fx-font-weight:" + (isSub ? "normal" : "bold") + ";");

            // Type badge
            Label typeBadge = new Label(q.getType() == QuestionType.MCQ ? "MCQ" : "WR");
            typeBadge.setStyle(
                "-fx-font-size:9px; -fx-font-weight:bold; -fx-padding:1 5;"
                + "-fx-background-radius:3; "
                + (q.getType() == QuestionType.MCQ
                    ? "-fx-background-color:#e6f1fb; -fx-text-fill:#185fa5;"
                    : "-fx-background-color:#faeeda; -fx-text-fill:#854f0b;"));

            HBox row = new HBox(6, handle, dot, lbl, typeBadge);
            row.setAlignment(Pos.CENTER_LEFT);
            HBox.setHgrow(lbl, Priority.ALWAYS);

            setGraphic(row);
            setText(null);

            // Swap mode highlight
            if (swapMode && swapFirstPick == q) {
                setStyle("-fx-background-color:#fff3e0; -fx-border-color:#e67e22;"
                       + "-fx-border-width:2; -fx-border-radius:4;");
            } else {
                setStyle("");
            }

            // ── Drag source ──
            setOnDragDetected(e -> {
                if (q == null) return;
                Dragboard db = startDragAndDrop(TransferMode.MOVE);
                ClipboardContent cc = new ClipboardContent();
                cc.put(DRAG_FORMAT, getIndex());
                db.setContent(cc);
                e.consume();
            });

            // ── Drag target ──
            setOnDragOver(e -> {
                if (e.getGestureSource() != this && e.getDragboard().hasContent(DRAG_FORMAT)) {
                    e.acceptTransferModes(TransferMode.MOVE);
                    setStyle(DROP_STYLE);
                }
                e.consume();
            });

            setOnDragExited(e -> setStyle(swapMode && swapFirstPick == q
                ? "-fx-background-color:#fff3e0; -fx-border-color:#e67e22;"
                  + "-fx-border-width:2; -fx-border-radius:4;"
                : ""));

            setOnDragDropped(e -> {
                Dragboard db = e.getDragboard();
                if (db.hasContent(DRAG_FORMAT)) {
                    int fromIdx = (int) db.getContent(DRAG_FORMAT);
                    processDrop(fromIdx, getIndex());
                    e.setDropCompleted(true);
                } else {
                    e.setDropCompleted(false);
                }
                e.consume();
            });

            // ── Click — normal select OR swap pick ──
            setOnMouseClicked(e -> {
                if (q == null) return;
                if (swapMode) {
                    handleSwapClick(q);
                } else {
                    questionList.getSelectionModel().select(q);
                }
            });
        }
    }

    // ── Section combo cell ────────────────────────────────────────────────

    private static class SectionCell extends ListCell<FormSection> {
        @Override
        protected void updateItem(FormSection s, boolean empty) {
            super.updateItem(s, empty);
            setText(empty || s == null ? "— No section —" : s.getTitle());
        }
    }

    // ── Utils ──────────────────────────────────────────────────────────────

    private void showError(String msg) {
        Alert a = new Alert(Alert.AlertType.ERROR, msg, ButtonType.OK);
        a.setTitle("Editor"); a.showAndWait();
    }

    private void showInfo(String msg) {
        Alert a = new Alert(Alert.AlertType.INFORMATION, msg, ButtonType.OK);
        a.setTitle("Editor"); a.showAndWait();
    }

    private static boolean isBlank(String s) { return s == null || s.isBlank(); }
    private static String  nvl(String s)      { return s != null ? s : ""; }
}