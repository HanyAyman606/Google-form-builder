package com.formbuilder.ui;

import com.formbuilder.AppState;
import com.formbuilder.model.Question;
import com.formbuilder.model.QuestionBankEntry;
import com.formbuilder.service.QuestionBankService;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.VBox;

import java.io.File;
import java.net.URL;
import java.util.*;

/**
 * Question Bank panel controller.
 *
 * Matches fx:controller="com.formbuilder.ui.QuestionBankController" in bank_panel.fxml.
 *
 * Phase 2 (P2-07): Fully implemented.
 *  - Search / clear
 *  - Results shown as thumbnail cards; click to select/deselect
 *  - "Add selected to current project" imports copies into AppState
 *  - Source exam name and date added shown on each card
 */
public class QuestionBankController implements Initializable {

    @FXML private TextField  tfSearch;
    @FXML private Label      resultCountLabel;
    @FXML private FlowPane   bankResultsPane;
    @FXML private Label      selectedCountLabel;
    @FXML private Button     btnImportSelected;

    private final AppState          state       = AppState.getInstance();
    private final QuestionBankService bankService = new QuestionBankService();

    /** Currently displayed entries (matches the last search). */
    private List<QuestionBankEntry> displayedEntries = new ArrayList<>();

    /** Selected entries (by question ID). */
    private final Set<String> selectedIds = new LinkedHashSet<>();

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        btnImportSelected.setDisable(true);
        loadResults(bankService.loadAll());
    }

    // ── Search ─────────────────────────────────────────────────────────────

    @FXML
    private void onSearch() {
        String query = tfSearch.getText();
        loadResults(bankService.search(query));
    }

    @FXML
    private void onClear() {
        tfSearch.clear();
        selectedIds.clear();
        loadResults(bankService.loadAll());
    }

    // ── Import selected into current project ───────────────────────────────

    @FXML
    private void onImportSelected() {
        if (state.getProjectDir() == null) {
            showError("Open or create a project first (File → New).");
            return;
        }
        if (selectedIds.isEmpty()) return;

        int nextNumber = state.getQuestions().size() + 1;
        int imported   = 0;

        for (QuestionBankEntry entry : displayedEntries) {
            String qId = entry.getQuestion().getId();
            if (!selectedIds.contains(qId)) continue;

            try {
                Question copy = bankService.importIntoProject(entry, state.getProjectDir(), nextNumber);
                state.getQuestions().add(copy);
                nextNumber++;
                imported++;
            } catch (Exception e) {
                showError("Failed to import Q" + entry.getQuestion().getNumber()
                    + ": " + e.getMessage());
            }
        }

        if (imported > 0) {
            state.markDirty();
            selectedIds.clear();
            updateSelectionCount();
            showInfo(imported + " question(s) added to the current project.");
        }
    }

    // ── Build results grid ─────────────────────────────────────────────────

    private void loadResults(List<QuestionBankEntry> entries) {
        displayedEntries = entries;
        bankResultsPane.getChildren().clear();
        selectedIds.clear();
        updateSelectionCount();

        if (entries.isEmpty()) {
            resultCountLabel.setText("No questions in bank.");
            return;
        }

        resultCountLabel.setText(entries.size() + " question(s) found.");

        // Bank image dir is next to the bank.json file
        File bankDir = new File(System.getProperty("user.home"), ".formsbuilder");

        for (QuestionBankEntry entry : entries) {
            bankResultsPane.getChildren().add(buildCard(entry, bankDir));
        }
    }

    private VBox buildCard(QuestionBankEntry entry, File bankDir) {
        Question q = entry.getQuestion();
        String   qId = q.getId();

        // Thumbnail image
        ImageView iv = new ImageView();
        iv.setFitWidth(130);
        iv.setFitHeight(100);
        iv.setPreserveRatio(true);

        if (q.getImagePath() != null) {
            File img = new File(bankDir, q.getImagePath());
            if (img.exists()) iv.setImage(new Image(img.toURI().toString()));
        }

        Label lblNum    = new Label("Q" + q.getNumber());
        Label lblSource = new Label(entry.getSourceExam() != null ? entry.getSourceExam() : "");
        lblSource.setStyle("-fx-font-size: 10px; -fx-text-fill: #888;");
        Label lblDate   = new Label(entry.getDateAdded() != null ? entry.getDateAdded() : "");
        lblDate.setStyle("-fx-font-size: 10px; -fx-text-fill: #aaa;");
        Label lblUsage  = new Label("Used " + entry.getUsageCount() + "×");
        lblUsage.setStyle("-fx-font-size: 10px; -fx-text-fill: #aaa;");

        VBox card = new VBox(4, iv, lblNum, lblSource, lblDate, lblUsage);
        card.setStyle(baseCardStyle(false));
        card.setUserData(qId);

        card.setOnMouseClicked(e -> {
            boolean isSelected = selectedIds.contains(qId);
            if (isSelected) {
                selectedIds.remove(qId);
                card.setStyle(baseCardStyle(false));
            } else {
                selectedIds.add(qId);
                card.setStyle(baseCardStyle(true));
            }
            updateSelectionCount();
        });

        return card;
    }

    private String baseCardStyle(boolean selected) {
        String border = selected ? "#2980b9" : "#dde1e5";
        String bg     = selected ? "#eef5fb" : "white";
        int    width  = selected ? 2 : 1;
        return "-fx-background-color: " + bg + "; -fx-border-color: " + border + "; " +
               "-fx-border-radius: 8; -fx-background-radius: 8; -fx-border-width: " + width + "; " +
               "-fx-padding: 8; -fx-cursor: hand; -fx-alignment: CENTER;";
    }

    private void updateSelectionCount() {
        selectedCountLabel.setText(selectedIds.size() + " selected");
        btnImportSelected.setDisable(selectedIds.isEmpty());
    }

    private void showError(String msg) {
        Alert a = new Alert(Alert.AlertType.ERROR, msg, ButtonType.OK);
        a.setTitle("Question Bank");
        a.showAndWait();
    }

    private void showInfo(String msg) {
        Alert a = new Alert(Alert.AlertType.INFORMATION, msg, ButtonType.OK);
        a.setTitle("Question Bank");
        a.showAndWait();
    }
}