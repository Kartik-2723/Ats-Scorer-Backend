package com.resumeshaper.resume;

import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.usermodel.*;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Extracts raw text from PDF / DOCX and returns a structured map
 * matching the sections schema used by the LLM prompts.
 */
@Slf4j
@Service
public class ResumeParserService {

    private static final Pattern SECTION_HEADER = Pattern.compile(
            "^(SUMMARY|OBJECTIVE|EXPERIENCE|WORK EXPERIENCE|EDUCATION|SKILLS|" +
                    "CERTIFICATIONS|PROJECTS|ACHIEVEMENTS|AWARDS|LANGUAGES|INTERESTS|" +
                    "PUBLICATIONS|VOLUNTEER|REFERENCES)\\s*:?\\s*$",
            Pattern.CASE_INSENSITIVE
    );

    public Map<String, Object> parse(MultipartFile file) throws IOException {
        String filename = Objects.requireNonNull(file.getOriginalFilename()).toLowerCase();
        String rawText;

        if (filename.endsWith(".pdf")) {
            rawText = extractPdf(file.getInputStream());
        } else if (filename.endsWith(".docx")) {
            rawText = extractDocx(file.getInputStream());
        } else {
            throw new IllegalArgumentException("Unsupported file type: " + filename);
        }

        return structureText(rawText);
    }

    // ── PDF extraction ───────────────────────────────────────

    private String extractPdf(InputStream is) throws IOException {
        // PDFBox 3.x: Loader.loadPDF() replaces PDDocument.load()
        // Must read into bytes first — Loader doesn't accept raw InputStream
        try (PDDocument doc = Loader.loadPDF(is.readAllBytes())) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            return stripper.getText(doc);
        }
    }

    // ── DOCX extraction ──────────────────────────────────────

    private String extractDocx(InputStream is) throws IOException {
        try (XWPFDocument doc = new XWPFDocument(is)) {
            StringBuilder sb = new StringBuilder();
            for (XWPFParagraph p : doc.getParagraphs()) {
                sb.append(p.getText()).append("\n");
            }
            for (XWPFTable table : doc.getTables()) {
                for (XWPFTableRow row : table.getRows()) {
                    for (XWPFTableCell cell : row.getTableCells()) {
                        sb.append(cell.getText()).append(" | ");
                    }
                    sb.append("\n");
                }
            }
            return sb.toString();
        }
    }

    // ── Section grouping ─────────────────────────────────────

    private Map<String, Object> structureText(String raw) {
        List<String> lines = Arrays.stream(raw.split("\n"))
                .map(String::trim)
                .filter(l -> !l.isBlank())
                .toList();

        // Try to detect name / contact on first few lines
        Map<String, Object> result = new LinkedHashMap<>();
        Map<String, List<String>> sections = new LinkedHashMap<>();
        String currentSection = "HEADER";
        sections.put(currentSection, new ArrayList<>());

        for (String line : lines) {
            if (SECTION_HEADER.matcher(line).matches()) {
                currentSection = line.toUpperCase().replaceAll("[^A-Z ]", "").trim();
                sections.putIfAbsent(currentSection, new ArrayList<>());
            } else {
                sections.get(currentSection).add(line);
            }
        }

        // Extract contact info from HEADER block
        List<String> header = sections.remove("HEADER");
        if (header != null && !header.isEmpty()) {
            result.put("name",    header.get(0));
            result.put("contact", header.size() > 1 ? header.subList(1, header.size()) : List.of());
        }

        result.put("rawText", raw);
        result.put("sections", sections);
        result.put("wordCount", raw.split("\\s+").length);

        return result;
    }
}