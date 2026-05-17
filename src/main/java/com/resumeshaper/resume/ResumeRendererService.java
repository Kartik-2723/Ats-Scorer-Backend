package com.resumeshaper.resume;

import lombok.extern.slf4j.Slf4j;
import org.apache.poi.xwpf.usermodel.*;
import org.apache.xmlbeans.XmlCursor;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.*;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;

/**
 * Converts the shaped resume JSON (from LLM) into a clean, ATS-safe DOCX.
 * Single page, no tables/graphics/headers, clean fonts.
 */
@Slf4j
@Service
public class ResumeRendererService {

    private static final String FONT = "Calibri";
    private static final int    BODY_SIZE = 20;     // half-points → 10pt
    private static final int    H1_SIZE   = 28;     // 14pt
    private static final int    H2_SIZE   = 22;     // 11pt
    private static final String DIVIDER_COLOR = "2E74B5";

    @SuppressWarnings("unchecked")
    public byte[] render(Map<String, Object> shapedResume) throws IOException {
        try (XWPFDocument doc = new XWPFDocument();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {

            // ── Page margins (narrow to fit one page) ────────
            CTSectPr sect = doc.getDocument().getBody().addNewSectPr();
            CTPageMar mar = sect.addNewPgMar();
            mar.setTop(BigInteger.valueOf(720));
            mar.setBottom(BigInteger.valueOf(720));
            mar.setLeft(BigInteger.valueOf(900));
            mar.setRight(BigInteger.valueOf(900));

            // ── Header: Name + Contact ────────────────────────
            String name = str(shapedResume, "name");
            addHeading1(doc, name.toUpperCase());

            Object contactObj = shapedResume.get("contact");
            if (contactObj instanceof Map<?,?> contact) {
                String contactLine = buildContactLine(contact);
                addBodyPara(doc, contactLine, true, false);
            }

            addDivider(doc);

            // ── Summary ───────────────────────────────────────
            String summary = str(shapedResume, "summary");
            if (!summary.isBlank()) {
                addSection(doc, "SUMMARY");
                addBodyPara(doc, summary, false, false);
                addSpacing(doc);
            }

            // ── Skills ────────────────────────────────────────
            List<String> skills = listOf(shapedResume, "skills");
            if (!skills.isEmpty()) {
                addSection(doc, "SKILLS");
                addBodyPara(doc, String.join(" • ", skills), false, false);
                addSpacing(doc);
            }

            // ── Experience ────────────────────────────────────
            List<Map<String, Object>> experience = listOfMaps(shapedResume, "experience");
            if (!experience.isEmpty()) {
                addSection(doc, "EXPERIENCE");
                for (Map<String, Object> exp : experience) {
                    addJobHeader(doc,
                            str(exp, "title") + " — " + str(exp, "company"),
                            str(exp, "startDate") + " – " + str(exp, "endDate"));
                    for (String bullet : listOf(exp, "bullets")) {
                        addBullet(doc, bullet);
                    }
                    addSpacing(doc);
                }
            }

            // ── Education ─────────────────────────────────────
            List<Map<String, Object>> education = listOfMaps(shapedResume, "education");
            if (!education.isEmpty()) {
                addSection(doc, "EDUCATION");
                for (Map<String, Object> edu : education) {
                    addJobHeader(doc,
                            str(edu, "degree") + " — " + str(edu, "institution"),
                            str(edu, "year"));
                }
                addSpacing(doc);
            }

            // ── Certifications ────────────────────────────────
            List<String> certs = listOf(shapedResume, "certifications");
            if (!certs.isEmpty()) {
                addSection(doc, "CERTIFICATIONS");
                for (String cert : certs) addBullet(doc, cert);
                addSpacing(doc);
            }

            // ── Projects ─────────────────────────────────────
            List<Map<String, Object>> projects = listOfMaps(shapedResume, "projects");
            if (!projects.isEmpty()) {
                addSection(doc, "PROJECTS");
                for (Map<String, Object> p : projects) {
                    addJobHeader(doc, str(p, "name"), "");
                    addBodyPara(doc, str(p, "description"), false, true);
                    List<String> tech = listOf(p, "tech");
                    if (!tech.isEmpty()) {
                        addBodyPara(doc, "Tech: " + String.join(", ", tech), false, true);
                    }
                    addSpacing(doc);
                }
            }

            doc.write(out);
            return out.toByteArray();
        }
    }

    // ── Private paragraph helpers ─────────────────────────────

    private void addHeading1(XWPFDocument doc, String text) {
        XWPFParagraph p = doc.createParagraph();
        p.setAlignment(ParagraphAlignment.CENTER);
        XWPFRun r = p.createRun();
        r.setText(text);
        r.setBold(true);
        r.setFontFamily(FONT);
        r.setFontSize(H1_SIZE / 2);
        r.setColor("1F3864");
    }

    private void addSection(XWPFDocument doc, String title) {
        XWPFParagraph p = doc.createParagraph();
        p.setSpacingBefore(80);
        CTPPr ppr = p.getCTP().addNewPPr();
        CTBorder border = ppr.addNewPBdr().addNewBottom();
        border.setVal(STBorder.SINGLE);
        border.setSz(BigInteger.valueOf(4));
        border.setColor(DIVIDER_COLOR);
        XWPFRun r = p.createRun();
        r.setText(title);
        r.setBold(true);
        r.setFontFamily(FONT);
        r.setFontSize(H2_SIZE / 2);
        r.setColor(DIVIDER_COLOR);
    }

    private void addJobHeader(XWPFDocument doc, String title, String dateRange) {
        XWPFParagraph p = doc.createParagraph();
        XWPFRun r1 = p.createRun();
        r1.setText(title);
        r1.setBold(true);
        r1.setFontFamily(FONT);
        r1.setFontSize(BODY_SIZE / 2);

        if (!dateRange.isBlank()) {
            XWPFRun r2 = p.createRun();
            r2.setText("  |  " + dateRange);
            r2.setFontFamily(FONT);
            r2.setFontSize(BODY_SIZE / 2);
            r2.setColor("666666");
        }
    }

    private void addBullet(XWPFDocument doc, String text) {
        XWPFParagraph p = doc.createParagraph();
        p.setIndentationLeft(360);
        p.setIndentationHanging(180);
        XWPFRun r = p.createRun();
        r.setText("• " + text);
        r.setFontFamily(FONT);
        r.setFontSize(BODY_SIZE / 2);
    }

    private void addBodyPara(XWPFDocument doc, String text, boolean center, boolean italic) {
        XWPFParagraph p = doc.createParagraph();
        if (center) p.setAlignment(ParagraphAlignment.CENTER);
        XWPFRun r = p.createRun();
        r.setText(text);
        r.setFontFamily(FONT);
        r.setFontSize(BODY_SIZE / 2);
        if (italic) r.setItalic(true);
    }

    private void addDivider(XWPFDocument doc) {
        XWPFParagraph p = doc.createParagraph();
        CTBorder border = p.getCTP().addNewPPr().addNewPBdr().addNewBottom();
        border.setVal(STBorder.SINGLE);
        border.setSz(BigInteger.valueOf(6));
        border.setColor(DIVIDER_COLOR);
    }

    private void addSpacing(XWPFDocument doc) {
        XWPFParagraph p = doc.createParagraph();
        p.setSpacingAfter(60);
    }

    // ── Data helpers ─────────────────────────────────────────

    private String str(Map<?, ?> map, String key) {
        Object v = map.get(key);
        return v != null ? v.toString() : "";
    }

    @SuppressWarnings("unchecked")
    private List<String> listOf(Map<?, ?> map, String key) {
        Object v = map.get(key);
        if (v instanceof List<?> list) {
            return (List<String>) list;
        }
        return List.of();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> listOfMaps(Map<?, ?> map, String key) {
        Object v = map.get(key);
        if (v instanceof List<?> list) {
            return (List<Map<String, Object>>) list;
        }
        return List.of();
    }

    private String buildContactLine(Map<?, ?> contact) {
        StringBuilder sb = new StringBuilder();
        appendIfPresent(sb, contact, "email", " | ");
        appendIfPresent(sb, contact, "phone", " | ");
        appendIfPresent(sb, contact, "linkedin", " | ");
        appendIfPresent(sb, contact, "location", "");
        return sb.toString();
    }

    private void appendIfPresent(StringBuilder sb, Map<?, ?> m, String key, String sep) {
        Object v = m.get(key);
        if (v != null && !v.toString().isBlank()) {
            if (!sb.isEmpty()) sb.append(sep);
            sb.append(v);
        }
    }
}
