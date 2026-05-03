package com.formbuilder.ui;

import com.formbuilder.AppState;
import com.formbuilder.model.FormSection;
import com.formbuilder.model.Question;
import com.formbuilder.model.QuestionType;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;

import java.net.URL;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Section Builder.
 *
 * FIXES (v3):
 *  1. selectionMode is set in code (not FXML) — FXML attribute was invalid
 *     causing "Error loading panel: sections_panel.fxml:46"
 *
 *  2. autoSetupIfEmpty() runs on init and on question-list changes so that
 *     sections appear automatically after PDF import.
 *
 *  3. Section cards show question count badge that updates live.
 *
 * v4 — Three assignment modes:
 *  1. Range mode: assign a contiguous range Q(from) to Q(to)
 *  2. Multi-select mode: checkbox dialog to pick/remove multiple questions
 *  3. Single mode: choose one question to assign
 */
public class SectionBuilderController implements Initializable {

    @FXML private ListView<Question> unassignedList;
    @FXML private VBox               sectionsContainer;
    @FXML private Label              unassignedCountLabel;

    private final AppState state = AppState.getInstance();
    private QuestionType currentFilter = null;

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        // FIX: set MULTIPLE selection in code, not in FXML (FXML attribute is invalid)
        unassignedList.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);

        refreshUnassigned();
        refreshSections();
        autoSetupIfEmpty();

        state.getQuestions().addListener(
            (javafx.collections.ListChangeListener<Question>) c -> {
                refreshUnassigned(); refreshSections(); autoSetupIfEmpty();
            });
    }

    // ── Filters ────────────────────────────────────────────────────────────

    @FXML private void onFilterAll()   { currentFilter = null;                 refreshUnassigned(); }
    @FXML private void onFilterMcq()   { currentFilter = QuestionType.MCQ;     refreshUnassigned(); }
    @FXML private void onFilterEssay() { currentFilter = QuestionType.PROBLEM; refreshUnassigned(); }

    // ── Auto-setup ─────────────────────────────────────────────────────────

    private void autoSetupIfEmpty() {
        if (!state.getSections().isEmpty()) return;
        if (state.getQuestions().isEmpty()) return;
        long mcq     = state.getQuestions().stream().filter(q -> q.getType() == QuestionType.MCQ).count();
        long written = state.getQuestions().stream().filter(q -> q.getType() == QuestionType.PROBLEM).count();
        if (mcq == 0 && written == 0) return;
        performAutoSetup(false);
    }

    @FXML
    private void onAutoSetup() {
        long mcq     = state.getQuestions().stream().filter(q -> q.getType() == QuestionType.MCQ).count();
        long written = state.getQuestions().stream().filter(q -> q.getType() == QuestionType.PROBLEM).count();
        if (mcq == 0 && written == 0) { showInfo("No questions imported yet."); return; }

        Alert c = new Alert(Alert.AlertType.CONFIRMATION,
            "Auto-create sections from question types?\n\n"
            + (mcq     > 0 ? "• MCQ Questions: "     + mcq     + "\n" : "")
            + (written > 0 ? "• Written Questions: "  + written + "\n" : "")
            + "\nExisting section assignments will be replaced.",
            ButtonType.YES, ButtonType.CANCEL);
        c.setTitle("Auto-Setup Sections");
        if (c.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.YES) return;
        performAutoSetup(true);
        showInfo("Done! Sections created automatically.");
    }

    private void performAutoSetup(boolean replace) {
        if (replace) {
            state.getQuestions().forEach(q -> q.setSectionId(null));
            state.getSections().clear();
        }

        long mcq     = state.getQuestions().stream().filter(q -> q.getType() == QuestionType.MCQ).count();
        long written = state.getQuestions().stream().filter(q -> q.getType() == QuestionType.PROBLEM).count();

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
        refreshUnassigned();
        refreshSections();
    }

    // ══════════════════════════════════════════════════════════════════
    //  ASSIGN — original (list-selected) assign
    // ══════════════════════════════════════════════════════════════════

    @FXML
    private void onAddToSection() {
        List<Question> selected = new ArrayList<>(
            unassignedList.getSelectionModel().getSelectedItems());
        if (selected.isEmpty()) { showInfo("Select questions on the left (hold Ctrl for multiple)."); return; }
        if (state.getSections().isEmpty()) { showInfo("Create a section first."); return; }

        FormSection target = pickSection("Assign " + selected.size() + " question(s) to:");
        if (target == null) return;

        assignQuestionsToSection(selected, target);
    }

    // ══════════════════════════════════════════════════════════════════
    //  ASSIGN — Range mode  (Q from → Q to)
    // ══════════════════════════════════════════════════════════════════

    @FXML
    private void onAssignRange() {
        if (state.getQuestions().isEmpty()) { showInfo("No questions available."); return; }
        if (state.getSections().isEmpty()) { showInfo("Create a section first."); return; }

        // Build a dialog with FROM and TO spinners
        Dialog<int[]> dialog = new Dialog<>();
        dialog.setTitle("Assign Range");
        dialog.setHeaderText("Assign questions by range");

        int maxQ = state.getQuestions().stream().mapToInt(Question::getNumber).max().orElse(1);
        int minQ = state.getQuestions().stream().mapToInt(Question::getNumber).min().orElse(1);

        Spinner<Integer> spnFrom = new Spinner<>(minQ, maxQ, minQ);
        Spinner<Integer> spnTo   = new Spinner<>(minQ, maxQ, maxQ);
        spnFrom.setEditable(true);
        spnTo.setEditable(true);
        spnFrom.setPrefWidth(100);
        spnTo.setPrefWidth(100);

        Label arrow = new Label("  →  ");
        arrow.setStyle("-fx-font-size:16px; -fx-font-weight:bold;");

        HBox rangeRow = new HBox(10, new Label("From Q"), spnFrom, arrow, new Label("To Q"), spnTo);
        rangeRow.setAlignment(Pos.CENTER_LEFT);
        rangeRow.setPadding(new Insets(10));

        // Action choice: Assign or Remove
        ToggleGroup actionGroup = new ToggleGroup();
        RadioButton rbAssign = new RadioButton("Assign to section");
        RadioButton rbRemove = new RadioButton("Remove from section");
        rbAssign.setToggleGroup(actionGroup);
        rbRemove.setToggleGroup(actionGroup);
        rbAssign.setSelected(true);

        HBox actionRow = new HBox(20, rbAssign, rbRemove);
        actionRow.setPadding(new Insets(4, 10, 10, 10));

        VBox content = new VBox(8, rangeRow, actionRow);
        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        dialog.getDialogPane().setPrefWidth(420);

        dialog.setResultConverter(btn -> {
            if (btn == ButtonType.OK) {
                // Commit spinner editors
                commitSpinnerValue(spnFrom);
                commitSpinnerValue(spnTo);
                int action = rbAssign.isSelected() ? 1 : 2;
                return new int[]{ spnFrom.getValue(), spnTo.getValue(), action };
            }
            return null;
        });

        Optional<int[]> result = dialog.showAndWait();
        if (result.isEmpty()) return;

        int from   = Math.min(result.get()[0], result.get()[1]);
        int to     = Math.max(result.get()[0], result.get()[1]);
        int action = result.get()[2];

        List<Question> matched = state.getQuestions().stream()
            .filter(q -> q.getNumber() >= from && q.getNumber() <= to)
            .collect(Collectors.toList());

        if (matched.isEmpty()) { showInfo("No questions found in range Q" + from + " – Q" + to + "."); return; }

        if (action == 1) {
            // Assign
            FormSection target = pickSection("Assign Q" + from + " – Q" + to + " (" + matched.size() + " questions) to:");
            if (target == null) return;
            assignQuestionsToSection(matched, target);
            showInfo("Assigned " + matched.size() + " question(s) (Q" + from + " – Q" + to + ") to \"" + target.getTitle() + "\".");
        } else {
            // Remove from sections
            matched.forEach(q -> {
                if (q.getSectionId() != null) {
                    // Also remove from FormSection's questionIds list
                    state.getSections().stream()
                        .filter(s -> s.getId().equals(q.getSectionId()))
                        .findFirst()
                        .ifPresent(s -> s.getQuestionIds().remove(q.getId()));
                    q.setSectionId(null);
                }
            });
            state.markDirty();
            refreshUnassigned();
            refreshSections();
            showInfo("Removed " + matched.size() + " question(s) (Q" + from + " – Q" + to + ") from their sections.");
        }
    }

    // ══════════════════════════════════════════════════════════════════
    //  ASSIGN — Multi-select mode  (checkbox dialog)
    // ══════════════════════════════════════════════════════════════════

    @FXML
    private void onAssignMultiSelect() {
        if (state.getQuestions().isEmpty()) { showInfo("No questions available."); return; }
        if (state.getSections().isEmpty()) { showInfo("Create a section first."); return; }

        Dialog<List<Question>> dialog = new Dialog<>();
        dialog.setTitle("Multi-Select Questions");
        dialog.setHeaderText("Check questions to assign or uncheck to remove");

        VBox checkboxContainer = new VBox(4);
        checkboxContainer.setPadding(new Insets(6));
        List<CheckBox> checkBoxes = new ArrayList<>();

        for (Question q : state.getQuestions()) {
            String typeTag = q.getType() == QuestionType.MCQ ? "MCQ" : "Written";
            String secLabel = "";
            if (q.getSectionId() != null) {
                secLabel = state.getSections().stream()
                    .filter(s -> s.getId().equals(q.getSectionId()))
                    .map(s -> " — " + s.getTitle())
                    .findFirst().orElse("");
            }
            CheckBox cb = new CheckBox("Q" + q.displayNumber() + "  [" + typeTag + "]" + secLabel);
            cb.setUserData(q);
            cb.setSelected(false);
            cb.setStyle("-fx-font-size:12px;");
            checkBoxes.add(cb);
            checkboxContainer.getChildren().add(cb);
        }

        // Select All / Deselect All buttons
        Button btnSelectAll = new Button("Select All");
        btnSelectAll.setStyle("-fx-font-size:11px; -fx-padding:4 10; -fx-cursor:hand;");
        btnSelectAll.setOnAction(e -> checkBoxes.forEach(cb -> cb.setSelected(true)));

        Button btnDeselectAll = new Button("Deselect All");
        btnDeselectAll.setStyle("-fx-font-size:11px; -fx-padding:4 10; -fx-cursor:hand;");
        btnDeselectAll.setOnAction(e -> checkBoxes.forEach(cb -> cb.setSelected(false)));

        HBox selectControls = new HBox(8, btnSelectAll, btnDeselectAll);
        selectControls.setAlignment(Pos.CENTER_LEFT);
        selectControls.setPadding(new Insets(4, 6, 6, 6));

        // Action choice: Assign or Remove
        ToggleGroup actionGroup = new ToggleGroup();
        RadioButton rbAssign = new RadioButton("Assign selected to section");
        RadioButton rbRemove = new RadioButton("Remove selected from section");
        rbAssign.setToggleGroup(actionGroup);
        rbRemove.setToggleGroup(actionGroup);
        rbAssign.setSelected(true);

        HBox actionRow = new HBox(20, rbAssign, rbRemove);
        actionRow.setPadding(new Insets(6));

        ScrollPane scrollPane = new ScrollPane(checkboxContainer);
        scrollPane.setFitToWidth(true);
        scrollPane.setPrefHeight(350);
        scrollPane.setStyle("-fx-background-color:white;");

        VBox content = new VBox(6, selectControls, scrollPane, new Separator(), actionRow);
        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        dialog.getDialogPane().setPrefWidth(450);

        dialog.setResultConverter(btn -> {
            if (btn == ButtonType.OK) {
                return checkBoxes.stream()
                    .filter(CheckBox::isSelected)
                    .map(cb -> (Question) cb.getUserData())
                    .collect(Collectors.toList());
            }
            return null;
        });

        Optional<List<Question>> result = dialog.showAndWait();
        if (result.isEmpty() || result.get().isEmpty()) return;

        List<Question> selected = result.get();

        if (rbAssign.isSelected()) {
            FormSection target = pickSection("Assign " + selected.size() + " question(s) to:");
            if (target == null) return;
            assignQuestionsToSection(selected, target);
            showInfo("Assigned " + selected.size() + " question(s) to \"" + target.getTitle() + "\".");
        } else {
            // Remove from sections
            selected.forEach(q -> {
                if (q.getSectionId() != null) {
                    state.getSections().stream()
                        .filter(s -> s.getId().equals(q.getSectionId()))
                        .findFirst()
                        .ifPresent(s -> s.getQuestionIds().remove(q.getId()));
                    q.setSectionId(null);
                }
            });
            state.markDirty();
            refreshUnassigned();
            refreshSections();
            showInfo("Removed " + selected.size() + " question(s) from their sections.");
        }
    }

    // ══════════════════════════════════════════════════════════════════
    //  ASSIGN — Single mode  (one question)
    // ══════════════════════════════════════════════════════════════════

    @FXML
    private void onAssignSingle() {
        if (state.getQuestions().isEmpty()) { showInfo("No questions available."); return; }
        if (state.getSections().isEmpty()) { showInfo("Create a section first."); return; }

        // Pick a single question via ChoiceDialog
        ChoiceDialog<Question> dialog = new ChoiceDialog<>(
            state.getQuestions().get(0), state.getQuestions());
        dialog.setTitle("Assign Single Question");
        dialog.setHeaderText("Pick a question to assign:");

        // Custom string converter so the dropdown shows readable labels
        dialog.getItems().clear();
        dialog.getItems().addAll(state.getQuestions());

        var result = dialog.showAndWait();
        if (result.isEmpty()) return;

        Question q = result.get();
        FormSection target = pickSection("Assign Q" + q.displayNumber() + " to:");
        if (target == null) return;

        assignQuestionsToSection(List.of(q), target);
        showInfo("Assigned Q" + q.displayNumber() + " to \"" + target.getTitle() + "\".");
    }

    // ══════════════════════════════════════════════════════════════════
    //  Shared helpers
    // ══════════════════════════════════════════════════════════════════

    /**
     * Opens a section picker dialog if there are multiple sections,
     * or returns the single section if there's only one.
     */
    private FormSection pickSection(String headerText) {
        if (state.getSections().size() == 1) {
            return state.getSections().get(0);
        }
        ChoiceDialog<FormSection> dialog = new ChoiceDialog<>(
            state.getSections().get(0), state.getSections());
        dialog.setTitle("Choose Section");
        dialog.setHeaderText(headerText);
        return dialog.showAndWait().orElse(null);
    }

    /** Assigns a list of questions to the given section. */
    private void assignQuestionsToSection(List<Question> questions, FormSection target) {
        for (Question q : questions) {
            // If already assigned to a different section, remove from old
            if (q.getSectionId() != null && !q.getSectionId().equals(target.getId())) {
                state.getSections().stream()
                    .filter(s -> s.getId().equals(q.getSectionId()))
                    .findFirst()
                    .ifPresent(s -> s.getQuestionIds().remove(q.getId()));
            }
            q.setSectionId(target.getId());
            if (!target.getQuestionIds().contains(q.getId())) {
                target.getQuestionIds().add(q.getId());
            }
        }
        state.markDirty();
        refreshUnassigned();
        refreshSections();
    }

    /** Forces the spinner to commit any text typed in the editor. */
    private void commitSpinnerValue(Spinner<Integer> spinner) {
        try {
            String text = spinner.getEditor().getText();
            spinner.getValueFactory().setValue(Integer.parseInt(text.trim()));
        } catch (NumberFormatException ignored) {}
    }

    @FXML private void onRemoveSelected() {
        showInfo("Use the Remove button next to each question in the section cards on the right.");
    }

    @FXML
    private void onNewSection() {
        TextInputDialog d = new TextInputDialog("New Section");
        d.setTitle("Add Section"); d.setHeaderText("Section name:");
        d.showAndWait().ifPresent(name -> {
            if (name.isBlank()) return;
            state.getSections().add(new FormSection(name));
            state.markDirty(); refreshSections();
        });
    }

    // ── Refresh ────────────────────────────────────────────────────────────

    private void refreshUnassigned() {
        var list = state.getQuestions().stream()
            .filter(q -> q.getSectionId() == null || q.getSectionId().isBlank())
            .filter(q -> currentFilter == null || q.getType() == currentFilter)
            .toList();
        unassignedList.getItems().setAll(list);
        unassignedList.setCellFactory(lv -> new ListCell<>() {
            @Override protected void updateItem(Question q, boolean empty) {
                super.updateItem(q, empty);
                if (empty || q == null) { setText(null); return; }
                setText("Q" + q.getNumber() + "  ["
                    + (q.getType() == QuestionType.MCQ ? "MCQ" : "Written") + "]");
            }
        });
        long total = state.getQuestions().stream()
            .filter(q -> q.getSectionId() == null || q.getSectionId().isBlank()).count();
        if (unassignedCountLabel != null) unassignedCountLabel.setText(total + " unassigned");
    }

    private void refreshSections() {
        sectionsContainer.getChildren().clear();
        if (state.getSections().isEmpty()) {
            Label hint = new Label(
                "No sections yet.\n\n"
                + "Import questions from PDF and sections are created automatically.\n"
                + "Or click ⚡ Auto-Setup above.");
            hint.setWrapText(true);
            hint.setStyle("-fx-text-fill:#888; -fx-font-size:12px; -fx-padding:12;");
            sectionsContainer.getChildren().add(hint);
            return;
        }
        state.getSections().forEach(s -> sectionsContainer.getChildren().add(buildCard(s)));
    }

    private VBox buildCard(FormSection section) {
        var assigned = state.getQuestions().stream()
            .filter(q -> section.getId().equals(q.getSectionId())).toList();

        VBox card = new VBox(8);
        card.setStyle("-fx-background-color:white; -fx-border-color:#dde1e5;"
                    + "-fx-border-radius:8; -fx-background-radius:8; -fx-padding:14;");

        // Title row
        TextField tfTitle = new TextField(section.getTitle());
        tfTitle.setStyle("-fx-font-weight:bold; -fx-font-size:13px;");
        tfTitle.textProperty().addListener((o, a, v) -> { section.setTitle(v); state.markDirty(); });

        TextField tfDesc = new TextField(section.getDescription() != null ? section.getDescription() : "");
        tfDesc.setPromptText("Description (optional)");
        tfDesc.setStyle("-fx-font-size:11px; -fx-text-fill:#666;");
        tfDesc.textProperty().addListener((o, a, v) -> {
            section.setDescription(v.isBlank() ? null : v); state.markDirty();
        });

        Label badge = new Label(assigned.size() + " Q");
        badge.setStyle("-fx-background-color:#2980b9; -fx-text-fill:white;"
                     + "-fx-background-radius:10; -fx-padding:2 8;"
                     + "-fx-font-size:11px; -fx-font-weight:bold;");

        Button btnDel = new Button("✕");
        btnDel.setStyle("-fx-background-color:#e74c3c; -fx-text-fill:white;"
                      + "-fx-background-radius:4; -fx-border-width:0; -fx-cursor:hand; -fx-padding:3 8;");
        btnDel.setOnAction(e -> {
            Alert conf = new Alert(Alert.AlertType.CONFIRMATION,
                "Delete \"" + section.getTitle() + "\"? Questions will be unassigned.",
                ButtonType.YES, ButtonType.CANCEL);
            if (conf.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.YES) return;
            state.getQuestions().stream().filter(q -> section.getId().equals(q.getSectionId()))
                .forEach(q -> q.setSectionId(null));
            state.getSections().remove(section);
            state.markDirty(); refreshUnassigned(); refreshSections();
        });

        HBox titleRow = new HBox(8, tfTitle, badge, btnDel);
        titleRow.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        HBox.setHgrow(tfTitle, Priority.ALWAYS);
        card.getChildren().addAll(titleRow, tfDesc);

        if (assigned.isEmpty()) {
            Label empty = new Label("Empty — assign questions from the left.");
            empty.setStyle("-fx-text-fill:#aaa; -fx-font-style:italic; -fx-font-size:11px;");
            card.getChildren().add(empty);
        } else {
            for (Question q : assigned) {
                String typeTag = q.getType() == QuestionType.MCQ ? "MCQ" : "Written";
                Label lbl = new Label("Q" + q.getNumber() + " [" + typeTag + "]");
                lbl.setMinWidth(100);
                Button rem = new Button("Remove");
                rem.setStyle("-fx-background-color:#aaa; -fx-text-fill:white;"
                           + "-fx-background-radius:4; -fx-border-width:0;"
                           + "-fx-cursor:hand; -fx-font-size:11px; -fx-padding:3 8;");
                rem.setOnAction(e -> {
                    q.setSectionId(null);
                    section.getQuestionIds().remove(q.getId());
                    state.markDirty(); refreshUnassigned(); refreshSections();
                });
                HBox row = new HBox(8, lbl, rem); row.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
                card.getChildren().add(row);
            }
        }
        return card;
    }

    private void showInfo(String msg) { new Alert(Alert.AlertType.INFORMATION, msg, ButtonType.OK).showAndWait(); }
}