package com.formbuilder.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
public class Question {

    @JsonProperty("id")
    private String id = UUID.randomUUID().toString();

    @JsonProperty("number")
    private int number;

    /**
     * For sub-questions (e.g. 39-i, 39-ii).
     * 0 = this is a standalone question (default).
     * >0 = this is a sub-question of that parent number.
     */
    @JsonProperty("parentNumber")
    private int parentNumber = 0;

    /**
     * Roman-numeral or letter label for sub-questions: "i", "ii", "iii", "iv", "v" …
     * Null/blank for standalone questions.
     */
    @JsonProperty("subLabel")
    private String subLabel;

    @JsonProperty("imagePath")
    private String imagePath;           // Relative path to cropped image file

    @JsonProperty("driveImageUrl")
    private String driveImageUrl;       // Populated after Drive upload

    @JsonProperty("type")
    private QuestionType type = QuestionType.MCQ;

    @JsonProperty("choiceA")
    private String choiceA;

    @JsonProperty("choiceB")
    private String choiceB;

    @JsonProperty("choiceC")
    private String choiceC;

    @JsonProperty("choiceD")
    private String choiceD;

    @JsonProperty("correctAnswer")
    private char correctAnswer = 'A';

    @JsonProperty("pointValue")
    private int pointValue = 1;

    @JsonProperty("sectionId")
    private String sectionId;

    @JsonProperty("uploadStatus")
    private UploadStatus uploadStatus = UploadStatus.PENDING;

    @JsonProperty("visionConfidence")
    private double visionConfidence;    // 0.0–1.0 from Gemini

    @JsonProperty("sectionHint")
    private String sectionHint;         // Section header text detected by Gemini

    @JsonProperty("bankTags")
    private List<String> bankTags = new ArrayList<>();
private int displayOrder;
    // ── Default constructor (required by Jackson) ──────────────────────────
    public Question() {}

    // ── Deep copy — used for undo support and question bank import ─────────
    public Question copy() {
        Question q = new Question();
        q.id            = UUID.randomUUID().toString(); // fresh ID for the copy
        q.number        = this.number;
        q.parentNumber  = this.parentNumber;
        q.subLabel      = this.subLabel;
        q.imagePath     = this.imagePath;
        q.driveImageUrl = null;                         // copy starts un-uploaded
        q.type          = this.type;
        q.choiceA       = this.choiceA;
        q.choiceB       = this.choiceB;
        q.choiceC       = this.choiceC;
        q.choiceD       = this.choiceD;
        q.correctAnswer = this.correctAnswer;
        q.pointValue    = this.pointValue;
        q.sectionId     = null;
        q.uploadStatus  = UploadStatus.PENDING;
        q.visionConfidence = this.visionConfidence;
        q.sectionHint   = this.sectionHint;
        q.bankTags      = new ArrayList<>(this.bankTags);
        
        return q;
    }

    // ── Validation helper ──────────────────────────────────────────────────
    /**
     * Returns true when this question is ready to be exported to Google Forms.
     * A question is blocked if visionConfidence < 0.70 AND it has not been
     * manually confirmed (we treat visionConfidence == 1.0 as "teacher confirmed").
     */
    public boolean isReadyForExport() {
        if (imagePath == null || imagePath.isBlank()) return false;
        if (correctAnswer < 'A' || correctAnswer > 'D')  return false;
        if (isBlank(choiceA) || isBlank(choiceB) || isBlank(choiceC) || isBlank(choiceD)) return false;
        if (visionConfidence > 0 && visionConfidence < 0.70) return false; // blocked unless confirmed
        return true;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    // ── Getters & Setters ──────────────────────────────────────────────────

    public String getId()                          { return id; }
    public void   setId(String id)                 { this.id = id; }

    public int    getNumber()                      { return number; }
    public void   setNumber(int number)            { this.number = number; }

    public int    getParentNumber()                        { return parentNumber; }
    public void   setParentNumber(int parentNumber)        { this.parentNumber = parentNumber; }

    public String getSubLabel()                            { return subLabel; }
    public void   setSubLabel(String subLabel)             { this.subLabel = subLabel; }

    /**
     * Returns the display string for this question's number.
     * Standalone: "39"   Sub-question: "39-i"
     */
    public String displayNumber() {
        if (parentNumber > 0 && subLabel != null && !subLabel.isBlank()) {
            return parentNumber + "-" + subLabel;
        }
        return String.valueOf(number);
    }

    /** Returns true if this question is a sub-question of another. */
    public boolean isSubQuestion() {
        return parentNumber > 0 && subLabel != null && !subLabel.isBlank();
    }

    public String getImagePath()                   { return imagePath; }
    public void   setImagePath(String imagePath)   { this.imagePath = imagePath; }

    public String getDriveImageUrl()               { return driveImageUrl; }
    public void   setDriveImageUrl(String url)     { this.driveImageUrl = url; }

    public QuestionType getType()                          { return type; }
    public void         setType(QuestionType type)         { this.type = type; }

    public String getChoiceA()                     { return choiceA; }
    public void   setChoiceA(String choiceA)       { this.choiceA = choiceA; }

    public String getChoiceB()                     { return choiceB; }
    public void   setChoiceB(String choiceB)       { this.choiceB = choiceB; }

    public String getChoiceC()                     { return choiceC; }
    public void   setChoiceC(String choiceC)       { this.choiceC = choiceC; }

    public String getChoiceD()                     { return choiceD; }
    public void   setChoiceD(String choiceD)       { this.choiceD = choiceD; }

    public char   getCorrectAnswer()               { return correctAnswer; }
    public void   setCorrectAnswer(char c)         { this.correctAnswer = c; }

    public int    getPointValue()                  { return pointValue; }
    public void   setPointValue(int pointValue)    { this.pointValue = pointValue; }

    public String getSectionId()                   { return sectionId; }
    public void   setSectionId(String sectionId)   { this.sectionId = sectionId; }

    public UploadStatus getUploadStatus()                       { return uploadStatus; }
    public void         setUploadStatus(UploadStatus status)    { this.uploadStatus = status; }

    public double getVisionConfidence()                { return visionConfidence; }
    public void   setVisionConfidence(double v)        { this.visionConfidence = v; }

    public String getSectionHint()                     { return sectionHint; }
    public void   setSectionHint(String sectionHint)   { this.sectionHint = sectionHint; }

    public List<String> getBankTags()                      { return bankTags; }
    public void         setBankTags(List<String> bankTags) { this.bankTags = bankTags; }

    @Override
    public String toString() {
        return "Question{number=" + number + ", type=" + type + ", status=" + uploadStatus + "}";
    }
}