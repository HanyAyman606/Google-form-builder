package com.formbuilder.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * DTO returned by VisionAnalysisService after Gemini analyses a question image.
 * Transient — not persisted to the .fbp file.
 *
 * ── FIX (April 2025) ──────────────────────────────────────────────────────
 * Added @JsonProperty("problemAnswer") — was in the Gemini prompt but missing
 * from this class, causing Jackson to silently drop the field.
 * ──────────────────────────────────────────────────────────────────────────
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class QuestionAnalysis {

    @JsonProperty("questionType")
    public String questionType;         // "MCQ" or "PROBLEM"

    @JsonProperty("questionNumber")
    public Integer questionNumber;      // as detected from the image

    /** MCQ only — choice text read from the image */
    @JsonProperty("choiceA")  public String choiceA;
    @JsonProperty("choiceB")  public String choiceB;
    @JsonProperty("choiceC")  public String choiceC;
    @JsonProperty("choiceD")  public String choiceD;

    /**
     * PROBLEM only — the correct answer written below the question on the image.
     * e.g. "(20 V - 5 A - 6.25x10^19 electrons)"
     * NEVER shown to students — feeds DistractorGeneratorService only.
     *
     * FIX: This field was present in the Gemini prompt but absent from the class,
     * causing Jackson to silently discard it on deserialization.
     */
    @JsonProperty("problemAnswer")
    public String problemAnswer;

    /** Section header detected (e.g. "Session 4", "Q2: Problems") */
    @JsonProperty("sectionHint")
    public String sectionHint;

    @JsonProperty("confidence")
    public double confidence;

    public QuestionAnalysis() {}

    public boolean isAutoAccepted() { return confidence >= 0.90; }
    public boolean isBlocked()      { return confidence <  0.70; }

    @Override
    public String toString() {
        return "QuestionAnalysis{type=" + questionType
                + ", qNum=" + questionNumber
                + ", confidence=" + confidence + "}";
    }
}