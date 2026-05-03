package com.formbuilder.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
public class FormSection {

    @JsonProperty("id")
    private String id = UUID.randomUUID().toString();

    @JsonProperty("title")
    private String title;           // e.g. "Part A — Multiple Choice"

    @JsonProperty("description")
    private String description;     // Optional subtitle shown in the form

    @JsonProperty("questionIds")
    private List<String> questionIds = new ArrayList<>();

    @JsonProperty("shuffle")
    private boolean shuffle = false; // Whether to randomise question order

    @JsonProperty("offset")
    private int offset = 0;          // Starting question number offset for display

    public FormSection() {}

    public FormSection(String title) {
        this.title = title;
    }

    // ── Getters & Setters ──────────────────────────────────────────────────

    public String getId()                              { return id; }
    public void   setId(String id)                     { this.id = id; }

    public String getTitle()                           { return title; }
    public void   setTitle(String title)               { this.title = title; }

    public String getDescription()                     { return description; }
    public void   setDescription(String description)   { this.description = description; }

    public List<String> getQuestionIds()                           { return questionIds; }
    public void         setQuestionIds(List<String> questionIds)   { this.questionIds = questionIds; }

    public boolean isShuffle()                { return shuffle; }
    public void    setShuffle(boolean shuffle){ this.shuffle = shuffle; }

    public int  getOffset()              { return offset; }
    public void setOffset(int offset)    { this.offset = offset; }

    @Override
    public String toString() {
        return "FormSection{title='" + title + "', questions=" + questionIds.size() + "}";
    }
}