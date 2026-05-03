package com.formbuilder.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Teacher-only answer key.
 * Maps question number → correct answer char.
 * Never sent to the Forms API — only used locally and in exported PDF/CSV.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class AnswerKey {

    @JsonProperty("examTitle")
    private String examTitle;

    /** question number (1-based) → correct answer ('A'–'D') */
    @JsonProperty("answers")
    private Map<Integer, Character> answers = new LinkedHashMap<>();

    /** question number → point value */
    @JsonProperty("pointValues")
    private Map<Integer, Integer> pointValues = new LinkedHashMap<>();

    public AnswerKey() {}

    public AnswerKey(String examTitle) {
        this.examTitle = examTitle;
    }

    /** Populate from the current question list. */
    public static AnswerKey fromQuestions(java.util.List<Question> questions, String title) {
        AnswerKey key = new AnswerKey(title);
        for (Question q : questions) {
            key.answers.put(q.getNumber(), q.getCorrectAnswer());
            key.pointValues.put(q.getNumber(), q.getPointValue());
        }
        return key;
    }

    /** Score a single student response against this answer key. */
    public int score(ExamResponse response) {
        int total = 0;
        for (Map.Entry<Integer, Character> entry : response.getAnswers().entrySet()) {
            Character correct = answers.get(entry.getKey());
            if (correct != null && correct.equals(entry.getValue())) {
                total += pointValues.getOrDefault(entry.getKey(), 1);
            }
        }
        return total;
    }

    public int totalMarks() {
        return pointValues.values().stream().mapToInt(Integer::intValue).sum();
    }

    // ── Getters & Setters ──────────────────────────────────────────────────

    public String getExamTitle()                       { return examTitle; }
    public void   setExamTitle(String examTitle)       { this.examTitle = examTitle; }

    public Map<Integer, Character> getAnswers()                        { return answers; }
    public void                    setAnswers(Map<Integer, Character> a){ this.answers = a; }

    public Map<Integer, Integer> getPointValues()                          { return pointValues; }
    public void                  setPointValues(Map<Integer, Integer> pv)  { this.pointValues = pv; }
}