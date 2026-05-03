package com.formbuilder.service;

import com.formbuilder.model.FormSection;
import com.formbuilder.model.ProjectData;
import com.formbuilder.model.QuestionType;
import com.formbuilder.model.UploadStatus;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.googleapis.json.GoogleJsonResponseException;
import com.google.api.client.http.FileContent;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.drive.Drive;
import com.google.api.services.drive.model.File;
import com.google.api.services.drive.model.Permission;
import com.google.api.services.forms.v1.Forms;
import com.google.api.services.forms.v1.model.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * GoogleFormsService — v8
 *
 * KEY FIX in v8: addQuestionRequests() now uses the ACTUAL choice text
 * (q.getChoiceA(), B, C, D) as the radio-button option labels instead of
 * the placeholder letters "A", "B", "C", "D".
 *
 * If the teacher typed "test1 / test2 / test3 / test4" in the Edit panel,
 * those exact strings appear in the Google Form as the answer options.
 *
 * The correct-answer value sent to Grading.CorrectAnswers is also the
 * CHOICE TEXT of the correct answer, not the letter, because that is what
 * the Google Forms API expects — it must match one of the option.getValue()
 * strings exactly.
 *
 * For image-only MCQ where the teacher set choices = "A","B","C","D"
 * (auto-filled in AnswerPanelController), the behaviour is unchanged.
 */
public class GoogleFormsService {

    private static final String APPLICATION_NAME = "Form Builder";

    private final Drive  driveService;
    private final Forms  formsService;

    public GoogleFormsService() throws Exception {
        driveService = new Drive.Builder(
                GoogleNetHttpTransport.newTrustedTransport(),
                GsonFactory.getDefaultInstance(),
                GoogleAuthService.getCredentials()
        ).setApplicationName(APPLICATION_NAME).build();

        formsService = new Forms.Builder(
                GoogleNetHttpTransport.newTrustedTransport(),
                GsonFactory.getDefaultInstance(),
                GoogleAuthService.getCredentials()
        ).setApplicationName(APPLICATION_NAME).build();
    }

    // ─────────────────────────────────────────────
    // PUBLISH
    // ─────────────────────────────────────────────

    public String publish(ProjectData projectData,
                          java.io.File projectDir,
                          ProgressCallback progress) throws Exception {

        List<com.formbuilder.model.Question> questions = projectData.getQuestions();
        int total = questions.size();

        // Step 1: Create form
        String title = projectData.getFormTitle();
        if (title == null || title.isBlank()) title = projectData.getProjectName();
        String formId = createForm(title);

        // Step 2: Set form title via batchUpdate (API sometimes ignores create title)
        formsService.forms()
                .batchUpdate(formId, new BatchUpdateFormRequest()
                        .setRequests(List.of(
                                new Request().setUpdateFormInfo(
                                        new UpdateFormInfoRequest()
                                                .setInfo(new Info()
                                                        .setTitle(title))
                                                .setUpdateMask("title")
                                ))))
                .execute();

        // Step 3: Enable Quiz mode BEFORE adding questions
        enableQuizMode(formId);

        // Step 4: Group questions by section
        Map<String, FormSection> sectionById = new LinkedHashMap<>();
        for (FormSection s : projectData.getSections()) sectionById.put(s.getId(), s);

        Map<String, List<com.formbuilder.model.Question>> grouped = new LinkedHashMap<>();
        for (FormSection s : projectData.getSections()) grouped.put(s.getId(), new ArrayList<>());
        grouped.put(null, new ArrayList<>());

        for (com.formbuilder.model.Question q : questions) {
            String sid = q.getSectionId();
            grouped.getOrDefault(sid != null && grouped.containsKey(sid) ? sid : null,
                                 grouped.get(null)).add(q);
        }

        // Step 5: Build and flush batchUpdate requests
        List<Request> pendingRequests = new ArrayList<>();
        List<String> batchQuestionLabels = new ArrayList<>();  // track Q labels per batch
        int formIndex = 0;
        int done      = 0;
        boolean hasSections = projectData.getSections() != null
                              && !projectData.getSections().isEmpty();

        for (Map.Entry<String, List<com.formbuilder.model.Question>> entry : grouped.entrySet()) {
            String sectionId      = entry.getKey();
            List<com.formbuilder.model.Question> sectionQuestions = entry.getValue();
            if (sectionQuestions.isEmpty()) continue;

            if (hasSections) {
                String sectionTitle       = sectionId == null ? "General" : "Questions";
                String sectionDescription = null;
                if (sectionId != null) {
                    FormSection sec = sectionById.get(sectionId);
                    if (sec != null) {
                        sectionTitle       = sec.getTitle() != null ? sec.getTitle() : "Section";
                        sectionDescription = sec.getDescription();
                    }
                }
                Item pbItem = new Item().setTitle(sectionTitle)
                                        .setPageBreakItem(new PageBreakItem());
                if (sectionDescription != null && !sectionDescription.isBlank())
                    pbItem.setDescription(sectionDescription);

                pendingRequests.add(new Request().setCreateItem(
                        new CreateItemRequest().setItem(pbItem)
                                .setLocation(new Location().setIndex(formIndex++))));
            }

            for (com.formbuilder.model.Question q : sectionQuestions) {
                String qLabel = "Q" + q.displayNumber();
                try {
                    if (q.getUploadStatus() == UploadStatus.UPLOADED
                            && q.getDriveImageUrl() != null) {
                        formIndex += addQuestionRequests(pendingRequests, formIndex,
                                                        q.getDriveImageUrl(), q);
                        batchQuestionLabels.add(qLabel);
                        done++;
                        if (progress != null) progress.update(done, total);
                    } else {
                        java.io.File imageFile = q.getImagePath() != null
                            ? new java.io.File(projectDir, q.getImagePath()) : null;
                        if (imageFile == null || !imageFile.exists()) {
                            q.setUploadStatus(UploadStatus.FAILED);
                            done++;
                            if (progress != null) progress.update(done, total);
                            continue;
                        }

                        String imageId = uploadImageWithRetry(imageFile.getAbsolutePath());
                        q.setDriveImageUrl(imageId);

                        formIndex += addQuestionRequests(pendingRequests, formIndex, imageId, q);
                        batchQuestionLabels.add(qLabel);
                        q.setUploadStatus(UploadStatus.UPLOADED);
                        done++;
                        if (progress != null) progress.update(done, total);
                    }

                    // Flush every 10 requests (5 questions × 2 items each)
                    // to keep batches small and error messages precise
                    if (pendingRequests.size() >= 10) {
                        flushBatch(formId, pendingRequests, batchQuestionLabels);
                    }
                } catch (Exception ex) {
                    throw new RuntimeException("Publish failed at " + qLabel + ": " + ex.getMessage(), ex);
                }
            }
        }

        if (!pendingRequests.isEmpty()) flushBatch(formId, pendingRequests, batchQuestionLabels);

        return "https://docs.google.com/forms/d/" + formId + "/edit";
    }

    // ─────────────────────────────────────────────
    // ENABLE QUIZ MODE
    // ─────────────────────────────────────────────

    private void enableQuizMode(String formId) throws Exception {
        formsService.forms()
                .batchUpdate(formId, new BatchUpdateFormRequest()
                        .setRequests(List.of(
                                new Request().setUpdateSettings(
                                        new UpdateSettingsRequest()
                                                .setSettings(new FormSettings()
                                                        .setQuizSettings(new QuizSettings()
                                                                .setIsQuiz(true)))
                                                .setUpdateMask("quizSettings.isQuiz")
                                ))))
                .execute();
    }

    private void flushBatch(String formId, List<Request> requests,
                            List<String> batchQuestionLabels) throws Exception {
        if (requests.isEmpty()) return;
        try {
            formsService.forms()
                    .batchUpdate(formId, new BatchUpdateFormRequest()
                            .setRequests(new ArrayList<>(requests)))
                    .execute();
        } catch (Exception ex) {
            String qList = String.join(", ", batchQuestionLabels);
            throw new RuntimeException(
                "Publish failed at batch [" + qList + "]: " + ex.getMessage(), ex);
        } finally {
            requests.clear();
            batchQuestionLabels.clear();
        }
    }

    /**
     * Appends a single request for one question with its image embedded.
     *
     * FIX v9: Embeds the image directly in the QuestionItem instead of
     * creating a separate ImageItem. This uses 1 form item per question
     * instead of 2, doubling the effective capacity (Google Forms limits
     * forms to ~300 items).
     *
     * Returns the number of form index slots consumed (1).
     */
    private int addQuestionRequests(List<Request> requests,
                                    int baseIndex,
                                    String imageId,
                                    com.formbuilder.model.Question q) {

        String imageUrl = "https://drive.google.com/uc?id=" + imageId;
        String qLabel   = "Q" + q.displayNumber();

        if (q.getType() == QuestionType.MCQ) {

            // Build the four option strings using actual typed text
            String optA = choiceText(q.getChoiceA(), "A");
            String optB = choiceText(q.getChoiceB(), "B");
            String optC = choiceText(q.getChoiceC(), "C");
            String optD = choiceText(q.getChoiceD(), "D");

            // FIX: Google Forms API rejects duplicate option values.
            String[] opts = deduplicateOptions(optA, optB, optC, optD);
            optA = opts[0]; optB = opts[1]; optC = opts[2]; optD = opts[3];

            List<Option> options = List.of(
                    new Option().setValue(optA),
                    new Option().setValue(optB),
                    new Option().setValue(optC),
                    new Option().setValue(optD)
            );

            Question apiQuestion = new Question()
                    .setRequired(true)
                    .setChoiceQuestion(new ChoiceQuestion()
                            .setType("RADIO")
                            .setOptions(options));

            // Attach grading if the teacher explicitly set a correct answer
            char correctLetter = q.getCorrectAnswer();
            boolean hasAnswer  = correctLetter >= 'A' && correctLetter <= 'D'
                    && q.getChoiceA() != null && !q.getChoiceA().isBlank();

            if (hasAnswer) {
                String correctOptionText = switch (correctLetter) {
                    case 'A' -> optA;
                    case 'B' -> optB;
                    case 'C' -> optC;
                    case 'D' -> optD;
                    default  -> optA;
                };

                int pointValue = Math.max(1, q.getPointValue());
                apiQuestion.setGrading(new Grading()
                        .setPointValue(pointValue)
                        .setCorrectAnswers(new CorrectAnswers()
                                .setAnswers(List.of(
                                        new CorrectAnswer().setValue(correctOptionText)
                                ))));
            }

            // Single item: question with embedded image
            QuestionItem questionItem = new QuestionItem()
                    .setQuestion(apiQuestion)
                    .setImage(new Image().setSourceUri(imageUrl));

            requests.add(new Request().setCreateItem(
                    new CreateItemRequest()
                            .setItem(new Item()
                                    .setTitle(qLabel)
                                    .setQuestionItem(questionItem))
                            .setLocation(new Location().setIndex(baseIndex))
            ));

        } else {
            // Written / PROBLEM — free-text answer with embedded image
            QuestionItem questionItem = new QuestionItem()
                    .setQuestion(new Question()
                            .setRequired(true)
                            .setTextQuestion(new TextQuestion()
                                    .setParagraph(true)))
                    .setImage(new Image().setSourceUri(imageUrl));

            requests.add(new Request().setCreateItem(
                    new CreateItemRequest()
                            .setItem(new Item()
                                    .setTitle(qLabel)
                                    .setQuestionItem(questionItem))
                            .setLocation(new Location().setIndex(baseIndex))
            ));
        }

        return 1; // consumed 1 index slot (was 2 before)
    }

    /**
     * Returns the choice text to use as the Forms option value.
     * If the stored text is null, blank, or equals just the fallback letter,
     * returns the fallback letter.  Otherwise returns the actual text.
     */
    private static String choiceText(String stored, String fallbackLetter) {
        if (stored == null || stored.isBlank()) return fallbackLetter;
        return stored.trim();
    }

    /**
     * Ensures all four option values are unique.
     * If duplicates are found, appends trailing spaces to later occurrences
     * to make them distinct. Google Forms displays them the same visually
     * but treats them as different values internally.
     */
    private static String[] deduplicateOptions(String... values) {
        String[] result = values.clone();
        for (int i = 1; i < result.length; i++) {
            // Check if result[i] collides with any earlier value
            boolean dup = true;
            while (dup) {
                dup = false;
                for (int j = 0; j < i; j++) {
                    if (result[i].equals(result[j])) {
                        result[i] = result[i] + " ";  // append a trailing space
                        dup = true;
                        break;
                    }
                }
            }
        }
        return result;
    }

    // ─────────────────────────────────────────────
    // UPLOAD IMAGE TO DRIVE
    // ─────────────────────────────────────────────

    public String uploadImage(String imagePath) throws Exception {
        File fileMetadata = new File().setName("question_image");
        java.io.File filePathObj  = new java.io.File(imagePath);
        FileContent mediaContent  = new FileContent("image/png", filePathObj);

        File uploaded = driveService.files()
                .create(fileMetadata, mediaContent)
                .setFields("id")
                .execute();

        Permission permission = new Permission()
                .setType("anyone").setRole("reader");
        driveService.permissions().create(uploaded.getId(), permission).execute();

        return uploaded.getId();
    }

    private String uploadImageWithRetry(String imagePath) throws Exception {
        int delayMs = 1000;
        Exception lastEx = null;
        for (int attempt = 0; attempt < 4; attempt++) {
            try {
                return uploadImage(imagePath);
            } catch (GoogleJsonResponseException e) {
                if (e.getStatusCode() == 429 || e.getStatusCode() == 500) {
                    lastEx = e;
                    Thread.sleep(delayMs);
                    delayMs *= 2;
                } else {
                    throw e;
                }
            }
        }
        throw new RuntimeException("Upload failed after retries: " + imagePath, lastEx);
    }

    // ─────────────────────────────────────────────
    // CREATE FORM
    // ─────────────────────────────────────────────

    public String createForm(String title) throws Exception {
        Form form    = new Form().setInfo(new Info().setTitle(title));
        Form created = formsService.forms().create(form).execute();
        return created.getFormId();
    }

    // ─────────────────────────────────────────────
    // STANDALONE METHODS (unit tests / direct use)
    // ─────────────────────────────────────────────

    public void addMcqQuestion(String formId, String imageId) throws Exception {
        List<Request> requests = new ArrayList<>();
        com.formbuilder.model.Question dummy = new com.formbuilder.model.Question();
        dummy.setType(QuestionType.MCQ);
        addQuestionRequests(requests, 0, imageId, dummy);
        formsService.forms()
                .batchUpdate(formId, new BatchUpdateFormRequest().setRequests(requests))
                .execute();
    }

    public void addEssayQuestion(String formId, String imageId) throws Exception {
        List<Request> requests = new ArrayList<>();
        com.formbuilder.model.Question dummy = new com.formbuilder.model.Question();
        dummy.setType(com.formbuilder.model.QuestionType.PROBLEM);
        addQuestionRequests(requests, 0, imageId, dummy);
        formsService.forms()
                .batchUpdate(formId, new BatchUpdateFormRequest().setRequests(requests))
                .execute();
    }

    @FunctionalInterface
    public interface ProgressCallback {
        void update(int done, int total);
    }
}