package com.formbuilder.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Holds one student's answers imported from a Google Sheets / CSV response export.
 * Map key = question number (1-based), Map value = student's answer char ('A'–'D').
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ExamResponse {

    @JsonProperty("studentEmail")
    private String studentEmail;

    @JsonProperty("timestamp")
    private String timestamp;

    /** question number → answer char ('A', 'B', 'C', or 'D') */
    @JsonProperty("answers")
    private Map<Integer, Character> answers = new LinkedHashMap<>();

    @JsonProperty("score")
    private int score;

    @JsonProperty("totalMarks")
    private int totalMarks;

    public ExamResponse() {}

    public double getPercentage() {
        if (totalMarks == 0) return 0;
        return (score * 100.0) / totalMarks;
    }

    public boolean isPassing(double passThreshold) {
        return getPercentage() >= passThreshold;
    }

    // ── Getters & Setters ──────────────────────────────────────────────────

    public String getStudentEmail()                        { return studentEmail; }
    public void   setStudentEmail(String studentEmail)     { this.studentEmail = studentEmail; }

    public String getTimestamp()                           { return timestamp; }
    public void   setTimestamp(String timestamp)           { this.timestamp = timestamp; }

    public Map<Integer, Character> getAnswers()                            { return answers; }
    public void                    setAnswers(Map<Integer, Character> a)   { this.answers = a; }

    public int  getScore()               { return score; }
    public void setScore(int score)      { this.score = score; }

    public int  getTotalMarks()                  { return totalMarks; }
    public void setTotalMarks(int totalMarks)    { this.totalMarks = totalMarks; }
}