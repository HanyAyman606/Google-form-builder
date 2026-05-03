package com.formbuilder;

import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.stage.Stage;

import java.net.URL;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Application entry point.
 *
 * FIX (close behaviour):
 *  - If the project has been saved at least once (projectFile != null) and
 *    there are unsaved changes, the app auto-saves silently on close.
 *  - If the project was never saved (new project, no file), the user is still
 *    asked to confirm discarding.
 */
public class Main extends Application {

    @Override
    public void start(Stage primaryStage) throws Exception {
        URL fxmlUrl = getClass().getResource("/com/formbuilder/ui/main.fxml");
        if (fxmlUrl == null) {
            throw new IllegalStateException("main.fxml not found in resources");
        }

        FXMLLoader loader = new FXMLLoader(fxmlUrl);
        Scene scene = new Scene(loader.load(), 1200, 800);

        // Load stylesheet
        URL cssUrl = getClass().getResource("/com/formbuilder/ui/styles.css");
        if (cssUrl != null) {
            scene.getStylesheets().add(cssUrl.toExternalForm());
        }

        primaryStage.setTitle("Perfection Forms Builder");
        primaryStage.setScene(scene);
        primaryStage.setMinWidth(900);
        primaryStage.setMinHeight(600);

        // Close handler: auto-save if we have a file, else confirm discard
        primaryStage.setOnCloseRequest(event -> {
            AppState state = AppState.getInstance();
            if (state.isDirty()) {
                if (state.getProjectFile() != null) {
                    // Auto-save silently — never lose work
                    try {
                        new com.formbuilder.service.ProjectService()
                            .save(state.toProjectData(), state.getProjectFile());
                    } catch (Exception ex) {
                        // If auto-save fails, ask the user
                        javafx.scene.control.Alert alert = new javafx.scene.control.Alert(
                            javafx.scene.control.Alert.AlertType.CONFIRMATION,
                            "Auto-save failed: " + ex.getMessage() + "\nExit anyway?",
                            javafx.scene.control.ButtonType.YES,
                            javafx.scene.control.ButtonType.CANCEL
                        );
                        alert.setTitle("Save Failed");
                        alert.showAndWait().ifPresent(btn -> {
                            if (btn != javafx.scene.control.ButtonType.YES) {
                                event.consume();
                            }
                        });
                    }
                } else {
                    // Never saved — ask user
                    javafx.scene.control.Alert alert = new javafx.scene.control.Alert(
                        javafx.scene.control.Alert.AlertType.CONFIRMATION,
                        "You have unsaved changes that will be lost (project was never saved). Exit anyway?",
                        javafx.scene.control.ButtonType.YES,
                        javafx.scene.control.ButtonType.CANCEL
                    );
                    alert.setTitle("Unsaved Changes");
                    alert.showAndWait().ifPresent(btn -> {
                        if (btn != javafx.scene.control.ButtonType.YES) {
                            event.consume();
                        }
                    });
                }
            }
        });

        primaryStage.show();
    }

    public static void main(String[] args) {
        // Mute Apache Commons Logging (PDFBox's internal logger)
        System.setProperty("org.apache.commons.logging.Log",
            "org.apache.commons.logging.impl.NoOpLog");

        // Mute standard Java logging for PDFBox/FontBox
        Logger.getLogger("org.apache.fontbox").setLevel(Level.OFF);
        Logger.getLogger("org.apache.pdfbox").setLevel(Level.OFF);

        launch(args);
    }
}