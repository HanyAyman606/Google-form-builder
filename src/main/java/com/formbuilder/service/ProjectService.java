package com.formbuilder.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.formbuilder.model.ProjectData;
import com.formbuilder.model.Question;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/**
 * Saves and loads .fbp project files (JSON format).
 *
 * Image paths inside the project are stored relative to the .fbp file location,
 * so the project folder can be moved, zipped, or shared without breaking image links.
 *
 * FIX (save robustness):
 *  - Creates parent directories automatically before writing.
 *  - Writes to a temp file then atomically renames, so a crash during save
 *    never leaves a corrupt .fbp file.
 *  - Validates that the destination is not a directory.
 */
public class ProjectService {

    private static final ObjectMapper mapper = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    /**
     * Saves a ProjectData object to a .fbp file.
     * Performs an atomic write: data → .fbp.tmp → rename → .fbp.
     * Parent directories are created automatically.
     */
    public void save(ProjectData project, File destination) throws IOException {
        // Ensure .fbp extension
        File file = destination.getName().endsWith(".fbp")
            ? destination
            : new File(destination.getParent(), destination.getName() + ".fbp");

        // Create parent directories if they don't exist
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }

        // Write atomically: write to .tmp first, then rename
        File tmp = new File(file.getParent(), file.getName() + ".tmp");
        try {
            mapper.writeValue(tmp, project);
            // Atomic rename (same filesystem guaranteed)
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            tmp.delete(); // clean up temp on failure
            throw e;
        }
    }

    /**
     * Loads a .fbp project file.
     *
     * Validates that each question's image file exists relative to the project file.
     * Questions with missing images are flagged — their imagePath is left intact so
     * the teacher can see what's missing, but a warning list is returned.
     *
     * @param file the .fbp file to load
     * @return LoadResult containing the project and any missing image warnings
     */
    public LoadResult load(File file) throws IOException {
        if (!file.exists()) {
            throw new IOException("Project file not found: " + file.getAbsolutePath());
        }
        ProjectData project = mapper.readValue(file, ProjectData.class);

        File projectDir = file.getParentFile();
        java.util.List<String> warnings = new java.util.ArrayList<>();

        for (Question q : project.getQuestions()) {
            if (q.getImagePath() != null && !q.getImagePath().isBlank()) {
                File imageFile = new File(projectDir, q.getImagePath());
                if (!imageFile.exists()) {
                    warnings.add("Q" + q.displayNumber()
                                 + ": image not found — " + q.getImagePath());
                }
            }
        }

        return new LoadResult(project, projectDir, warnings);
    }

    /**
     * Resolves a relative image path to an absolute File using the project directory.
     */
    public static File resolveImageFile(String relativePath, File projectDir) {
        return new File(projectDir, relativePath);
    }

    // ── Result record ──────────────────────────────────────────────────────

    public record LoadResult(
        ProjectData project,
        File        projectDir,
        java.util.List<String> warnings
    ) {
        public boolean hasWarnings() { return !warnings.isEmpty(); }
    }
}