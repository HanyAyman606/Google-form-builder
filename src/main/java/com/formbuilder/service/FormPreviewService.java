package com.formbuilder.service;

import com.formbuilder.model.FormSection;
import com.formbuilder.model.ProjectData;
import com.formbuilder.model.Question;

import java.awt.Desktop;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Generates a rich local HTML preview (title + sections + choices + images),
 * then opens it in the default browser.
 *
 * FIXES (v3):
 *  - Form title shown at top
 *  - Section-break cards with title + description
 *  - Each question: image + A/B/C/D choice rows with text
 *  - Questions with no typed choices (image-based MCQ) show "Choice A/B/C/D"
 *    labels so the form looks complete
 *  - Correct answers still hidden (student view)
 *  - Summary bar: total questions + total marks + sections
 */
public class FormPreviewService {

    public File generateAndOpen(ProjectData project, File projectDir) throws Exception {
        File htmlFile = new File(projectDir,
            project.getProjectName().replaceAll("[^a-zA-Z0-9_\\-]", "_") + "_preview.html");

        try (Writer w = new OutputStreamWriter(new FileOutputStream(htmlFile), StandardCharsets.UTF_8)) {
            w.write(buildHtml(project, projectDir));
        }

        if (Desktop.isDesktopSupported()) Desktop.getDesktop().browse(htmlFile.toURI());
        return htmlFile;
    }

    private String buildHtml(ProjectData project, File projectDir) {
        StringBuilder sb = new StringBuilder();

        Map<String, FormSection> sectionMap = new LinkedHashMap<>();
        project.getSections().forEach(s -> sectionMap.put(s.getId(), s));

        sb.append("""
            <!DOCTYPE html>
            <html lang="en">
            <head>
              <meta charset="UTF-8"/>
              <title>Form Preview</title>
              <style>
                *{box-sizing:border-box;margin:0;padding:0}
                body{font-family:Roboto,Arial,sans-serif;background:#f0f0f0;color:#202124;padding:24px 16px 48px}
                .wrap{max-width:760px;margin:0 auto}
                .banner{background:#1a73e8;color:#fff;padding:10px 18px;border-radius:8px 8px 0 0;
                        font-size:13px;margin-bottom:0;display:flex;align-items:center;gap:8px}
                .form-header{background:#fff;border-top:8px solid #673ab7;border-radius:0 0 8px 8px;
                             padding:28px 24px 20px;margin-bottom:14px;box-shadow:0 1px 3px rgba(0,0,0,.15)}
                .form-header h1{font-size:26px;font-weight:400;margin-bottom:6px}
                .form-header p{font-size:13px;color:#5f6368}
                .summary{background:#fff;border-radius:8px;padding:12px 18px;margin-bottom:14px;
                         box-shadow:0 1px 3px rgba(0,0,0,.1);display:flex;gap:20px;flex-wrap:wrap;
                         font-size:13px;color:#555}
                .summary strong{color:#2c3e50}
                .section-break{background:#fff;border-top:8px solid #673ab7;border-radius:8px;
                               padding:18px 22px;margin:20px 0 8px;box-shadow:0 1px 3px rgba(0,0,0,.15)}
                .section-break h2{font-size:19px;font-weight:400;color:#202124;margin-bottom:4px}
                .section-break p{font-size:13px;color:#5f6368}
                .card{background:#fff;border-radius:8px;padding:20px 22px;margin-bottom:10px;
                      box-shadow:0 1px 3px rgba(0,0,0,.15)}
                .q-num{font-size:11px;color:#5f6368;margin-bottom:10px;font-weight:500;
                       text-transform:uppercase;letter-spacing:.5px}
                .q-img{max-width:100%;border-radius:4px;margin-bottom:14px;border:1px solid #e0e0e0}
                .choices{display:flex;flex-direction:column;gap:6px}
                .choice{display:flex;align-items:center;gap:12px;padding:9px 14px;
                        border:1px solid #e0e0e0;border-radius:6px;cursor:pointer;font-size:14px}
                .choice:hover{background:#f8f4ff;border-color:#673ab7}
                .choice input{accent-color:#673ab7;width:17px;height:17px;flex-shrink:0}
                .letter{font-weight:700;color:#673ab7;min-width:22px;text-align:center}
                .no-choices{font-size:13px;color:#e67e22;font-style:italic;padding:4px 0}
                .footer{text-align:center;color:#888;font-size:12px;margin-top:32px;
                        padding-top:14px;border-top:1px solid #e0e0e0}
              </style>
            </head>
            <body><div class="wrap">
            """);

        sb.append("<div class=\"banner\">⚠️ <strong>Preview only</strong> — not the live form. "
            + "Correct answers are hidden.</div>\n");

        String title = (project.getFormTitle() != null && !project.getFormTitle().isBlank())
            ? project.getFormTitle() : project.getProjectName();
        int totalQ = project.getQuestions().size();
        int totalM = project.getQuestions().stream().mapToInt(Question::getPointValue).sum();

        sb.append("<div class=\"form-header\"><h1>").append(esc(title)).append("</h1>")
          .append("<p>").append(totalQ).append(" questions");
        if (totalM > 0) sb.append(" &nbsp;·&nbsp; ").append(totalM).append(" marks");
        if (!project.getSections().isEmpty())
            sb.append(" &nbsp;·&nbsp; ").append(project.getSections().size()).append(" section(s)");
        sb.append("</p></div>\n");

        long withAnswers = project.getQuestions().stream()
            .filter(q -> q.getChoiceA() != null && !q.getChoiceA().isBlank()).count();
        sb.append("<div class=\"summary\">")
          .append("<span><strong>").append(totalQ).append("</strong> questions</span>")
          .append("<span><strong>").append(withAnswers).append("</strong> with choices</span>")
          .append("<span><strong>").append(totalM).append("</strong> total marks</span>");
        if (!project.getSections().isEmpty())
            sb.append("<span><strong>").append(project.getSections().size()).append("</strong> sections</span>");
        sb.append("</div>\n");

        String lastSecId = null;
        for (Question q : project.getQuestions()) {
            String curSecId = q.getSectionId();
            if (!Objects.equals(curSecId, lastSecId)) {
                if (curSecId != null) {
                    FormSection sec = sectionMap.get(curSecId);
                    if (sec != null) {
                        sb.append("<div class=\"section-break\"><h2>").append(esc(sec.getTitle())).append("</h2>");
                        if (sec.getDescription() != null && !sec.getDescription().isBlank())
                            sb.append("<p>").append(esc(sec.getDescription())).append("</p>");
                        sb.append("</div>\n");
                    }
                }
                lastSecId = curSecId;
            }

            sb.append("<div class=\"card\">");
            sb.append("<div class=\"q-num\">Question ").append(q.displayNumber());
            if (q.getPointValue() > 1) sb.append(" &nbsp;(").append(q.getPointValue()).append(" pts)");
            sb.append("</div>");

            if (q.getImagePath() != null) {
                File img = new File(projectDir, q.getImagePath());
                if (img.exists())
                    sb.append("<img class=\"q-img\" src=\"").append(img.toURI()).append("\" alt=\"Q").append(q.displayNumber()).append("\"/>");
            }

            // Choices
            boolean hasText = q.getChoiceA() != null && !q.getChoiceA().isBlank()
                           && !q.getChoiceA().equals("A"); // "A" is auto-set, show generic label

            sb.append("<div class=\"choices\">");
            for (char l : new char[]{'A','B','C','D'}) {
                String text = switch (l) {
                    case 'A' -> q.getChoiceA(); case 'B' -> q.getChoiceB();
                    case 'C' -> q.getChoiceC(); case 'D' -> q.getChoiceD(); default -> "";
                };
                String display = (text != null && !text.isBlank() && !text.equals(String.valueOf(l)))
                    ? esc(text) : ("Choice " + l);
                sb.append("<label class=\"choice\"><input type=\"radio\" name=\"q").append(q.displayNumber())
                  .append("\"/><span class=\"letter\">").append(l).append(".</span><span>")
                  .append(display).append("</span></label>\n");
            }
            sb.append("</div></div>\n");
        }

        sb.append("<div class=\"footer\">Preview for <strong>").append(esc(project.getProjectName()))
          .append("</strong></div></div></body></html>");
        return sb.toString();
    }

    private String esc(String s) {
        if (s == null) return "";
        return s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;");
    }
}