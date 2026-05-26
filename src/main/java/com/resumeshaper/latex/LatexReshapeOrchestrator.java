package com.resumeshaper.latex;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.resumeshaper.common.exception.AppException;
import com.resumeshaper.common.exception.GeminiQuotaExhaustedException;
import com.resumeshaper.llm.GeminiApiClient;
import com.resumeshaper.resume.InputType;
import com.resumeshaper.resume.JobStatus;
import com.resumeshaper.resume.ResumeJob;
import com.resumeshaper.resume.ResumeJobRepository;
import com.resumeshaper.session.GuestSessionService;
import com.resumeshaper.storage.S3FileStorageService;
import com.resumeshaper.user.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Async pipeline for the LaTeX reshape feature.
 *
 * ── Pipeline stages ──────────────────────────────────────────────────────────
 *   PDF:   PENDING → CONVERTING → RESHAPING_LATEX → COMPILING → DONE
 *   LaTeX: PENDING → RESHAPING_LATEX → COMPILING → DONE
 *
 * ── Two-phase reshape (both happen inside RESHAPING_LATEX) ───────────────────
 *   Phase 1 — PLANNING:
 *     Planner LLM call reads the raw resume and returns a structured ResumePlan:
 *       - profileType (STUDENT_FRESHER / EARLY_CAREER / MID_SENIOR / CAREER_SWITCHER)
 *       - discoveredSections (every section found — none may be dropped)
 *       - rankedSections (reordered by ATS impact for the given role + profileType)
 *       - mustBoldKeywords, injectableKeywords, atsGapKeywords, contentFlags
 *
 *   Phase 2 — RESHAPE:
 *     Reshape LLM call receives the plan explicitly.
 *     Section order, bold keywords, injectable keywords all come from the plan.
 *     No hardcoded section ordering anywhere.
 *
 * ── Compile retry loop (max 3 attempts) ──────────────────────────────────────
 *   Attempt 1 — initial compile
 *   Attempt 2 — standard LLM fix (fix syntax only, preserve everything)
 *   Attempt 3 — aggressive LLM fix (simplify constructs if needed, still preserve content)
 *   Both fix prompts are plan-aware: section order and preservation enforced.
 *
 * ── ATS scoring ──────────────────────────────────────────────────────────────
 *   Runs as a separate fire-and-forget call AFTER successful compile.
 *   Never blocks the critical path.
 *
 * ── Plan persistence ─────────────────────────────────────────────────────────
 *   profileTypeDetected, atsGapKeywords, contentFlags saved to ResumeJob
 *   so the frontend can surface them as improvement suggestions after DONE.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LatexReshapeOrchestrator {

    private static final int MAX_COMPILE_ATTEMPTS = 3;

    private final PdfTextExtractor     pdfExtractor;
    private final GeminiApiClient      gemini;
    private final LatexPromptBuilder   latexPrompts;
    private final LatexCompilerService compiler;
    private final ResumeJobRepository  jobRepository;
    private final S3FileStorageService storage;
    private final GuestSessionService  guestSessionService;
    private final ObjectMapper         objectMapper;

    // ─────────────────────────────────────────────────────────────────────────
    // Entry point (synchronous)
    // ─────────────────────────────────────────────────────────────────────────

    @Transactional
    public ResumeJob submit(MultipartFile file,
                            InputType inputType,
                            LatexReshapeRequest req,
                            User user) throws IOException {

        String s3Key = storage.uploadBytes(
                file.getBytes(),
                "originals/" + java.util.UUID.randomUUID() + "/" + file.getOriginalFilename(),
                file.getContentType() != null ? file.getContentType() : "application/octet-stream"
        );

        ResumeJob job = ResumeJob.builder()
                .user(user)
                .guestToken(user == null ? req.getGuestToken() : null)
                .roleLabel(req.getRoleLabel())
                .roleCategory(req.getRoleCategory())
                .customRole(req.isCustomRole())
                .jdText(req.getJdText())
                .inputType(inputType)
                .originalFileKey(s3Key)
                .originalFileName(file.getOriginalFilename() != null
                        ? file.getOriginalFilename() : "resume")
                .status(JobStatus.PENDING)
                .build();

        if (inputType == InputType.LATEX) {
            job.setRawLatex(new String(file.getBytes(), java.nio.charset.StandardCharsets.UTF_8));
        }

        job = jobRepository.save(job);

        byte[] fileBytes = file.getBytes();
        runPipeline(job.getId(), fileBytes, inputType, req);

        return job;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Async pipeline
    // ─────────────────────────────────────────────────────────────────────────

    @Async("llmExecutor")
    public void runPipeline(java.util.UUID jobId,
                            byte[] fileBytes,
                            InputType inputType,
                            LatexReshapeRequest req) {

        ResumeJob job = jobRepository.findById(jobId)
                .orElseThrow(() -> new AppException("Job not found", HttpStatus.NOT_FOUND));

        log.info("LaTeX reshape pipeline start: job={} inputType={}", jobId, inputType);

        try {
            String rawLatex;
            String extractedText = null;  // kept for ATS scoring after compile

            updateStatus(job, JobStatus.RESHAPING_LATEX);

            // ── Stage 1: Extract text (PDF only) ──────────────────────────────
            if (inputType == InputType.PDF) {
                updateStatus(job, JobStatus.CONVERTING);
                extractedText = pdfExtractor.extract(fileBytes);
                log.debug("job={} PDF extracted: {} chars", jobId, extractedText.length());
                updateStatus(job, JobStatus.RESHAPING_LATEX);
            }

            // ── Stage 2: Phase 1 — Plan ───────────────────────────────────────
            // Planner reads the raw resume and produces a structured ResumePlan.
            // This determines section order, keywords, and profile type.
            // Runs silently inside RESHAPING_LATEX — no new JobStatus needed.
            String textForPlanner = (inputType == InputType.PDF)
                    ? extractedText
                    : job.getRawLatex();

            log.info("job={} Phase 1: running planner", jobId);
            ResumePlan plan = runPlanner(jobId, textForPlanner, req);

            // Persist plan metadata to DB immediately — frontend can use after DONE
            savePlanMetadata(job, plan);
            log.info("job={} Plan complete: profileType={} sections={} boldKeywords={} injectKeywords={}",
                    jobId,
                    plan.getProfileType(),
                    plan.getRankedSections(),
                    plan.getMustBoldKeywords(),
                    plan.getInjectableKeywords());

            // ── Stage 3: Phase 2 — Reshape using plan ────────────────────────
            log.info("job={} Phase 2: reshaping with plan", jobId);

            if (inputType == InputType.PDF) {
                Map<String, Object> llmResult = gemini.generateJson(
                        latexPrompts.latexSystemInstruction(),
                        latexPrompts.pdfToLatexReshapePrompt(
                                extractedText, plan, req.getRoleLabel(), req.getJdText())
                );
                rawLatex = extractLatex(llmResult);
                saveRawLatex(job, rawLatex);
                saveChangesLog(job, llmResult);

            } else {
                Map<String, Object> llmResult = gemini.generateJson(
                        latexPrompts.latexSystemInstruction(),
                        latexPrompts.latexReshapePrompt(
                                job.getRawLatex(), plan, req.getRoleLabel(), req.getJdText())
                );
                rawLatex = extractLatex(llmResult);
                saveChangesLog(job, llmResult);
            }

            // ── Stage 4: Compile loop (max 3 attempts) ────────────────────────
            // Attempt 1 — initial compile
            // Attempt 2 — standard fix (syntax only, plan-aware)
            // Attempt 3 — aggressive fix (simplify constructs, plan-aware)
            String latexToCompile = rawLatex;
            byte[] compiledPdf    = null;

            for (int attempt = 1; attempt <= MAX_COMPILE_ATTEMPTS; attempt++) {
                incrementCompileAttempts(job);
                updateStatus(job, attempt == 1 ? JobStatus.COMPILING : JobStatus.FIX_RETRY);

                try {
                    compiledPdf = compiler.compile(latexToCompile);
                    log.info("job={} Tectonic compile OK on attempt {}", jobId, attempt);
                    break;

                } catch (LatexCompileException ex) {
                    log.warn("job={} compile failed attempt {}/{}: {}",
                            jobId, attempt, MAX_COMPILE_ATTEMPTS, ex.getMessage());

                    if (attempt == MAX_COMPILE_ATTEMPTS) {
                        throw new LatexCompileException(
                                "All " + MAX_COMPILE_ATTEMPTS + " compile attempts failed. " +
                                        "Last error: " + ex.getMessage(),
                                ex.getCompilerLog());
                    }

                    // Pass attempt number so prompt builder can escalate strategy:
                    // attempt=2 → standard fix (fix syntax only)
                    // attempt=3 → aggressive fix (simplify constructs if needed)
                    int fixAttempt = attempt + 1;
                    log.info("job={} sending to LLM for fix (fixAttempt={})", jobId, fixAttempt);

                    Map<String, Object> fixResult = gemini.generateJson(
                            latexPrompts.latexSystemInstruction(),
                            latexPrompts.latexFixPrompt(
                                    latexToCompile,
                                    ex.getCompilerLog(),
                                    plan,         // plan enforces section preservation during fix
                                    fixAttempt    // drives standard vs aggressive strategy
                            )
                    );
                    latexToCompile = extractLatexFix(fixResult);
                }
            }

            // ── Stage 5: Upload compiled PDF ──────────────────────────────────
            updateStatus(job, JobStatus.COMPILING);
            String pdfKey = storage.uploadBytes(
                    compiledPdf,
                    "compiled/" + jobId + "/resume.pdf",
                    "application/pdf"
            );

            finalise(job, latexToCompile, pdfKey);
            log.info("LaTeX reshape pipeline DONE: job={} profileType={} attempts={}",
                    jobId, plan.getProfileType(), job.getLatexCompileAttempts());

            // ── Stage 6: ATS scoring (non-blocking, best-effort) ──────────────
            // Fires after DONE so it never delays the user seeing their result.
            final String finalLatex     = latexToCompile;
            final String finalExtracted = extractedText;
            scoreAsync(job.getId(),
                    finalExtracted != null ? finalExtracted : job.getRawLatex(),
                    finalLatex,
                    req.getRoleLabel());

        } catch (GeminiQuotaExhaustedException ex) {
            log.warn("All Gemini models quota-exhausted for job={}", jobId);
            markFailed(job, "QUOTA_EXHAUSTED — all models rate-limited. Try again later.");

        } catch (LatexCompileException ex) {
            log.error("job={} all compile attempts failed", jobId);
            markFailed(job, "LaTeX compile failed after " + MAX_COMPILE_ATTEMPTS +
                    " attempts: " + ex.getMessage());

        } catch (Exception ex) {
            log.error("LaTeX reshape pipeline FAILED for job={}", jobId, ex);
            markFailed(job, ex.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Phase 1 — Planner
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Runs the planner LLM call and parses the result into a ResumePlan.
     *
     * Falls back to a safe default plan if:
     *   - The LLM returns malformed JSON
     *   - Required fields are missing
     * This ensures the reshape phase always has a valid plan and never crashes
     * due to a planner failure.
     */
    private ResumePlan runPlanner(java.util.UUID jobId,
                                  String resumeText,
                                  LatexReshapeRequest req) {
        try {
            Map<String, Object> raw = gemini.generateJson(
                    latexPrompts.plannerSystemInstruction(),
                    latexPrompts.resumePlannerPrompt(
                            resumeText,
                            req.getRoleLabel(),
                            req.getRoleCategory() != null ? req.getRoleCategory() : "",
                            req.getJdText()
                    )
            );
            return parsePlan(raw);

        } catch (Exception ex) {
            log.warn("job={} Planner LLM call failed — using safe default plan: {}",
                    jobId, ex.getMessage());
            return buildDefaultPlan();
        }
    }

    /**
     * Parses the raw LLM map into a typed ResumePlan.
     * Each field is extracted defensively — missing fields fall back to empty lists.
     */
    @SuppressWarnings("unchecked")
    private ResumePlan parsePlan(Map<String, Object> raw) {
        String profileType = raw.getOrDefault("profileType", "STUDENT_FRESHER").toString();

        List<String> discoveredSections = toStringList(raw.get("discoveredSections"));
        List<String> rankedSections     = toStringList(raw.get("rankedSections"));
        List<String> mustBold           = toStringList(raw.get("mustBoldKeywords"));
        List<String> injectable         = toStringList(raw.get("injectableKeywords"));
        List<String> atsGap             = toStringList(raw.get("atsGapKeywords"));

        // Parse contentFlags — array of {section, bullet, suggestion} objects
        List<ResumePlan.ContentFlag> flags = new ArrayList<>();
        Object rawFlags = raw.get("contentFlags");
        if (rawFlags instanceof List<?> flagList) {
            for (Object item : flagList) {
                if (item instanceof Map<?, ?> m) {
                    flags.add(ResumePlan.ContentFlag.builder()
                            .section(getString(m, "section"))
                            .bullet(getString(m, "bullet"))
                            .suggestion(getString(m, "suggestion"))
                            .build());
                }
            }
        }

        // Guard: if rankedSections is empty or mismatched, fall back to discoveredSections
        if (rankedSections.isEmpty()) {
            rankedSections = new ArrayList<>(discoveredSections);
        }

        return ResumePlan.builder()
                .profileType(profileType)
                .discoveredSections(discoveredSections)
                .rankedSections(rankedSections)
                .mustBoldKeywords(mustBold)
                .injectableKeywords(injectable)
                .atsGapKeywords(atsGap)
                .contentFlags(flags)
                .build();
    }

    /**
     * Safe default plan used when the planner LLM call fails.
     * Empty sections list means the reshape prompt will use its own judgment —
     * still better than crashing the entire pipeline.
     */
    private ResumePlan buildDefaultPlan() {
        return ResumePlan.builder()
                .profileType("STUDENT_FRESHER")
                .discoveredSections(new ArrayList<>())
                .rankedSections(new ArrayList<>())
                .mustBoldKeywords(new ArrayList<>())
                .injectableKeywords(new ArrayList<>())
                .atsGapKeywords(new ArrayList<>())
                .contentFlags(new ArrayList<>())
                .build();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ATS score — async, best-effort, never blocks pipeline
    // ─────────────────────────────────────────────────────────────────────────

    @Async("llmExecutor")
    public void scoreAsync(java.util.UUID jobId,
                           String originalText,
                           String shapedLatex,
                           String roleLabel) {
        try {
            Map<String, Object> scores = gemini.generateJson(
                    "You are an ATS scoring assistant. Return only JSON.",
                    latexPrompts.atsScorePrompt(originalText, shapedLatex, roleLabel)
            );

            ResumeJob job = jobRepository.findById(jobId).orElse(null);
            if (job == null) return;

            Object before = scores.get("atsScoreBefore");
            Object after  = scores.get("atsScoreAfter");
            if (before instanceof Number n) job.setAtsScoreBefore(n.intValue());
            if (after  instanceof Number n) job.setAtsScoreAfter(n.intValue());
            jobRepository.save(job);
            log.debug("job={} ATS scores saved: before={} after={}", jobId, before, after);

        } catch (Exception ex) {
            log.warn("job={} ATS scoring failed (non-fatal): {}", jobId, ex.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // DB helpers
    // ─────────────────────────────────────────────────────────────────────────

    @Transactional
    protected void updateStatus(ResumeJob job, JobStatus status) {
        job.setStatus(status);
        jobRepository.save(job);
    }

    @Transactional
    protected void saveRawLatex(ResumeJob job, String rawLatex) {
        job.setRawLatex(rawLatex);
        jobRepository.save(job);
    }

    @Transactional
    protected void saveChangesLog(ResumeJob job, Map<String, Object> llmResult) {
        Object changes = llmResult.get("changesLog");
        if (changes != null) {
            job.setShapedResume(Map.of("changesLog", changes));
            jobRepository.save(job);
        }
    }

    /**
     * Persists plan metadata to ResumeJob immediately after planning completes.
     * atsGapKeywords and contentFlags are surfaced to the frontend after DONE
     * as "manual improvement suggestions".
     */
    @Transactional
    protected void savePlanMetadata(ResumeJob job, ResumePlan plan) {
        job.setProfileTypeDetected(plan.getProfileType());

        // Store atsGapKeywords as JSON array in the existing jsonb column
        if (plan.getAtsGapKeywords() != null && !plan.getAtsGapKeywords().isEmpty()) {
            job.setAtsGapKeywords(plan.getAtsGapKeywords());
        }

        // Store contentFlags as structured JSON for frontend display
        if (plan.getContentFlags() != null && !plan.getContentFlags().isEmpty()) {
            List<Map<String, String>> flags = plan.getContentFlags().stream()
                    .map(f -> Map.of(
                            "section",    f.getSection()    != null ? f.getSection()    : "",
                            "bullet",     f.getBullet()     != null ? f.getBullet()     : "",
                            "suggestion", f.getSuggestion() != null ? f.getSuggestion() : ""
                    ))
                    .toList();
            job.setContentFlags(flags);
        }

        jobRepository.save(job);
    }

    @Transactional
    protected void incrementCompileAttempts(ResumeJob job) {
        job.setLatexCompileAttempts(job.getLatexCompileAttempts() + 1);
        jobRepository.save(job);
    }

    @Transactional
    protected void finalise(ResumeJob job, String shapedLatex, String compiledPdfKey) {
        job.setShapedLatex(shapedLatex);
        job.setCompiledPdfKey(compiledPdfKey);
        job.setStatus(JobStatus.DONE);
        jobRepository.save(job);
    }

    @Transactional
    protected void markFailed(ResumeJob job, String message) {
        job.setStatus(JobStatus.FAILED);
        job.setErrorMessage(message);
        jobRepository.save(job);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // LLM response helpers
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Extracts LaTeX from main reshape response.
     * Checks "shapedLatex" only — the main prompts always use this key.
     */
    private String extractLatex(Map<String, Object> result) {
        Object val = result.get("shapedLatex");
        if (val instanceof String s && !s.isBlank()) return s;
        throw new AppException(
                "LLM did not return a 'shapedLatex' field in response",
                HttpStatus.INTERNAL_SERVER_ERROR);
    }

    /**
     * Extracts LaTeX from fix response.
     * Checks "fixedLatex" first (correct key), then "shapedLatex" as fallback
     * in case the model drifts back to the system instruction schema.
     */
    private String extractLatexFix(Map<String, Object> result) {
        Object fixed = result.get("fixedLatex");
        if (fixed instanceof String s && !s.isBlank()) return s;

        Object shaped = result.get("shapedLatex");
        if (shaped instanceof String s && !s.isBlank()) {
            log.warn("Fix prompt returned 'shapedLatex' instead of 'fixedLatex' — using fallback");
            return s;
        }

        throw new AppException(
                "LLM did not return 'fixedLatex' or 'shapedLatex' in fix response",
                HttpStatus.INTERNAL_SERVER_ERROR);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Parse helpers
    // ─────────────────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private List<String> toStringList(Object raw) {
        if (raw instanceof List<?> list) {
            return list.stream()
                    .filter(item -> item instanceof String)
                    .map(item -> (String) item)
                    .toList();
        }
        return new ArrayList<>();
    }

    private String getString(Map<?, ?> map, String key) {
        Object val = map.get(key);
        return val instanceof String s ? s : "";
    }
}