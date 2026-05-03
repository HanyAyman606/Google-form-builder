package com.formbuilder.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.formbuilder.model.Question;
import com.formbuilder.model.QuestionBankEntry;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Manages the question bank stored at ~/.formsbuilder/bank.json.
 * The bank is shared across all projects — no database, no server.
 *
 * All methods are synchronous. Call from a background Task for large banks.
 */
public class QuestionBankService {

    private static final File BANK_FILE =
            new File(System.getProperty("user.home"), ".formsbuilder/bank.json");

    private static final ObjectMapper mapper = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    // ── Save to bank ───────────────────────────────────────────────────────

    /**
     * Adds a question to the bank and persists immediately.
     *
     * @param question    the Question to save (a copy is stored)
     * @param sourceExam  name of the project it came from
     * @param projectDir  used to copy the image file into the bank's image dir
     */
    public void addToBank(Question question, String sourceExam, File projectDir) throws IOException {
        List<QuestionBankEntry> entries = loadAll();

        // Copy image into bank images folder so it's independent of the project
        Question bankQuestion = question.copy();
        if (question.getImagePath() != null) {
            File srcImage = new File(projectDir, question.getImagePath());
            if (srcImage.exists()) {
                File bankImagesDir = new File(BANK_FILE.getParentFile(), "images");
                bankImagesDir.mkdirs();
                File destImage = new File(bankImagesDir, srcImage.getName());
                org.apache.commons.io.FileUtils.copyFile(srcImage, destImage);
                bankQuestion.setImagePath("images/" + srcImage.getName());
            }
        }
        bankQuestion.setUploadStatus(com.formbuilder.model.UploadStatus.PENDING);
        bankQuestion.setDriveImageUrl(null);

        entries.add(new QuestionBankEntry(bankQuestion, sourceExam));
        persist(entries);
    }

    // ── Load & search ──────────────────────────────────────────────────────

    /** Returns all entries in the bank. */
    public List<QuestionBankEntry> loadAll() {
        if (!BANK_FILE.exists()) return new ArrayList<>();
        try {
            QuestionBankEntry[] arr = mapper.readValue(BANK_FILE, QuestionBankEntry[].class);
            return new ArrayList<>(Arrays.asList(arr));
        } catch (IOException e) {
            System.err.println("Warning: could not read bank file — " + e.getMessage());
            return new ArrayList<>();
        }
    }

    /**
     * Searches the bank by tag or source exam name (case-insensitive).
     * An empty query returns all entries.
     */
    public List<QuestionBankEntry> search(String query) {
        List<QuestionBankEntry> all = loadAll();
        if (query == null || query.isBlank()) return all;

        String q = query.toLowerCase().trim();
        return all.stream()
            .filter(e -> {
                // Match on source exam name
                if (e.getSourceExam() != null &&
                    e.getSourceExam().toLowerCase().contains(q)) return true;
                // Match on any tag
                if (e.getQuestion().getBankTags() != null) {
                    return e.getQuestion().getBankTags().stream()
                        .anyMatch(tag -> tag.toLowerCase().contains(q));
                }
                return false;
            })
            .collect(Collectors.toList());
    }

    // ── Import into project ────────────────────────────────────────────────

    /**
     * Creates a copy of a bank question ready for use in a new project.
     * Copies the image into the project's images/ folder.
     * Increments the usageCount in the bank.
     *
     * @param entry      the bank entry to import
     * @param projectDir the target project's root directory
     * @param newNumber  the question number to assign in the new project
     * @return a new Question with paths set for the target project
     */
    public Question importIntoProject(QuestionBankEntry entry,
                                       File projectDir,
                                       int newNumber) throws IOException {
        Question copy = entry.createCopy(); // increments usageCount
        copy.setNumber(newNumber);

        // Copy image from bank to project images dir
        if (copy.getImagePath() != null) {
            File bankDir = BANK_FILE.getParentFile();
            File srcImage = new File(bankDir, copy.getImagePath());
            if (srcImage.exists()) {
                File projectImagesDir = new File(projectDir, "images");
                projectImagesDir.mkdirs();
                String newName = String.format("q%03d_%s", newNumber, srcImage.getName());
                File destImage = new File(projectImagesDir, newName);
                org.apache.commons.io.FileUtils.copyFile(srcImage, destImage);
                copy.setImagePath("images/" + newName);
            }
        }

        // Persist updated usageCount
        persist(loadAll()); // entry.createCopy() already mutated the entry's usageCount
        return copy;
    }

    // ── Delete ────────────────────────────────────────────────────────────

    /** Removes a bank entry by the question's ID. */
    public void remove(String questionId) throws IOException {
        List<QuestionBankEntry> entries = loadAll();
        entries.removeIf(e -> questionId.equals(e.getQuestion().getId()));
        persist(entries);
    }

    // ── Private ───────────────────────────────────────────────────────────

    private void persist(List<QuestionBankEntry> entries) throws IOException {
        BANK_FILE.getParentFile().mkdirs();
        mapper.writeValue(BANK_FILE, entries);
    }
}