package com.formbuilder.model;

/**
 * DTO populated from output.json produced by questions_divider.py (v8).
 *
 * v8 additions:
 *   questionNumber – the REAL question number as printed in the PDF
 *                    (e.g. 7, 8, 9, 10, 33, 39 …).
 *                    0 means "could not extract from PDF text".
 *
 * Existing fields (unchanged):
 *   parentNumber   – 0 for standalone; real parent number for sub-questions
 *                    (e.g. 10 for Q10-i, Q10-ii, Q10-iii)
 *   subLabel       – "" for standalone; "i", "ii", "iii"… for sub-questions
 */
public class QuestionData {
    public String id;
    public String type;
    public String image;
    public int    page;

    /**
     * v8: The REAL question number from the PDF text (7, 8, 9, 10, 33 …).
     * 0 = could not extract.
     */
    public int    questionNumber = 0;

    /**
     * 0  = standalone question.
     * >0 = sub-question of this parent's REAL number.
     */
    public int    parentNumber = 0;

    /** "" = standalone.  "i", "ii", "iii"… = sub-question label. */
    public String subLabel = "";

    @Override
    public String toString() {
        String sub = (parentNumber > 0)
            ? ", sub=" + parentNumber + "-" + subLabel
            : "";
        String qn  = questionNumber > 0 ? ", realQ=" + questionNumber : "";
        return "QuestionData{"
                + "id='" + id + '\''
                + ", type='" + type + '\''
                + ", image='" + image + '\''
                + ", page=" + page
                + qn
                + sub
                + '}';
    }
}