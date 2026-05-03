package com.formbuilder.ui;

import com.formbuilder.AppState;
import com.formbuilder.service.ProjectService;
import javafx.event.EventType;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.fxml.Initializable;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;

import java.io.File;
import java.net.URL;
import java.util.ResourceBundle;

public class MainWindowController implements Initializable {

    public static final EventType<javafx.event.Event> SHOW_MAIN_EVENT =
        new EventType<>(javafx.event.Event.ANY, "SHOW_MAIN");

    @FXML private BorderPane  rootPane;
    @FXML private VBox        sidebarVBox;
    @FXML private Label       statusLabel;
    @FXML private ProgressBar progressBar;
    @FXML private Label       titleLabel;
    @FXML private StackPane   contentArea;

    @FXML private Button btnImport;
    @FXML private Button btnEditor;
    @FXML private Button btnSections;
    @FXML private Button btnAnswers;
    @FXML private Button btnExport;
    @FXML private Button btnBank;

    private final ProjectService projectService = new ProjectService();
    private final AppState       state          = AppState.getInstance();

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        state.projectNameProperty().addListener((obs, o, n) ->
            titleLabel.setText("Perfection Forms Builder — " + n));
        progressBar.setVisible(false);
        rootPane.addEventHandler(SHOW_MAIN_EVENT, e -> showMainWorkflow());
        showWelcome();
    }

    private void showWelcome() {
        sidebarVBox.setVisible(false);
        sidebarVBox.setManaged(false);
        titleLabel.setText("Perfection Forms Builder");
        loadPanel("welcome_panel.fxml");
    }

    void showMainWorkflow() {
        sidebarVBox.setVisible(true);
        sidebarVBox.setManaged(true);
        titleLabel.setText("Perfection Forms Builder — " + state.getProjectName());
        navigateTo("import_panel.fxml");
        setActive(btnImport);
    }

    @FXML private void onBackToWelcome() {
        if (state.isDirty()) {
            // FIX: auto-save if we have a project file instead of prompting
            if (state.getProjectFile() != null) {
                silentSave();
            } else if (!confirmDiscard()) {
                return;
            }
        }
        showWelcome();
    }

    @FXML private void onImport()   { navigateTo("import_panel.fxml");   setActive(btnImport); }
    @FXML private void onEditor()   { navigateTo("editor_panel.fxml");   setActive(btnEditor); }
    @FXML private void onSections() { navigateTo("sections_panel.fxml"); setActive(btnSections); }
    @FXML private void onAnswers()  { navigateTo("answer_panel.fxml");   setActive(btnAnswers); }
    @FXML private void onExport()   { navigateTo("export_panel.fxml");   setActive(btnExport); }
    @FXML private void onBank()     { navigateTo("bank_panel.fxml");     setActive(btnBank); }

    private void navigateTo(String fxmlFile) {
        // Auto-save current state on every panel switch if we have a project file
        if (state.isDirty() && state.getProjectFile() != null) {
            silentSave();
        }
        try {
            URL url = getClass().getResource("/com/formbuilder/ui/" + fxmlFile);
            if (url == null) { setStatus("Panel not found: " + fxmlFile); return; }
            Node panel = FXMLLoader.load(url);
            contentArea.getChildren().setAll(panel);
        } catch (Exception e) {
            setStatus("Error loading panel: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void loadPanel(String fxmlFile) { navigateTo(fxmlFile); }

    private void setActive(Button active) {
        for (Button b : new Button[]{btnImport, btnEditor, btnSections, btnAnswers, btnExport, btnBank}) {
            if (b != null) b.getStyleClass().remove("sidebar-active");
        }
        if (active != null) active.getStyleClass().add("sidebar-active");
    }

    @FXML
    private void onNew() {
        if (state.isDirty()) {
            if (state.getProjectFile() != null) silentSave();
            else if (!confirmDiscard()) return;
        }
        TextInputDialog dialog = new TextInputDialog("Untitled Exam");
        dialog.setTitle("New Project");
        dialog.setHeaderText("Project name:");
        dialog.showAndWait().ifPresent(name -> {
            if (name.isBlank()) return;
            DirectoryChooser dc = new DirectoryChooser();
            dc.setTitle("Choose project folder");
            File dir = dc.showDialog(rootPane.getScene().getWindow());
            if (dir != null) {
                state.newProject(name, dir);
                WelcomePanelController.addToRecent(new File(dir, name + ".fbp").getAbsolutePath());
                showMainWorkflow();
                setStatus("New project: " + name);
            }
        });
    }

    @FXML
    private void onOpen() {
        if (state.isDirty()) {
            if (state.getProjectFile() != null) silentSave();
            else if (!confirmDiscard()) return;
        }
        FileChooser fc = new FileChooser();
        fc.setTitle("Open Project");
        fc.getExtensionFilters().add(
            new FileChooser.ExtensionFilter("Forms Builder Project", "*.fbp"));
        File file = fc.showOpenDialog(rootPane.getScene().getWindow());
        if (file != null) {
            try {
                var result = projectService.load(file);
                state.loadProject(result.project(), file);
                if (result.hasWarnings()) showWarnings(result.warnings());
                WelcomePanelController.addToRecent(file.getAbsolutePath());
                showMainWorkflow();
                setStatus("Opened: " + file.getName());
            } catch (Exception e) {
                new Alert(Alert.AlertType.ERROR, "Could not open: " + e.getMessage(),
                    ButtonType.OK).showAndWait();
            }
        }
    }

    @FXML private void onSave() {
        if (state.getProjectFile() == null) { onSaveAs(); return; }
        saveToFile(state.getProjectFile());
    }

    @FXML private void onSaveAs() {
        FileChooser fc = new FileChooser();
        fc.setTitle("Save Project As");
        fc.getExtensionFilters().add(
            new FileChooser.ExtensionFilter("Forms Builder Project", "*.fbp"));
        if (state.getProjectDir() != null) fc.setInitialDirectory(state.getProjectDir());
        fc.setInitialFileName(state.getProjectName() + ".fbp");
        File file = fc.showSaveDialog(rootPane.getScene().getWindow());
        if (file != null) saveToFile(file);
    }

    private void saveToFile(File file) {
        try {
            projectService.save(state.toProjectData(), file);
            // Update the stored file reference in AppState
            if (!file.equals(state.getProjectFile())) {
                // SaveAs to a new location — update AppState
                state.loadProject(state.toProjectData(), file);
            }
            state.markClean();
            setStatus("Saved: " + file.getName());
        } catch (Exception e) {
            new Alert(Alert.AlertType.ERROR, "Save failed: " + e.getMessage(),
                ButtonType.OK).showAndWait();
        }
    }

    /**
     * Silent background save — no dialog, no error popup on success.
     * Used for auto-save on panel switch and on close.
     */
    private void silentSave() {
        if (state.getProjectFile() == null) return;
        try {
            projectService.save(state.toProjectData(), state.getProjectFile());
            state.markClean();
            setStatus("Auto-saved ✓");
        } catch (Exception e) {
            setStatus("Auto-save failed: " + e.getMessage());
        }
    }

    public void setStatus(String message) { statusLabel.setText(message); }

    private boolean confirmDiscard() {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION,
            "Unsaved changes. Discard?", ButtonType.YES, ButtonType.CANCEL);
        return alert.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.YES;
    }

    private void showWarnings(java.util.List<String> warnings) {
        Alert alert = new Alert(Alert.AlertType.WARNING);
        alert.setTitle("Missing Images");
        alert.setContentText(String.join("\n", warnings));
        alert.showAndWait();
    }
}