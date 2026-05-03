package com.formbuilder.ui;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
import javafx.scene.Cursor;
import javafx.scene.Scene;
import javafx.scene.SnapshotParameters;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;

/**
 * A modal dialog that lets the user visually crop a question image.
 *
 * Usage:
 *     ImageCropDialog dialog = new ImageCropDialog(ownerWindow, imageFile);
 *     dialog.showAndWait();             // blocks
 *     if (dialog.isCropped()) {
 *         File result = dialog.getResultFile();   // saved cropped file
 *     }
 */
public class ImageCropDialog {

    private final Stage stage;
    private final Image originalImage;
    private final File  sourceFile;

    private boolean cropped = false;
    private File    resultFile;

    // Crop rectangle in IMAGE coordinates
    private double cropX, cropY, cropW, cropH;

    // Drag state
    private double dragStartX, dragStartY;
    private boolean dragging = false;

    // Display scale factor (image → canvas)
    private double scale;
    private double offsetX, offsetY;

    // Canvas size
    private static final double MAX_CANVAS_W = 900;
    private static final double MAX_CANVAS_H = 650;

    public ImageCropDialog(Window owner, File imageFile) {
        this.sourceFile = imageFile;
        this.originalImage = new Image(imageFile.toURI().toString());

        stage = new Stage();
        stage.initOwner(owner);
        stage.initModality(Modality.APPLICATION_MODAL);
        stage.setTitle("Crop Image — " + imageFile.getName());
        stage.setResizable(false);

        buildUI();
    }

    private void buildUI() {
        double imgW = originalImage.getWidth();
        double imgH = originalImage.getHeight();

        // Compute scale to fit in canvas
        double scaleW = MAX_CANVAS_W / imgW;
        double scaleH = MAX_CANVAS_H / imgH;
        scale = Math.min(scaleW, Math.min(scaleH, 1.0)); // don't upscale

        double canvasW = imgW * scale;
        double canvasH = imgH * scale;
        offsetX = 0;
        offsetY = 0;

        // Initialize crop to full image
        cropX = 0; cropY = 0;
        cropW = imgW; cropH = imgH;

        Canvas canvas = new Canvas(canvasW, canvasH);
        drawCanvas(canvas);

        // ── Mouse handlers for drag-to-crop ──

        canvas.setOnMousePressed(e -> {
            dragStartX = e.getX();
            dragStartY = e.getY();
            dragging = true;
        });

        canvas.setOnMouseDragged(e -> {
            if (!dragging) return;

            double x1 = Math.max(0, Math.min(dragStartX, e.getX()));
            double y1 = Math.max(0, Math.min(dragStartY, e.getY()));
            double x2 = Math.min(canvasW, Math.max(dragStartX, e.getX()));
            double y2 = Math.min(canvasH, Math.max(dragStartY, e.getY()));

            // Convert canvas coordinates to image coordinates
            cropX = x1 / scale;
            cropY = y1 / scale;
            cropW = (x2 - x1) / scale;
            cropH = (y2 - y1) / scale;

            drawCanvas(canvas);
        });

        canvas.setOnMouseReleased(e -> {
            dragging = false;
            // Ensure minimum crop size (at least 10px in image coords)
            if (cropW < 10 || cropH < 10) {
                cropX = 0; cropY = 0;
                cropW = imgW; cropH = imgH;
                drawCanvas(canvas);
            }
        });

        canvas.setCursor(Cursor.CROSSHAIR);

        // ── Info label ──
        Label infoLabel = new Label("Click and drag to select crop area. Then click Apply.");
        infoLabel.setStyle("-fx-font-size:12px; -fx-text-fill:#555;");

        Label sizeLabel = new Label(String.format("Original: %.0f × %.0f px", imgW, imgH));
        sizeLabel.setStyle("-fx-font-size:11px; -fx-text-fill:#888;");

        // ── Buttons ──
        Button btnApply = new Button("✓ Apply Crop");
        btnApply.setStyle("-fx-background-color:#27ae60; -fx-text-fill:white;"
            + "-fx-background-radius:5; -fx-border-width:0; -fx-font-weight:bold;"
            + "-fx-padding:10 24; -fx-font-size:13px; -fx-cursor:hand;");
        btnApply.setOnAction(e -> {
            applyCrop();
            stage.close();
        });

        Button btnReset = new Button("↺ Reset");
        btnReset.setStyle("-fx-background-color:transparent; -fx-text-fill:#e67e22;"
            + "-fx-border-color:#e67e22; -fx-border-width:1; -fx-border-radius:5;"
            + "-fx-background-radius:5; -fx-padding:10 20; -fx-font-size:13px; -fx-cursor:hand;");
        btnReset.setOnAction(e -> {
            cropX = 0; cropY = 0;
            cropW = imgW; cropH = imgH;
            drawCanvas(canvas);
        });

        Button btnCancel = new Button("Cancel");
        btnCancel.setStyle("-fx-background-color:#ccc; -fx-text-fill:#333;"
            + "-fx-background-radius:5; -fx-border-width:0;"
            + "-fx-padding:10 20; -fx-font-size:13px; -fx-cursor:hand;");
        btnCancel.setOnAction(e -> stage.close());

        HBox buttons = new HBox(12, btnApply, btnReset, btnCancel);
        buttons.setAlignment(Pos.CENTER);
        buttons.setPadding(new Insets(10));

        HBox infoRow = new HBox(20, infoLabel, sizeLabel);
        infoRow.setAlignment(Pos.CENTER_LEFT);
        infoRow.setPadding(new Insets(8, 12, 4, 12));

        // Canvas container with border
        StackPane canvasContainer = new StackPane(canvas);
        canvasContainer.setStyle("-fx-border-color:#dde1e5; -fx-border-width:1;"
            + "-fx-background-color:#f0f0f0;");
        canvasContainer.setPadding(new Insets(4));

        VBox root = new VBox(8, infoRow, canvasContainer, buttons);
        root.setStyle("-fx-background-color:white;");
        root.setPadding(new Insets(8));

        Scene scene = new Scene(root);
        stage.setScene(scene);
    }

    /**
     * Draws the image on the canvas with a darkened overlay outside the crop area.
     */
    private void drawCanvas(Canvas canvas) {
        GraphicsContext gc = canvas.getGraphicsContext2D();
        double w = canvas.getWidth();
        double h = canvas.getHeight();

        // Draw the full image
        gc.drawImage(originalImage, 0, 0, w, h);

        // Crop area in canvas coordinates
        double cx = cropX * scale;
        double cy = cropY * scale;
        double cw = cropW * scale;
        double ch = cropH * scale;

        // Draw dark overlay outside crop area
        gc.setFill(Color.rgb(0, 0, 0, 0.45));

        // Top
        gc.fillRect(0, 0, w, cy);
        // Bottom
        gc.fillRect(0, cy + ch, w, h - cy - ch);
        // Left
        gc.fillRect(0, cy, cx, ch);
        // Right
        gc.fillRect(cx + cw, cy, w - cx - cw, ch);

        // Draw crop border
        gc.setStroke(Color.web("#2980b9"));
        gc.setLineWidth(2);
        gc.strokeRect(cx, cy, cw, ch);

        // Draw corner handles
        double handleSize = 8;
        gc.setFill(Color.web("#2980b9"));
        // Top-left
        gc.fillRect(cx - handleSize / 2, cy - handleSize / 2, handleSize, handleSize);
        // Top-right
        gc.fillRect(cx + cw - handleSize / 2, cy - handleSize / 2, handleSize, handleSize);
        // Bottom-left
        gc.fillRect(cx - handleSize / 2, cy + ch - handleSize / 2, handleSize, handleSize);
        // Bottom-right
        gc.fillRect(cx + cw - handleSize / 2, cy + ch - handleSize / 2, handleSize, handleSize);

        // Crop dimensions label
        gc.setFill(Color.web("#2980b9"));
        gc.fillText(String.format("%.0f × %.0f", cropW, cropH),
            cx + 4, cy + ch + 16);
    }

    /**
     * Crops the image using the selected area and writes it to disk.
     */
    private void applyCrop() {
        try {
            int ix = Math.max(0, (int) cropX);
            int iy = Math.max(0, (int) cropY);
            int iw = Math.min((int) cropW, (int) originalImage.getWidth() - ix);
            int ih = Math.min((int) cropH, (int) originalImage.getHeight() - iy);

            if (iw <= 0 || ih <= 0) return;

            PixelReader reader = originalImage.getPixelReader();
            WritableImage croppedImage = new WritableImage(reader, ix, iy, iw, ih);

            // Convert WritableImage to BufferedImage and save
            BufferedImage buffered = new BufferedImage(iw, ih, BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < ih; y++) {
                for (int x = 0; x < iw; x++) {
                    javafx.scene.paint.Color fxColor = croppedImage.getPixelReader().getColor(x, y);
                    int r = (int) (fxColor.getRed() * 255);
                    int g = (int) (fxColor.getGreen() * 255);
                    int b = (int) (fxColor.getBlue() * 255);
                    int a = (int) (fxColor.getOpacity() * 255);
                    buffered.setRGB(x, y, (a << 24) | (r << 16) | (g << 8) | b);
                }
            }

            // Determine output file name
            String name = sourceFile.getName();
            String base = name.contains(".") ? name.substring(0, name.lastIndexOf('.')) : name;
            String ext  = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1) : "png";
            if (ext.equalsIgnoreCase("jpg") || ext.equalsIgnoreCase("jpeg")) {
                // Convert to PNG for quality (JPEG re-encoding degrades)
                ext = "png";
            }

            resultFile = new File(sourceFile.getParent(), base + "_cropped." + ext);
            ImageIO.write(buffered, ext, resultFile);
            cropped = true;

        } catch (Exception e) {
            e.printStackTrace();
            cropped = false;
        }
    }

    public void showAndWait() {
        stage.showAndWait();
    }

    public boolean isCropped() {
        return cropped;
    }

    public File getResultFile() {
        return resultFile;
    }
}
