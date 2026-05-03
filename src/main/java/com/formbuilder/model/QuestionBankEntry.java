package com.formbuilder.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.LocalDate;

/**
 * Wraps a Question with extra metadata for the question bank.
 * Stored in ~/.formsbuilder/bank.json alongside all other bank entries.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class QuestionBankEntry {

    @JsonProperty("question")
    private Question question;

    @JsonProperty("sourceExam")
    private String sourceExam;   // Name of the project this came from

    @JsonProperty("dateAdded")
    private String dateAdded;    // ISO date string e.g. "2025-09-01"

    @JsonProperty("usageCount")
    private int usageCount = 0;  // How many times this has been reused

    public QuestionBankEntry() {}

    public QuestionBankEntry(Question question, String sourceExam) {
        this.question   = question.copy(); // bank stores a clean copy
        this.sourceExam = sourceExam;
        this.dateAdded  = LocalDate.now().toString();
    }

    /** Returns a copy of the stored question ready to drop into a new project. */
    public Question createCopy() {
        usageCount++;
        return question.copy();
    }

    // ── Getters & Setters ──────────────────────────────────────────────────

    public Question getQuestion()                      { return question; }
    public void     setQuestion(Question question)     { this.question = question; }

    public String getSourceExam()                      { return sourceExam; }
    public void   setSourceExam(String sourceExam)     { this.sourceExam = sourceExam; }

    public String getDateAdded()                       { return dateAdded; }
    public void   setDateAdded(String dateAdded)       { this.dateAdded = dateAdded; }

    public int  getUsageCount()                { return usageCount; }
    public void setUsageCount(int usageCount)  { this.usageCount = usageCount; }
}