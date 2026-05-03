package com.formbuilder.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.List;

/**
 * Root object serialized to/from the .fbp project file.
 * Contains the full state of a single exam project.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ProjectData {

    @JsonProperty("projectName")
    private String projectName = "Untitled Exam";

    @JsonProperty("formTitle")
    private String formTitle = "";

    @JsonProperty("publishedFormUrl")
    private String publishedFormUrl;    // Populated after successful publish

    @JsonProperty("publishedFormId")
    private String publishedFormId;

    @JsonProperty("questions")
    private List<Question> questions = new ArrayList<>();

    @JsonProperty("sections")
    private List<FormSection> sections = new ArrayList<>();

    @JsonProperty("version")
    private String version = "3.0";

    public ProjectData() {}

    // ── Getters & Setters ──────────────────────────────────────────────────

    public String getProjectName()                         { return projectName; }
    public void   setProjectName(String projectName)       { this.projectName = projectName; }

    public String getFormTitle()                           { return formTitle; }
    public void   setFormTitle(String formTitle)           { this.formTitle = formTitle; }

    public String getPublishedFormUrl()                    { return publishedFormUrl; }
    public void   setPublishedFormUrl(String url)          { this.publishedFormUrl = url; }

    public String getPublishedFormId()                     { return publishedFormId; }
    public void   setPublishedFormId(String id)            { this.publishedFormId = id; }

    public List<Question>    getQuestions()                            { return questions; }
    public void              setQuestions(List<Question> questions)    { this.questions = questions; }

    public List<FormSection> getSections()                             { return sections; }
    public void              setSections(List<FormSection> sections)   { this.sections = sections; }

    public String getVersion()                  { return version; }
    public void   setVersion(String version)    { this.version = version; }
}