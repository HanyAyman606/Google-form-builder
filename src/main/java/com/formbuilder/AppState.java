package com.formbuilder;

import com.formbuilder.model.FormSection;
import com.formbuilder.model.ProjectData;
import com.formbuilder.model.Question;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

import java.io.File;

/**
 * Singleton that holds the authoritative application state.
 *
 * All JavaFX panels bind to the ObservableLists here.
 * Services operate on plain Java objects; they write back here via setters.
 * This keeps Services free of any JavaFX dependency.
 *
 * FIX: publishedFormUrl and publishedFormId are now tracked in AppState
 * and round-tripped through toProjectData() / loadProject() so they
 * survive save → close → reopen cycles.
 */
public class AppState {

    // ── Singleton ──────────────────────────────────────────────────────────
    private static AppState instance;

    public static AppState getInstance() {
        if (instance == null) instance = new AppState();
        return instance;
    }

    private AppState() {}

    // ── Observable state ───────────────────────────────────────────────────

    /** The current project's question list — bound to by all editor panels. */
    private final ObservableList<Question> questions =
            FXCollections.observableArrayList();

    /** The current project's section list. */
    private final ObservableList<FormSection> sections =
            FXCollections.observableArrayList();

    /** The currently selected question in the editor panel. */
    private final ObjectProperty<Question> selectedQuestion =
            new SimpleObjectProperty<>();

    /** The project file path (null = unsaved new project). */
    private final ObjectProperty<File> projectFile =
            new SimpleObjectProperty<>();

    /** The project root directory (parent of the .fbp file). */
    private final ObjectProperty<File> projectDir =
            new SimpleObjectProperty<>();

    /** Display name shown in the title bar. */
    private final StringProperty projectName =
            new SimpleStringProperty("Untitled Exam");

    /** Tracks whether there are unsaved changes. */
    private boolean dirty = false;

    // ── Published form info (persisted in .fbp) ────────────────────────────
    private String publishedFormUrl = null;
    private String publishedFormId  = null;

    // ── Load a project into state ──────────────────────────────────────────

    public void loadProject(ProjectData data, File fbpFile) {
        questions.setAll(data.getQuestions());
        sections.setAll(data.getSections());
        projectFile.set(fbpFile);
        projectDir.set(fbpFile.getParentFile());
        projectName.set(data.getProjectName());
        selectedQuestion.set(null);
        // Restore published form info
        publishedFormUrl = data.getPublishedFormUrl();
        publishedFormId  = data.getPublishedFormId();
        dirty = false;
    }

    /** Resets state for a brand-new project. */
    public void newProject(String name, File dir) {
        questions.clear();
        sections.clear();
        projectFile.set(null);
        projectDir.set(dir);
        projectName.set(name);
        selectedQuestion.set(null);
        publishedFormUrl = null;
        publishedFormId  = null;
        dirty = false;
    }

    /** Snapshot the current observable state back into a ProjectData for saving. */
    public ProjectData toProjectData() {
        ProjectData data = new ProjectData();
        data.setProjectName(projectName.get());
        data.setFormTitle(projectName.get());
        data.setQuestions(new java.util.ArrayList<>(questions));
        data.setSections(new java.util.ArrayList<>(sections));
        // Persist published form info
        data.setPublishedFormUrl(publishedFormUrl);
        data.setPublishedFormId(publishedFormId);
        return data;
    }

    // ── Getters ────────────────────────────────────────────────────────────

    public ObservableList<Question>    getQuestions()       { return questions; }
    public ObservableList<FormSection> getSections()        { return sections; }

    public ObjectProperty<Question>   selectedQuestionProperty() { return selectedQuestion; }
    public Question                   getSelectedQuestion()       { return selectedQuestion.get(); }
    public void                       setSelectedQuestion(Question q) { selectedQuestion.set(q); }

    public ObjectProperty<File>       projectFileProperty() { return projectFile; }
    public File                       getProjectFile()      { return projectFile.get(); }

    public ObjectProperty<File>       projectDirProperty()  { return projectDir; }
    public File                       getProjectDir()       { return projectDir.get(); }

    public StringProperty             projectNameProperty() { return projectName; }
    public String                     getProjectName()      { return projectName.get(); }

    public boolean isDirty()                { return dirty; }
    public void    markDirty()              { dirty = true; }
    public void    markClean()              { dirty = false; }

    public void setProjectName(String name) { this.projectName.set(name); }

    // ── Published form info ────────────────────────────────────────────────

    public String getPublishedFormUrl()               { return publishedFormUrl; }
    public void   setPublishedFormUrl(String url)     { this.publishedFormUrl = url; }

    public String getPublishedFormId()                { return publishedFormId; }
    public void   setPublishedFormId(String id)       { this.publishedFormId = id; }
}