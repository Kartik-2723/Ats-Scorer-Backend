package com.resumeshaper.latex;

import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.List;

/**
 * Extracts structured text from a resume PDF using PDFBox 3.x positional API.
 *
 * Strategy:
 *  1. PDFTextStripper with sorting enabled — preserves reading order
 *  2. Custom stripper captures font-size and bold hints per text run
 *  3. Output is a structured string the LLM can use to reconstruct LaTeX
 *     with correct section detection, bold candidates, and bullet detection
 *
 * Why not just getText()?
 *  Plain getText() loses font-size (heading vs body), bold flag (skill names),
 *  and bullet character detection. The richer output gives the LLM better
 *  signals for accurate LaTeX reconstruction.
 */
@Slf4j
@Component
public class PdfTextExtractor {

    private static final float HEADING_FONT_SIZE_THRESHOLD = 12.0f;
    private static final float BOLD_FONT_SIZE_THRESHOLD    = 10.5f;

    /**
     * Extract structured text from PDF bytes.
     * Returns a plain-text representation enriched with structural hints:
     *   [HEADING] — large font lines (likely section titles)
     *   [BOLD]    — bold or large-for-body lines (names, skill labels)
     *   [BULLET]  — lines starting with bullet characters
     *   (plain)   — normal body text
     *
     * @param pdfBytes raw PDF bytes from upload or S3
     * @return structured text string, ready to send to LLM
     */
    public String extract(byte[] pdfBytes) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdfBytes)) {
            if (doc.isEncrypted()) {
                throw new IllegalArgumentException(
                        "PDF is encrypted — cannot extract text. Ask user to upload unprotected PDF.");
            }

            HintingTextStripper stripper = new HintingTextStripper();
            stripper.setSortByPosition(true);
            stripper.getText(doc);   // triggers processTextPosition callbacks

            String result = stripper.getStructuredText();
            log.debug("PDF extraction complete: {} chars, {} lines",
                    result.length(), result.lines().count());
            return result;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Custom stripper — captures font metadata per character run
    // ─────────────────────────────────────────────────────────────────────────

    private static class HintingTextStripper extends PDFTextStripper {

        private record TextRun(String text, float fontSize, boolean bold) {}

        private final List<TextRun> runs = new ArrayList<>();

        HintingTextStripper() throws IOException {
            super();
        }

        @Override
        protected void processTextPosition(TextPosition text) {
            super.processTextPosition(text);

            String fontName   = text.getFont().getName();
            float  fontSize   = text.getFontSizeInPt();
            boolean isBold    = fontName != null &&
                    (fontName.toLowerCase().contains("bold") ||
                            fontName.toLowerCase().contains("heavy"));

            runs.add(new TextRun(text.getUnicode(), fontSize, isBold));
        }

        /**
         * Groups runs into lines and annotates each line with a structural hint.
         */
        String getStructuredText() throws IOException {
            // getText() has already been called — we work with the raw runs
            // we collected via processTextPosition.
            // We group by approximate Y position (not available here directly),
            // so instead we reconstruct from the stripper's output text + run metadata.

            // Simpler approach: use stripper output for line structure,
            // annotate each line based on the font statistics of runs on that line.

            // Re-run plain stripper for line text (already done via getText() call)
            StringBuilder sb = new StringBuilder();

            // Segment runs into "words" and estimate line-level font stats
            float currentSize = 0;
            boolean currentBold = false;
            StringBuilder lineBuffer = new StringBuilder();
            List<Float> lineSizes = new ArrayList<>();
            List<Boolean> lineBolds = new ArrayList<>();

            for (TextRun run : runs) {
                String ch = run.text();
                if (ch.equals("\n") || ch.equals("\r")) {
                    // flush line
                    if (!lineBuffer.isEmpty()) {
                        appendAnnotatedLine(sb, lineBuffer.toString().trim(), lineSizes, lineBolds);
                        lineBuffer.setLength(0);
                        lineSizes.clear();
                        lineBolds.clear();
                    }
                } else {
                    lineBuffer.append(ch);
                    lineSizes.add(run.fontSize());
                    lineBolds.add(run.bold());
                }
            }
            // flush last line
            if (!lineBuffer.isEmpty()) {
                appendAnnotatedLine(sb, lineBuffer.toString().trim(), lineSizes, lineBolds);
            }

            return sb.toString();
        }

        private void appendAnnotatedLine(StringBuilder sb,
                                         String line,
                                         List<Float> sizes,
                                         List<Boolean> bolds) {
            if (line.isBlank()) {
                sb.append("\n");
                return;
            }

            float avgSize  = (float) sizes.stream().mapToDouble(f -> f).average().orElse(10.0);
            long  boldCount = bolds.stream().filter(b -> b).count();
            boolean majorityBold = boldCount > bolds.size() / 2;

            // Detect bullet characters
            boolean isBullet = line.startsWith("•") || line.startsWith("–") ||
                    line.startsWith("-") || line.startsWith("*") ||
                    line.startsWith("▪") || line.startsWith("◦");

            if (avgSize >= HEADING_FONT_SIZE_THRESHOLD || isAllCaps(line)) {
                sb.append("[HEADING] ").append(line).append("\n");
            } else if (isBullet) {
                // strip the bullet character for cleaner LLM input
                sb.append("[BULLET] ").append(line.replaceFirst("^[•–\\-*▪◦]\\s*", "")).append("\n");
            } else if (majorityBold && avgSize >= BOLD_FONT_SIZE_THRESHOLD) {
                sb.append("[BOLD] ").append(line).append("\n");
            } else {
                sb.append(line).append("\n");
            }
        }

        private boolean isAllCaps(String line) {
            String letters = line.replaceAll("[^a-zA-Z]", "");
            return !letters.isEmpty() && letters.equals(letters.toUpperCase());
        }

        // PDFTextStripper requires this override — we write nothing since
        // we collect runs directly in processTextPosition
        @Override
        protected void writeString(String text, List<TextPosition> textPositions)
                throws IOException {
            super.writeString(text, textPositions);
        }
    }
}