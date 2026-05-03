package com.formbuilder.ui;

import com.formbuilder.AppState;
import com.formbuilder.model.AnswerKey;
import com.formbuilder.model.FormSection;
import com.formbuilder.model.Question;
import com.formbuilder.model.UploadStatus;
import com.formbuilder.service.*;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.*;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;

import java.io.File;
import java.net.URL;
import java.util.List;
import java.util.ResourceBundle;

/**
 * Export & Publish panel.
 *
 * FIXES:
 *  1. Published form URL is stored in AppState (not just the local field) so it
 *     survives panel reloads and project save/reopen.
 *  2. "Open in Edit Mode" derives the edit URL from the form ID stored by
 *     GoogleFormsService: https://docs.google.com/forms/d/{id}/edit
 *     If only the view URL is stored (legacy), falls back to replacing /viewform.
 *  3. On initialize(), the URL field is repopulated from AppState so the field
 *     is never blank after navigating away and back to this tab.
 */
public class ExportPanelController implements Initializable {

    @FXML private Button      btnPreview;
    @FXML private Button      btnAnswerKey;
    @FXML private Button      btnPublish;
    @FXML private Button      btnResume;
    @FXML private ProgressBar publishProgress;
    @FXML private Label       statusLabel;
    @FXML private TextField   tfFormUrl;
    @FXML private TextField   tfFormTitle;
    @FXML private TextField   tfFormDescription;
    @FXML private Label       validationSummary;
    @FXML private Label       readinessLabel;
    @FXML private VBox        sectionEditArea;

    private final AppState           state          = AppState.getInstance();
    private final FormPreviewService previewService = new FormPreviewService();
    private final AnswerKeyExporter  keyExporter    = new AnswerKeyExporter();
    private final ProjectService     projectService = new ProjectService();
    private       GoogleFormsService formsService;

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        publishProgress.setVisible(false);
        btnResume.setVisible(false);
        tfFormUrl.setEditable(false);

        // FIX: restore URL from AppState so it reappears after switching tabs
        String savedUrl = state.getPublishedFormUrl();
        if (savedUrl != null && !savedUrl.isBlank()) {
            tfFormUrl.setText(toEditUrl(savedUrl, state.getPublishedFormId()));
        }

        tfFormTitle.setText(state.getProjectName());
        tfFormTitle.textProperty().addListener((obs, old, val) -> {
            state.setProjectName(val); state.markDirty();
        });

        buildSectionEditArea();
        updateValidationSummary();
        checkForResume();

        try {
            formsService = new GoogleFormsService();
        } catch (Exception e) {
            btnPublish.setDisable(true);
            btnResume.setDisable(true);
            statusLabel.setText("Google auth failed: " + e.getMessage());
        }
    }

    // ── Section edit area ──────────────────────────────────────────────────

    private void buildSectionEditArea() {
        sectionEditArea.getChildren().clear();

        if (state.getSections().isEmpty()) {
            Label hint = new Label("No sections defined yet. "
                + "Go to the Sections tab, or import a PDF — sections are created automatically.");
            hint.setStyle("-fx-text-fill:#888; -fx-font-style:italic;");
            hint.setWrapText(true);
            sectionEditArea.getChildren().add(hint);
            return;
        }

        for (FormSection section : state.getSections()) {
            HBox row = new HBox(12);
            row.setAlignment(javafx.geometry.Pos.CENTER_LEFT);

            Label lbl = new Label("Name:");
            lbl.setStyle("-fx-font-size:12px; -fx-text-fill:#555;");
            lbl.setMinWidth(50);

            TextField tf = new TextField(section.getTitle());
            tf.setPromptText("Section title…");
            HBox.setHgrow(tf, javafx.scene.layout.Priority.ALWAYS);
            tf.textProperty().addListener((o, a, v) -> { section.setTitle(v); state.markDirty(); });

            TextField tfDesc = new TextField(
                section.getDescription() != null ? section.getDescription() : "");
            tfDesc.setPromptText("Description (optional)");
            tfDesc.setPrefWidth(200);
            tfDesc.textProperty().addListener((o, a, v) -> {
                section.setDescription(v.isBlank() ? null : v); state.markDirty();
            });

            long count = state.getQuestions().stream()
                .filter(q -> section.getId().equals(q.getSectionId())).count();
            Label countLbl = new Label(count + " Q");
            countLbl.setStyle("-fx-text-fill:#888; -fx-font-size:12px;");

            row.getChildren().addAll(lbl, tf, tfDesc, countLbl);
            sectionEditArea.getChildren().add(row);
        }
    }

    // ── Answer key ─────────────────────────────────────────────────────────

    @FXML private void onSaveAnswerKeyPdf() { saveAnswerKey(false); }
    @FXML private void onSaveAnswerKeyCsv() { saveAnswerKey(true); }

    private void saveAnswerKey(boolean csv) {
        List<Question> questions = state.getQuestions();
        long answered = questions.stream().filter(this::isAnswered).count();
        if (answered == 0) {
            showError("No answers assigned yet. Go to the Answers tab first.");
            return;
        }

        FileChooser fc = new FileChooser();
        fc.setTitle("Save Answer Key");
        if (csv) {
            fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("CSV", "*.csv"));
            fc.setInitialFileName(state.getProjectName() + "_AnswerKey.csv");
        } else {
            fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("PDF", "*.pdf"));
            fc.setInitialFileName(state.getProjectName() + "_AnswerKey.pdf");
        }
        if (state.getProjectDir() != null) fc.setInitialDirectory(state.getProjectDir());

        File dest = fc.showSaveDialog(btnAnswerKey.getScene().getWindow());
        if (dest == null) return;

        AnswerKey key = AnswerKey.fromQuestions(questions, state.getProjectName());
        try {
            if (csv) keyExporter.exportCsv(key, questions, dest);
            else     keyExporter.exportPdf(key, questions, dest);
            statusLabel.setText("Answer key saved: " + dest.getName());
        } catch (Exception e) {
            showError("Export failed: " + e.getMessage());
        }
    }

    // ── Preview ────────────────────────────────────────────────────────────

    @FXML
    private void onPreview() {
        try {
            File projectDir = state.getProjectDir();
            if (projectDir == null) { showError("No project directory set."); return; }
            var pd = state.toProjectData();
            pd.setFormTitle(tfFormTitle.getText());
            previewService.generateAndOpen(pd, projectDir);
            statusLabel.setText("Preview opened in browser.");
        } catch (Exception e) {
            showError("Preview failed: " + e.getMessage());
        }
    }

    // ── Publish ────────────────────────────────────────────────────────────

    @FXML private void onPublish() { if (validateBeforePublish()) startPublish(); }
    @FXML private void onResume()  { startPublish(); }

    private void startPublish() {
        if (formsService == null) {
            showError("Google Forms service unavailable. Check credentials and restart."); return;
        }
        btnPublish.setDisable(true); btnResume.setDisable(true);
        publishProgress.setVisible(true); publishProgress.setProgress(0);

        var projectData = state.toProjectData();
        projectData.setFormTitle(tfFormTitle.getText());
        File projectDir = state.getProjectDir();

        Task<String> task = new Task<>() {
            @Override protected String call() throws Exception {
                return formsService.publish(projectData, projectDir, (done, total) -> {
                    updateProgress(done, total);
                    updateMessage("Uploading " + done + " / " + total + "…");
                });
            }
        };

        publishProgress.progressProperty().bind(task.progressProperty());
        statusLabel.textProperty().bind(task.messageProperty());

        task.setOnSucceeded(e -> {
            unbind();
            String viewUrl = task.getValue();

            // Derive the form ID from the view URL so we can build the edit URL
            String formId = extractFormId(viewUrl);
            String editUrl = toEditUrl(viewUrl, formId);

            // FIX: persist in AppState so URL survives tab switches and save/reopen
            state.setPublishedFormUrl(viewUrl);
            state.setPublishedFormId(formId);
            state.markDirty();

            tfFormUrl.setText(editUrl);
            statusLabel.setText("Form created ✓  — Click 'Open in Edit Mode' to review, then Send from Google Forms.");
            btnPublish.setDisable(false); btnResume.setVisible(false);

            // Auto-save the project so the URL is not lost
            if (state.getProjectFile() != null) {
                try { projectService.save(state.toProjectData(), state.getProjectFile()); state.markClean(); }
                catch (Exception ex) { /* non-critical */ }
            }
        });

        task.setOnFailed(e -> {
            unbind();
            statusLabel.setText("Publish failed: " + task.getException().getMessage());
            btnPublish.setDisable(false); checkForResume();
        });

        new Thread(task, "publish-thread").start();
    }

    @FXML
    private void onCopyUrl() {
        String url = tfFormUrl.getText();
        if (url == null || url.isBlank()) return;
        ClipboardContent c = new ClipboardContent(); c.putString(url);
        Clipboard.getSystemClipboard().setContent(c);
        statusLabel.setText("Edit URL copied.");
    }

    @FXML
    private void onOpenEditMode() {
        String url = tfFormUrl.getText();
        if (url == null || url.isBlank()) {
            showError("Create the form first, then open it in edit mode.");
            return;
        }
        // Ensure we always open the edit URL, not the view URL
        String openUrl = url.contains("/edit") ? url : toEditUrl(url, extractFormId(url));
        try {
            java.awt.Desktop.getDesktop().browse(new java.net.URI(openUrl));
            statusLabel.setText("Opened form in Google Forms edit mode. Review, then click Send in Google Forms.");
        } catch (Exception e) {
            showError("Could not open browser: " + e.getMessage());
        }
    }

    // ── URL helpers ────────────────────────────────────────────────────────

    /**
     * Extracts the form ID from a Google Forms URL.
     * Works for both /d/{id}/viewform and /d/{id}/edit patterns.
     */
    private static String extractFormId(String url) {
        if (url == null) return null;
        // Pattern: /forms/d/{formId}/
        java.util.regex.Matcher m = java.util.regex.Pattern
            .compile("/forms/d/([^/?#]+)").matcher(url);
        return m.find() ? m.group(1) : null;
    }

    /**
     * Returns the Google Forms edit URL.
     * Prefers building from formId; falls back to string replacement on the view URL.
     */
    private static String toEditUrl(String viewUrl, String formId) {
        if (formId != null && !formId.isBlank()) {
            return "https://docs.google.com/forms/d/" + formId + "/edit";
        }
        if (viewUrl == null) return "";
        // Fallback: replace /viewform with /edit
        return viewUrl.replace("/viewform", "/edit");
    }

    // ── Validation ─────────────────────────────────────────────────────────

    /**
     * A question is considered answered only if the teacher explicitly saved
     * an answer in the Answer panel. We detect this by checking choiceA:
     *  - After import choiceA is "" (blank) — not yet answered.
     *  - After the teacher saves in the Answer panel, choiceA is set to at
     *    least "A" (auto-filled for image-based MCQ) or real text.
     * This prevents the default correctAnswer='A' (set in Question.java) from
     * making every question appear answered before the teacher touches anything.
     */
    private boolean isAnswered(Question q) {
        return q.getCorrectAnswer() >= 'A' && q.getCorrectAnswer() <= 'D'
            && q.getChoiceA() != null && !q.getChoiceA().isBlank();
    }

    private boolean validateBeforePublish() {
        if (state.getQuestions().isEmpty()) {
            showError("No questions to publish. Import questions first.");
            return false;
        }
        long noAnswer = state.getQuestions().stream().filter(q -> !isAnswered(q)).count();
        if (noAnswer > 0) {
            // Show a WARNING (not a blocker) — teacher may intentionally publish
            // without filling answers (the form itself is still valid).
            Alert warn = new Alert(Alert.AlertType.CONFIRMATION,
                "⚠ " + noAnswer + " question(s) have no answer set yet.\n\n"
                + "You have not filled in the correct answers for all questions.\n"
                + "The Google Form will still be created correctly for students,\n"
                + "but you won't be able to export an answer key until you fill them in.\n\n"
                + "Do you want to create the form anyway?",
                ButtonType.YES, ButtonType.CANCEL);
            warn.setTitle("Answers Not Complete");
            warn.setHeaderText("Some answers are missing");
            return warn.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.YES;
        }
        return true;
    }


    private void checkForResume() {
        long uploaded = state.getQuestions().stream()
            .filter(q -> q.getUploadStatus() == UploadStatus.UPLOADED).count();
        if (uploaded > 0) {
            btnResume.setVisible(true);
            btnResume.setText("Resume (" + uploaded + " already uploaded)");
        }
    }

    private void updateValidationSummary() {
        long total    = state.getQuestions().size();
        long answered = state.getQuestions().stream().filter(this::isAnswered).count();
        long ready    = state.getQuestions().stream().filter(Question::isReadyForExport).count();

        validationSummary.setText(total + " questions  ·  "
            + answered + " with answers  ·  " + ready + " ready for export");

        if (readinessLabel != null) {
            if (answered < total) {
                readinessLabel.setText("⚠ " + (total - answered)
                    + " question(s) still need answers. Go to the Answers tab first.");
                readinessLabel.setStyle("-fx-text-fill:#e67e22;");
            } else {
                readinessLabel.setText("✓ All questions have answers. Ready to publish.");
                readinessLabel.setStyle("-fx-text-fill:#27ae60;");
            }
        }
    }

    private void unbind() {
        publishProgress.progressProperty().unbind();
        statusLabel.textProperty().unbind();
        publishProgress.setVisible(false);
    }

    private void showError(String msg) {
        new Alert(Alert.AlertType.ERROR, msg, ButtonType.OK).showAndWait();
    }
}