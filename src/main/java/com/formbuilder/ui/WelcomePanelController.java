package com.formbuilder.ui;

import com.formbuilder.AppState;
import com.formbuilder.service.ProjectService;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.*;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;

import java.io.*;
import java.net.URL;
import java.nio.file.*;
import java.util.*;
import java.util.prefs.Preferences;

/**
 * Welcome screen — shown on startup before anything is loaded.
 * Handles New Project, Open Project, and Recent Projects list.
 */
public class WelcomePanelController implements Initializable {

    @FXML private ListView<String> recentList;

    private final AppState       state          = AppState.getInstance();
    private final ProjectService projectService = new ProjectService();

    // Store recent projects in Java Preferences (persistent across runs)
    private static final Preferences PREFS =
        Preferences.userNodeForPackage(WelcomePanelController.class);
    private static final String RECENT_KEY = "recentProjects";
    private static final int    MAX_RECENT  = 6;

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        loadRecentList();

        recentList.setCellFactory(lv -> new ListCell<>() {
            @Override protected void updateItem(String path, boolean empty) {
                super.updateItem(path, empty);
                if (empty || path == null) { setText(null); return; }
                File f = new File(path);
                setText(f.getName().replace(".fbp", "") + "\n" + f.getParent());
                setStyle("-fx-font-size:13px; -fx-text-fill:#ccd6e0; "
                       + "-fx-background-color:transparent; -fx-padding:8 12;");
            }
        });

        recentList.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) {
                String path = recentList.getSelectionModel().getSelectedItem();
                if (path != null) openFile(new File(path));
            }
        });
    }

    @FXML
    private void onNew() {
        TextInputDialog nameDialog = new TextInputDialog("Untitled Exam");
        nameDialog.setTitle("New Project");
        nameDialog.setHeaderText("Project name");
        nameDialog.setContentText("Name:");
        styleDialog(nameDialog);

        nameDialog.showAndWait().ifPresent(name -> {
            if (name.isBlank()) return;
            DirectoryChooser dc = new DirectoryChooser();
            dc.setTitle("Choose folder for project");
            File dir = dc.showDialog(recentList.getScene().getWindow());
            if (dir == null) return;

            state.newProject(name, dir);
            addToRecent(new File(dir, name + ".fbp").getAbsolutePath());
            navigateToMain();
        });
    }

    @FXML
    private void onOpen() {
        FileChooser fc = new FileChooser();
        fc.setTitle("Open Project");
        fc.getExtensionFilters().add(
            new FileChooser.ExtensionFilter("Forms Builder Project", "*.fbp"));
        File file = fc.showOpenDialog(recentList.getScene().getWindow());
        if (file != null) openFile(file);
    }

    private void openFile(File file) {
        try {
            var result = projectService.load(file);
            state.loadProject(result.project(), file);
            if (result.hasWarnings()) {
                Alert a = new Alert(Alert.AlertType.WARNING,
                    String.join("\n", result.warnings()), ButtonType.OK);
                a.setTitle("Missing Images"); a.showAndWait();
            }
            addToRecent(file.getAbsolutePath());
            navigateToMain();
        } catch (Exception e) {
            new Alert(Alert.AlertType.ERROR,
                "Could not open project:\n" + e.getMessage(),
                ButtonType.OK).showAndWait();
        }
    }

    private void navigateToMain() {
        // Tell MainWindowController to show the main layout
        // We do this by firing a custom event on the scene root
        recentList.getScene().getRoot()
            .fireEvent(new javafx.event.Event(MainWindowController.SHOW_MAIN_EVENT));
    }

    // ── Recent projects ────────────────────────────────────────────────────

    private void loadRecentList() {
        String raw = PREFS.get(RECENT_KEY, "");
        if (!raw.isBlank()) {
            Arrays.stream(raw.split("\\|"))
                .filter(p -> new File(p).exists())
                .forEach(recentList.getItems()::add);
        }
        if (recentList.getItems().isEmpty()) {
            recentList.setPlaceholder(new Label("No recent projects"));
        }
    }

    static void addToRecent(String path) {
        String raw = PREFS.get(RECENT_KEY, "");
        List<String> list = new ArrayList<>(
            raw.isBlank() ? List.of() : Arrays.asList(raw.split("\\|")));
        list.remove(path);
        list.add(0, path);
        if (list.size() > MAX_RECENT) list = list.subList(0, MAX_RECENT);
        PREFS.put(RECENT_KEY, String.join("|", list));
    }

    private void styleDialog(Dialog<?> d) {
        d.getDialogPane().setStyle("-fx-font-family:'Segoe UI'; -fx-font-size:13px;");
    }
}
