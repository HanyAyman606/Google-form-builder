package com.formbuilder.service;

import com.formbuilder.model.AnswerKey;
import com.formbuilder.model.Question;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Exports the answer key to PDF or CSV.
 *
 * FORMAT (v2):
 *   PDF and CSV both output:
 *       1   A   1 pt
 *       2   B   1 pt
 *       3   D   2 pts
 *       ...
 *   This is the "1 A, 2 B, 3 D" format the teacher requested.
 *
 * PDF fix: closes the PDPageContentStream before adding a new page
 * (was causing IllegalStateException on exams with 40+ questions).
 */
public class AnswerKeyExporter {

    private static final float MARGIN      = 50f;
    private static final float LINE_HEIGHT = 22f;
    private static final float COL_Q       = 50f;   // Q# column x
    private static final float COL_ANS     = 120f;  // Answer column x
    private static final float COL_PTS     = 200f;  // Points column x

    // ── PDF ────────────────────────────────────────────────────────────────

    public void exportPdf(AnswerKey answerKey, List<Question> questions, File destination)
            throws IOException {

        try (PDDocument doc = new PDDocument()) {
            PDType1Font fontBold    = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
            PDType1Font fontRegular = new PDType1Font(Standard14Fonts.FontName.HELVETICA);

            PDPage page    = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            float pageH = page.getMediaBox().getHeight();
            float y     = pageH - MARGIN;

            PDPageContentStream cs = new PDPageContentStream(doc, page);

            // ── Title ──────────────────────────────────────────────────────
            y = writeText(cs, fontBold, 18, MARGIN, y, "Answer Key");
            y = writeText(cs, fontRegular, 13, MARGIN, y, answerKey.getExamTitle());
            y -= 8;

            // ── Column header ──────────────────────────────────────────────
            y = drawColumnHeader(cs, fontBold, y, page);

            // ── One row per question: "  1      A      1 pt" ──────────────
            for (Question q : questions) {
                if (y < MARGIN + LINE_HEIGHT * 2) {
                    cs.close();
                    page = new PDPage(PDRectangle.A4);
                    doc.addPage(page);
                    y  = page.getMediaBox().getHeight() - MARGIN;
                    cs = new PDPageContentStream(doc, page);
                    y  = drawColumnHeader(cs, fontBold, y, page);
                }

                char ans = q.getCorrectAnswer();
                String ansStr  = (ans >= 'A' && ans <= 'D') ? String.valueOf(ans) : "—";
                String ptsStr  = q.getPointValue() + (q.getPointValue() == 1 ? " pt" : " pts");

                // Q# in bold
                cs.beginText(); cs.setFont(fontBold, 12);
                cs.newLineAtOffset(COL_Q, y);
                cs.showText(q.displayNumber());
                cs.endText();

                // Answer in large bold blue-ish (can't do color in Type1 without effort, use bold)
                cs.beginText(); cs.setFont(fontBold, 13);
                cs.newLineAtOffset(COL_ANS, y);
                cs.showText(ansStr);
                cs.endText();

                // Points
                cs.beginText(); cs.setFont(fontRegular, 11);
                cs.newLineAtOffset(COL_PTS, y);
                cs.showText(ptsStr);
                cs.endText();

                y -= LINE_HEIGHT;
            }

            // ── Total ──────────────────────────────────────────────────────
            y -= 6;
            if (y < MARGIN + LINE_HEIGHT) {
                cs.close();
                page = new PDPage(PDRectangle.A4);
                doc.addPage(page);
                y  = page.getMediaBox().getHeight() - MARGIN;
                cs = new PDPageContentStream(doc, page);
            }
            cs.moveTo(MARGIN, y + 4);
            cs.lineTo(page.getMediaBox().getWidth() - MARGIN, y + 4);
            cs.stroke();
            y -= 4;
            y = writeText(cs, fontBold, 12, MARGIN, y,
                "Total: " + answerKey.totalMarks() + " marks  |  " + questions.size() + " questions");

            cs.close();
            doc.save(destination);
        }
    }

    // ── CSV ────────────────────────────────────────────────────────────────

    /**
     * Exports as:
     *   Question,Answer,Points
     *   1,A,1
     *   2,B,1
     *   3,D,2
     *   ...
     *   Total,,15
     */
    public void exportCsv(AnswerKey answerKey, List<Question> questions, File destination)
            throws IOException {
        try (Writer w = new OutputStreamWriter(
                new FileOutputStream(destination), StandardCharsets.UTF_8)) {
            w.write("Question,Answer,Points\n");
            for (Question q : questions) {
                char ans = q.getCorrectAnswer();
                String ansStr = (ans >= 'A' && ans <= 'D') ? String.valueOf(ans) : "";
                w.write(q.displayNumber() + "," + ansStr + "," + q.getPointValue() + "\n");
            }
            w.write("Total,," + answerKey.totalMarks() + "\n");
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private float drawColumnHeader(PDPageContentStream cs, PDType1Font fontBold,
                                   float y, PDPage page) throws IOException {
        cs.beginText(); cs.setFont(fontBold, 11);
        cs.newLineAtOffset(COL_Q,   y); cs.showText("Q#");   cs.endText();
        cs.beginText(); cs.setFont(fontBold, 11);
        cs.newLineAtOffset(COL_ANS, y); cs.showText("Answer"); cs.endText();
        cs.beginText(); cs.setFont(fontBold, 11);
        cs.newLineAtOffset(COL_PTS, y); cs.showText("Points"); cs.endText();
        y -= 4;
        cs.moveTo(MARGIN, y);
        cs.lineTo(page.getMediaBox().getWidth() - MARGIN, y);
        cs.stroke();
        return y - 6;
    }

    private float writeText(PDPageContentStream cs, PDType1Font font, float size,
                             float x, float y, String text) throws IOException {
        cs.beginText();
        cs.setFont(font, size);
        cs.newLineAtOffset(x, y);
        cs.showText(text != null ? text : "");
        cs.endText();
        return y - LINE_HEIGHT;
    }
}