package com.formbuilder.ui;

import com.formbuilder.AppState;
import com.formbuilder.model.Question;
import com.formbuilder.model.QuestionType;
import com.formbuilder.service.DistractorGenerator;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;

import java.io.File;
import java.net.URL;
import java.util.*;

/**
 * Answer Panel — fast answer entry for MCQ and Essay questions.
 *
 * ── ROOT CAUSE FIX (v3) ──────────────────────────────────────────────────
 *
 * PROBLEM: Teacher clicks radio B, hits Save → badge still shows "Not answered".
 *
 * WHY: isAnswered() checked correctAnswer AND choiceA != blank.
 *      After import, choiceA = "" (blank). onSaveAll() only set correctAnswer.
 *      So isAnswered() kept returning false even though the answer was saved.
 *
 * FIX: For image-based MCQ (choices are in the image, not typed), when the
 *      teacher saves an answer we also auto-set choices to "A","B","C","D"
 *      so that isReadyForExport() passes. The choice TEXT for these questions
 *      IS the letter — the content is embedded in the question image.
 *
 * ── OTHER FIXES ──────────────────────────────────────────────────────────
 *  - 8 questions per page (was 4)
 *  - Blank default: radio buttons not pre-selected for unanswered questions
 *  - pendingMcq cleared after save (prevents stale state on next page)
 *  - Dashboard: coloured indicator listing every unanswered question number
 */
public class AnswerPanelController implements Initializable {

    @FXML private Button      btnMcqMode;
    @FXML private Button      btnEssayMode;
    @FXML private Label       modeLabel;
    @FXML private Label       progressLabel;
    @FXML private Button      btnPrevPage;
    @FXML private Button      btnNextPage;
    @FXML private Button      btnSaveAll;
    @FXML private VBox        contentArea;
    @FXML private ProgressBar answerProgress;
    @FXML private Label       dashboardLabel;

    private final AppState state = AppState.getInstance();

    private enum Mode { MCQ, ESSAY }
    private Mode currentMode = Mode.MCQ;

    private List<Question> mcqQuestions   = new ArrayList<>();
    private List<Question> essayQuestions = new ArrayList<>();

    private int mcqPage  = 0;
    private int essayIdx = 0;

    private static final int MCQ_PER_PAGE = 8;

    private final Map<String, Character>  pendingMcq   = new LinkedHashMap<>();
    private final Map<String, EssayDraft> pendingEssay = new LinkedHashMap<>();

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        refreshQuestionLists();
        setMode(Mode.MCQ);
        state.getQuestions().addListener(
            (javafx.collections.ListChangeListener<Question>) c -> {
                refreshQuestionLists(); rebuild();
            });
    }

    @FXML private void onMcqMode()   { setMode(Mode.MCQ); }
    @FXML private void onEssayMode() { setMode(Mode.ESSAY); }

    private void setMode(Mode mode) {
        currentMode = mode; mcqPage = 0; essayIdx = 0;
        btnMcqMode.setStyle(mode == Mode.MCQ   ? activeTab() : inactiveTab());
        btnEssayMode.setStyle(mode == Mode.ESSAY ? activeTab() : inactiveTab());
        modeLabel.setText(mode == Mode.MCQ
            ? "MCQ — click the correct letter (A/B/C/D) for each question, then Save Answers"
            : "Written/Essay — type the correct answer and generate distractors");
        rebuild();
    }

    @FXML private void onPrevPage() {
        if (currentMode == Mode.MCQ) { if (mcqPage > 0) { mcqPage--; rebuild(); } }
        else                         { if (essayIdx > 0) { essayIdx--; rebuild(); } }
    }

    @FXML private void onNextPage() {
        if (currentMode == Mode.MCQ) { if (mcqPage < maxMcqPage()) { mcqPage++; rebuild(); } }
        else { if (essayIdx < essayQuestions.size() - 1) { essayIdx++; rebuild(); } }
    }

    @FXML
    private void onSaveAll() {
        int saved = 0;

        for (Map.Entry<String, Character> e : pendingMcq.entrySet()) {
            if (e.getValue() == 0) continue;
            Question q = findById(e.getKey());
            if (q == null) continue;

            char answer = e.getValue();
            q.setCorrectAnswer(answer);

            // Auto-fill letter choices for image-based MCQ (content is in the image)
            if (q.getChoiceA() == null || q.getChoiceA().isBlank()) {
                q.setChoiceA("A"); q.setChoiceB("B");
                q.setChoiceC("C"); q.setChoiceD("D");
            }
            state.markDirty();
            saved++;
        }

        for (Map.Entry<String, EssayDraft> e : pendingEssay.entrySet()) {
            Question q = findById(e.getKey());
            EssayDraft d = e.getValue();
            if (q != null && d.isComplete()) {
                q.setChoiceA(d.choiceA); q.setChoiceB(d.choiceB);
                q.setChoiceC(d.choiceC); q.setChoiceD(d.choiceD);
                q.setCorrectAnswer(d.correctSlot);
                state.markDirty(); saved++;
            }
        }

        pendingMcq.clear();
        updateProgress(); rebuild();

        Alert a = new Alert(Alert.AlertType.INFORMATION, saved + " answer(s) saved ✓", ButtonType.OK);
        a.setTitle("Saved"); a.setHeaderText(null); a.showAndWait();
    }

    private void rebuild() {
        contentArea.getChildren().clear();
        if (currentMode == Mode.MCQ) buildMcqPage();
        else buildEssayPage();
        updateNavButtons(); updateProgress(); updateDashboard();
    }

    // ── MCQ page ───────────────────────────────────────────────────────────

    private void buildMcqPage() {
        if (mcqQuestions.isEmpty()) {
            contentArea.getChildren().add(emptyLabel("No MCQ questions. Import questions first.")); return;
        }
        int start = mcqPage * MCQ_PER_PAGE;
        int end   = Math.min(start + MCQ_PER_PAGE, mcqQuestions.size());
        for (int i = start; i < end; i++)
            contentArea.getChildren().add(buildMcqCard(mcqQuestions.get(i)));
    }

    private VBox buildMcqCard(Question q) {
        VBox card = new VBox(10);
        card.setStyle(cardStyle());

        boolean answered = isAnswered(q);
        Label header = new Label("Q" + q.displayNumber());
        header.setFont(Font.font("Segoe UI", FontWeight.BOLD, 14));
        header.setTextFill(Color.web("#2c3e50"));

        Label badge = new Label(answered ? "✓ " + q.getCorrectAnswer() : "⚠ Not answered");
        badge.setStyle(answered
            ? "-fx-background-color:#27ae60; -fx-text-fill:white; -fx-background-radius:8; -fx-padding:2 8; -fx-font-size:11px;"
            : "-fx-background-color:#e74c3c; -fx-text-fill:white; -fx-background-radius:8; -fx-padding:2 8; -fx-font-size:11px;");

        HBox hdr = new HBox(10, header, badge);
        hdr.setAlignment(Pos.CENTER_LEFT);
        card.getChildren().add(hdr);

        if (q.getImagePath() != null && state.getProjectDir() != null) {
            File img = new File(state.getProjectDir(), q.getImagePath());
            if (img.exists()) {
                ImageView iv = new ImageView(new Image(img.toURI().toString()));
                iv.setFitWidth(500); iv.setPreserveRatio(true);
                card.getChildren().add(iv);
            }
        }

        ToggleGroup tg = new ToggleGroup();
        HBox radioRow = new HBox(28);
        radioRow.setAlignment(Pos.CENTER_LEFT);
        radioRow.setStyle("-fx-padding:8 0 4 0;");

        char pending = pendingMcq.getOrDefault(q.getId(), (char) 0);
        char display = (pending != 0) ? pending : (answered ? q.getCorrectAnswer() : (char) 0);

        for (char letter : new char[]{'A', 'B', 'C', 'D'}) {
            RadioButton rb = new RadioButton(String.valueOf(letter));
            rb.setToggleGroup(tg);
            rb.setFont(Font.font("Segoe UI", FontWeight.BOLD, 20));
            rb.setStyle(display == letter ? "-fx-cursor:hand; -fx-text-fill:#2980b9;" : "-fx-cursor:hand;");
            if (display == letter) rb.setSelected(true);
            final char fl = letter;
            rb.selectedProperty().addListener((obs, old, sel) -> {
                if (sel) { pendingMcq.put(q.getId(), fl); rb.setStyle("-fx-cursor:hand; -fx-text-fill:#2980b9;"); }
                else      rb.setStyle("-fx-cursor:hand;");
            });
            radioRow.getChildren().add(rb);
        }
        card.getChildren().add(radioRow);
        return card;
    }

    // ── Essay page ─────────────────────────────────────────────────────────

    private void buildEssayPage() {
        if (essayQuestions.isEmpty()) {
            contentArea.getChildren().add(emptyLabel(
                "No written questions. Set type to PROBLEM in the Editor tab.")); return;
        }
        Question q = essayQuestions.get(essayIdx);
        EssayDraft draft = pendingEssay.computeIfAbsent(q.getId(), id -> new EssayDraft(q));

        VBox card = new VBox(14); card.setStyle(cardStyle());
        Label header = new Label("Q" + q.displayNumber() + "  —  Written");
        header.setFont(Font.font("Segoe UI", FontWeight.BOLD, 15));
        header.setTextFill(Color.web("#2c3e50"));
        card.getChildren().add(header);

        if (q.getImagePath() != null && state.getProjectDir() != null) {
            File img = new File(state.getProjectDir(), q.getImagePath());
            if (img.exists()) {
                ImageView iv = new ImageView(new Image(img.toURI().toString()));
                iv.setFitWidth(560); iv.setPreserveRatio(true);
                card.getChildren().add(iv);
            }
        }
        card.getChildren().add(new Separator());

        Label lbl = new Label("Correct answer:");
        lbl.setFont(Font.font("Segoe UI", FontWeight.BOLD, 13));

        TextField tfCorrect = new TextField(draft.correctText);
        tfCorrect.setPromptText("e.g. 3.18  or  320  or  6.25×10^19");
        tfCorrect.setStyle("-fx-font-size:14px; -fx-padding:8 10;");
        tfCorrect.setPrefWidth(300);
        tfCorrect.textProperty().addListener((obs, old, val) -> {
            draft.correctText = val; draft.clearChoices();
            updateChoiceGrid(card, draft, q);
        });

        Button btnGen = new Button("⚙ Generate Wrong Answers");
        btnGen.setStyle("-fx-background-color:#8e44ad; -fx-text-fill:white; "
            + "-fx-background-radius:5; -fx-padding:8 16; -fx-cursor:hand; -fx-font-weight:bold;");
        btnGen.setOnAction(e -> {
            if (draft.correctText == null || draft.correctText.isBlank()) {
                showError("Enter the correct answer first."); return;
            }
            draft.assignChoices(DistractorGenerator.generate(draft.correctText));
            updateChoiceGrid(card, draft, q);
        });

        HBox inputRow = new HBox(12, tfCorrect, btnGen);
        inputRow.setAlignment(Pos.CENTER_LEFT);
        card.getChildren().addAll(lbl, inputRow);
        card.getChildren().add(new Separator());
        Label choicesHdr = new Label("Assign choices (mark which slot is correct):");
        choicesHdr.setStyle("-fx-font-weight:bold; -fx-font-size:12px; -fx-text-fill:#555;");
        card.getChildren().add(choicesHdr);

        VBox choiceGrid = new VBox(8); choiceGrid.setUserData("choiceGrid");
        fillChoiceGrid(choiceGrid, draft, q);
        card.getChildren().add(choiceGrid);
        contentArea.getChildren().add(card);
    }

    private void updateChoiceGrid(VBox card, EssayDraft draft, Question q) {
        for (int i = 0; i < card.getChildren().size(); i++) {
            if (card.getChildren().get(i) instanceof VBox vb && "choiceGrid".equals(vb.getUserData())) {
                fillChoiceGrid(vb, draft, q); return;
            }
        }
    }

    private void fillChoiceGrid(VBox grid, EssayDraft draft, Question q) {
        grid.getChildren().clear();
        if (!draft.hasChoices()) {
            Label h = new Label("Enter the answer above and click Generate.");
            h.setStyle("-fx-text-fill:#999; -fx-font-style:italic; -fx-font-size:12px;");
            grid.getChildren().add(h); return;
        }
        ToggleGroup sg = new ToggleGroup();
        String[] choices = {draft.choiceA, draft.choiceB, draft.choiceC, draft.choiceD};
        char[]   letters = {'A','B','C','D'};
        for (int i = 0; i < 4; i++) {
            final int idx = i; final char letter = letters[i];
            Label lbl = new Label(String.valueOf(letter));
            lbl.setFont(Font.font("Segoe UI", FontWeight.BOLD, 14));
            lbl.setTextFill(Color.web("#2980b9")); lbl.setMinWidth(20);
            TextField tf = new TextField(choices[i] != null ? choices[i] : "");
            tf.setPrefWidth(220); tf.setStyle("-fx-font-size:13px; -fx-padding:6 8;");
            tf.textProperty().addListener((obs, old, val) -> {
                switch (idx) { case 0->draft.choiceA=val; case 1->draft.choiceB=val;
                               case 2->draft.choiceC=val; case 3->draft.choiceD=val; }
            });
            RadioButton rb = new RadioButton("Correct"); rb.setToggleGroup(sg);
            rb.setStyle("-fx-cursor:hand; -fx-text-fill:#27ae60; -fx-font-weight:bold;");
            if (draft.correctSlot == letter) rb.setSelected(true);
            rb.selectedProperty().addListener((obs, old, sel) -> { if (sel) draft.correctSlot = letter; });
            HBox row = new HBox(10, lbl, tf, rb); row.setAlignment(Pos.CENTER_LEFT);
            grid.getChildren().add(row);
        }
        Label hint = new Label("✎ Edit any choice. Radio = correct answer.");
        hint.setStyle("-fx-text-fill:#888; -fx-font-size:11px; -fx-font-style:italic;");
        grid.getChildren().add(hint);
    }

    // ── Dashboard indicator ────────────────────────────────────────────────

    private void updateDashboard() {
        if (dashboardLabel == null) return;
        long total    = state.getQuestions().size();
        long answered = state.getQuestions().stream().filter(this::isAnswered).count();
        long missing  = total - answered;
        if (total == 0) { dashboardLabel.setText(""); return; }
        StringBuilder sb = new StringBuilder("✓ ").append(answered).append(" answered");
        if (missing > 0) {
            sb.append("   |   ⚠ ").append(missing).append(" missing: ");
            StringJoiner j = new StringJoiner(", ");
            state.getQuestions().stream().filter(q -> !isAnswered(q)).forEach(q -> j.add("Q" + q.displayNumber()));
            sb.append(j);
        } else { sb.append("   ✅ All answered!"); }
        dashboardLabel.setText(sb.toString());
        dashboardLabel.setStyle(missing > 0
            ? "-fx-text-fill:#e67e22; -fx-font-size:12px;"
            : "-fx-text-fill:#27ae60; -fx-font-size:12px; -fx-font-weight:bold;");
    }

    // ── Utils ──────────────────────────────────────────────────────────────

    private boolean isAnswered(Question q) {
        return q.getCorrectAnswer() >= 'A' && q.getCorrectAnswer() <= 'D'
            && q.getChoiceA() != null && !q.getChoiceA().isBlank();
    }

    private void refreshQuestionLists() {
        mcqQuestions   = state.getQuestions().stream().filter(q -> q.getType() == QuestionType.MCQ).toList();
        essayQuestions = state.getQuestions().stream().filter(q -> q.getType() == QuestionType.PROBLEM).toList();
    }

    private int maxMcqPage() {
        return mcqQuestions.isEmpty() ? 0 : (mcqQuestions.size() - 1) / MCQ_PER_PAGE;
    }

    private void updateNavButtons() {
        if (currentMode == Mode.MCQ) {
            btnPrevPage.setDisable(mcqPage == 0);
            btnNextPage.setDisable(mcqPage >= maxMcqPage());
            progressLabel.setText("Page " + (mcqPage + 1) + " / " + (maxMcqPage() + 1)
                + "  (" + mcqQuestions.size() + " MCQ)");
        } else {
            btnPrevPage.setDisable(essayIdx == 0);
            btnNextPage.setDisable(essayIdx >= essayQuestions.size() - 1);
            progressLabel.setText("Q " + (essayIdx + 1) + " / " + essayQuestions.size() + " (written)");
        }
    }

    private void updateProgress() {
        long total = state.getQuestions().size();
        if (total == 0) { answerProgress.setProgress(0); return; }
        answerProgress.setProgress((double) state.getQuestions().stream().filter(this::isAnswered).count() / total);
    }

    private Question findById(String id) {
        return state.getQuestions().stream().filter(q -> q.getId().equals(id)).findFirst().orElse(null);
    }

    private Label emptyLabel(String t) {
        Label l = new Label(t); l.setWrapText(true);
        l.setStyle("-fx-text-fill:#999; -fx-font-style:italic; -fx-font-size:13px; -fx-padding:30;");
        return l;
    }

    private void showError(String msg) {
        new Alert(Alert.AlertType.ERROR, msg, ButtonType.OK).showAndWait();
    }

    private String cardStyle() {
        return "-fx-background-color:white; -fx-border-color:#dde1e5; "
             + "-fx-border-radius:8; -fx-background-radius:8; -fx-padding:16;";
    }

    private String activeTab() {
        return "-fx-background-color:#2980b9; -fx-text-fill:white; -fx-background-radius:5; "
             + "-fx-border-width:0; -fx-font-weight:bold; -fx-padding:8 24; -fx-cursor:hand;";
    }

    private String inactiveTab() {
        return "-fx-background-color:#ecf0f1; -fx-text-fill:#555; -fx-background-radius:5; "
             + "-fx-border-width:0; -fx-padding:8 24; -fx-cursor:hand;";
    }

    private static class EssayDraft {
        String correctText = "";
        String choiceA, choiceB, choiceC, choiceD;
        char   correctSlot = 0;

        EssayDraft(Question q) {
            choiceA = q.getChoiceA(); choiceB = q.getChoiceB();
            choiceC = q.getChoiceC(); choiceD = q.getChoiceD();
            char ca = q.getCorrectAnswer();
            correctSlot = (ca >= 'A' && ca <= 'D'
                && q.getChoiceA() != null && !q.getChoiceA().isBlank()) ? ca : 0;
        }

        boolean hasChoices() { return choiceA != null && !choiceA.isBlank(); }

        boolean isComplete() {
            return hasChoices()
                && choiceB != null && !choiceB.isBlank()
                && choiceC != null && !choiceC.isBlank()
                && choiceD != null && !choiceD.isBlank()
                && correctSlot >= 'A' && correctSlot <= 'D';
        }

        void assignChoices(List<String> distractors) {
            char[] slots = {'A','B','C','D'};
            int ci = new Random().nextInt(4); correctSlot = slots[ci];
            List<String> wrong = new ArrayList<>(distractors); int wi = 0;
            String[] ch = new String[4];
            for (int i = 0; i < 4; i++) ch[i] = (i == ci) ? correctText : (wi < wrong.size() ? wrong.get(wi++) : "—");
            choiceA=ch[0]; choiceB=ch[1]; choiceC=ch[2]; choiceD=ch[3];
        }

        void clearChoices() { choiceA = choiceB = choiceC = choiceD = null; }
    }
}