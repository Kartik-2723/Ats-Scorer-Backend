package com.resumeshaper.latex;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.resumeshaper.ats.ATSReport;
import com.resumeshaper.ats.ATSScoreService;
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
import org.apache.pdfbox.pdmodel.PDDocument;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;
import org.apache.pdfbox.Loader;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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
    private final ATSScoreService      atsScoreService;
    private final ApplicationContext   applicationContext;

    private static final List<JobStatus> ACTIVE_STATUSES = List.of(
            JobStatus.PENDING, JobStatus.CONVERTING,
            JobStatus.RESHAPING_LATEX, JobStatus.COMPILING,
            JobStatus.FIX_RETRY, JobStatus.FITTING_PAGE
    );
    private static final int IDEMPOTENCY_WINDOW_MINUTES = 30;

    // ─────────────────────────────────────────────────────────────────────────
    // Entry point
    // ─────────────────────────────────────────────────────────────────────────

    @Transactional
    public ResumeJob submit(MultipartFile file,
                            InputType inputType,
                            LatexReshapeRequest req,
                            User user) throws IOException {

        OffsetDateTime since = OffsetDateTime.now().minusMinutes(IDEMPOTENCY_WINDOW_MINUTES);

        boolean alreadyRunning = (user == null)
                ? jobRepository.hasActiveGuestJob(req.getGuestToken(), ACTIVE_STATUSES, since)
                : jobRepository.hasActiveUserJob(user.getId(), ACTIVE_STATUSES, since);

        if (alreadyRunning) {
            throw new AppException("A job is already in progress", HttpStatus.CONFLICT);
        }

        String s3Key = storage.uploadBytes(
                file.getBytes(),
                "originals/" + UUID.randomUUID() + "/" + file.getOriginalFilename(),
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

        final UUID jobId     = job.getId();
        final byte[] fileBytes = file.getBytes();
        final LatexReshapeOrchestrator self =
                applicationContext.getBean(LatexReshapeOrchestrator.class);

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                self.runPipeline(jobId, fileBytes, inputType, req);
            }
        });

        return job;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Async pipeline
    // ─────────────────────────────────────────────────────────────────────────

    @Async("llmExecutor")
    public void runPipeline(UUID jobId,
                            byte[] fileBytes,
                            InputType inputType,
                            LatexReshapeRequest req) {

        ResumeJob job = jobRepository.findById(jobId)
                .orElseThrow(() -> new AppException("Job not found", HttpStatus.NOT_FOUND));

        log.info("LaTeX reshape pipeline start: job={} inputType={}", jobId, inputType);

        try {
            String rawLatex;
            String extractedText = null;

            updateStatus(job, JobStatus.RESHAPING_LATEX);

            // ── Stage 1: Extract text (PDF only) ─────────────────────────────
            if (inputType == InputType.PDF) {
                updateStatus(job, JobStatus.CONVERTING);
                extractedText = pdfExtractor.extract(fileBytes);
                log.debug("job={} PDF extracted: {} chars", jobId, extractedText.length());
                updateStatus(job, JobStatus.RESHAPING_LATEX);
            }

            // ── Stage 2: Plan ─────────────────────────────────────────────────
            String textForPlanner = (inputType == InputType.PDF)
                    ? extractedText : job.getRawLatex();

            log.info("job={} Phase 1: running planner", jobId);
            ResumePlan plan = runPlanner(jobId, textForPlanner, req);

            savePlanMetadata(job, plan);
            log.info("job={} Plan: profileType={} sections={} bold={} inject={} gaps={}",
                    jobId, plan.getProfileType(), plan.getRankedSections(),
                    plan.getMustBoldKeywords(), plan.getInjectableKeywords(),
                    plan.getAtsGapKeywords());

            String originalTextForScoring = textForPlanner;
            scoreOriginalRulesBased(job, originalTextForScoring, plan);

            // ── Stage 3a: Content rewrite ─────────────────────────────────────
            log.info("job={} Phase 2a: content rewrite", jobId);
            Map<String, Object> contentResult = gemini.generateJson(
                    latexPrompts.contentRewriteSystemInstruction(),
                    latexPrompts.contentRewritePrompt(
                            textForPlanner, plan, req.getRoleLabel(), req.getJdText())
            );
            saveChangesLog(job, contentResult);

            String structuredContentJson = objectMapper.writeValueAsString(contentResult);

            // ── Stage 3b: LaTeX generation ────────────────────────────────────
            log.info("job={} Phase 2b: LaTeX generation", jobId);
            rawLatex = gemini.generateLatex(
                    latexPrompts.latexSystemInstruction(),
                    latexPrompts.latexGeneratePrompt(
                            structuredContentJson, plan, req.getRoleLabel())
            );
            saveRawLatex(job, rawLatex);

            // ── Stage 4: Compile loop (max 3 attempts) ────────────────────────
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

                    int fixAttempt = attempt + 1;
                    log.info("job={} sending to LLM for fix (fixAttempt={})", jobId, fixAttempt);
                    Map<String, Object> fixResult = gemini.generateJson(
                            latexPrompts.latexSystemInstruction(),
                            latexPrompts.latexFixPrompt(
                                    latexToCompile, ex.getCompilerLog(), plan, fixAttempt)
                    );
                    latexToCompile = extractLatexFix(fixResult);
                }
            }

            // ── Stage 7: Fit to 1 page ────────────────────────────────────────
            updateStatus(job, JobStatus.FITTING_PAGE);
            FitPageResult fitResult = fitToOnePage(jobId, latexToCompile, compiledPdf, plan);
            latexToCompile = fitResult.latex();
            compiledPdf    = fitResult.pdf();

            // ── Stage 5: Upload compiled PDF ──────────────────────────────────
            String pdfKey = storage.uploadBytes(
                    compiledPdf,
                    "compiled/" + jobId + "/resume.pdf",
                    "application/pdf"
            );

            finalise(job, latexToCompile, pdfKey);
            log.info("LaTeX reshape pipeline DONE: job={} profileType={} attempts={}",
                    jobId, plan.getProfileType(), job.getLatexCompileAttempts());

            // ── Stage 6: Async ATS score ──────────────────────────────────────
            final UUID   finalJobId       = job.getId();
            final String finalOriginalText = originalTextForScoring;
            final String finalLatexCopy    = latexToCompile;
            final String finalRole         = req.getRoleLabel();

            applicationContext.getBean(LatexReshapeOrchestrator.class)
                    .scoreAsync(finalJobId, finalOriginalText, finalLatexCopy, finalRole);

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
    // Stage 7 — Fit to 1 page (non-fatal, 3-tier)
    // ─────────────────────────────────────────────────────────────────────────

    private FitPageResult fitToOnePage(UUID jobId,
                                       String latex,
                                       byte[] pdf,
                                       ResumePlan plan) {
        int pages = countPdfPages(pdf);
        log.info("job={} fit-page: detected {} page(s)", jobId, pages);

        if (pages == 1) {
            log.info("job={} fit-page: already 1 page — skipping", jobId);
            return new FitPageResult(latex, pdf);
        }

        // Tier 1: spacing expand — only if somehow 0 pages detected (defensive)
        // In practice pages > 1 means we go straight to Tier 2

        // Tier 2: sizing compress
        try {
            log.info("job={} fit-page Tier 2: sizing compress ({} pages)", jobId, pages);
            String compressedLatex = gemini.generateLatex(
                    latexPrompts.latexSystemInstruction(),
                    latexPrompts.sizingCompressPrompt(latex, pages)
            );
            byte[] compressedPdf = compiler.compile(compressedLatex);
            int newPages = countPdfPages(compressedPdf);

            if (newPages == 1) {
                log.info("job={} fit-page Tier 2 SUCCESS: now 1 page", jobId);
                return new FitPageResult(compressedLatex, compressedPdf);
            }

            log.info("job={} fit-page Tier 2 insufficient ({} pages) — escalating to Tier 3",
                    jobId, newPages);
            // Use compressed as base for Tier 3
            latex = compressedLatex;
            pages = newPages;

        } catch (LatexCompileException ex) {
            log.warn("job={} fit-page Tier 2 compile failed — escalating to Tier 3: {}",
                    jobId, ex.getMessage());
        } catch (Exception ex) {
            log.warn("job={} fit-page Tier 2 LLM failed — escalating to Tier 3: {}",
                    jobId, ex.getMessage());
        }

        // Tier 3: content trim — last resort
        try {
            log.info("job={} fit-page Tier 3: content trim ({} pages)", jobId, pages);
            String trimmedLatex = gemini.generateLatex(
                    latexPrompts.latexSystemInstruction(),
                    latexPrompts.contentTrimPrompt(latex, pages)
            );
            byte[] trimmedPdf = compiler.compile(trimmedLatex);
            int newPages = countPdfPages(trimmedPdf);
            log.info("job={} fit-page Tier 3 result: {} page(s)", jobId, newPages);
            return new FitPageResult(trimmedLatex, trimmedPdf);

        } catch (LatexCompileException ex) {
            log.warn("job={} fit-page Tier 3 compile failed — returning best available result: {}",
                    jobId, ex.getMessage());
        } catch (Exception ex) {
            log.warn("job={} fit-page Tier 3 LLM failed — returning best available result: {}",
                    jobId, ex.getMessage());
        }

        // All tiers failed — return whatever we had going in (non-fatal)
        log.warn("job={} fit-page: all tiers exhausted — using pre-fit result", jobId);
        return new FitPageResult(latex, pdf);
    }

    private int countPdfPages(byte[] pdfBytes) {
        try (PDDocument doc = Loader.loadPDF(pdfBytes)) {
            return doc.getNumberOfPages();
        } catch (Exception e) {
            log.warn("Page count detection failed — assuming 1 page: {}", e.getMessage());
            return 1;
        }
    }

    private record FitPageResult(String latex, byte[] pdf) {}

    // ─────────────────────────────────────────────────────────────────────────
    // Phase 1 — Planner
    // ─────────────────────────────────────────────────────────────────────────

    private ResumePlan runPlanner(UUID jobId,
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

    @SuppressWarnings("unchecked")
    private ResumePlan parsePlan(Map<String, Object> raw) {
        String profileType = raw.getOrDefault("profileType", "STUDENT_FRESHER").toString();

        List<String> discoveredSections = toStringList(raw.get("discoveredSections"));
        List<String> rankedSections     = toStringList(raw.get("rankedSections"));
        List<String> mustBold           = toStringList(raw.get("mustBoldKeywords"));
        List<String> injectable         = toStringList(raw.get("injectableKeywords"));
        List<String> atsGap             = toStringList(raw.get("atsGapKeywords"));

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
    // Rules-based ATS score (synchronous)
    // ─────────────────────────────────────────────────────────────────────────

    @Transactional
    protected void scoreOriginalRulesBased(ResumeJob job,
                                           String originalText,
                                           ResumePlan plan) {
        try {
            List<String> allJdKeywords = new ArrayList<>();
            if (plan.getMustBoldKeywords()   != null) allJdKeywords.addAll(plan.getMustBoldKeywords());
            if (plan.getInjectableKeywords() != null) allJdKeywords.addAll(plan.getInjectableKeywords());
            if (plan.getAtsGapKeywords()     != null) allJdKeywords.addAll(plan.getAtsGapKeywords());

            String plainText = originalText.contains("\\documentclass")
                    ? ATSScoreService.stripLatex(originalText)
                    : originalText;

            ATSReport report = atsScoreService.score(plainText, allJdKeywords);

            job.setAtsScoreBefore(report.getOverallScore());
            job.setAtsReport(Map.of(
                    "overallScore",    report.getOverallScore(),
                    "keywordScore",    report.getKeywordScore(),
                    "sectionScore",    report.getSectionScore(),
                    "formatScore",     report.getFormatScore(),
                    "verbScore",       report.getVerbScore(),
                    "matchedKeywords", report.getMatchedKeywords(),
                    "missingKeywords", report.getMissingKeywords(),
                    "presentSections", report.getPresentSections(),
                    "formatIssues",    report.getFormatIssues()
            ));

            jobRepository.save(job);
            log.info("job={} atsScoreBefore={} (rules-based, {} keywords checked)",
                    job.getId(), report.getOverallScore(), allJdKeywords.size());

        } catch (Exception ex) {
            log.warn("job={} rules-based ATS scoring failed (non-fatal): {}",
                    job.getId(), ex.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // LLM ATS score (async)
    // ─────────────────────────────────────────────────────────────────────────

    @Async("llmExecutor")
    public void scoreAsync(UUID jobId,
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

            Object after = scores.get("atsScoreAfter");
            if (after instanceof Number n) job.setAtsScoreAfter(n.intValue());

            jobRepository.save(job);
            log.debug("job={} atsScoreAfter={} (LLM adversarial)", jobId, job.getAtsScoreAfter());

        } catch (Exception ex) {
            log.warn("job={} LLM ATS scoring failed (non-fatal): {}", jobId, ex.getMessage());
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

    @Transactional
    protected void savePlanMetadata(ResumeJob job, ResumePlan plan) {
        job.setProfileTypeDetected(plan.getProfileType());

        if (plan.getAtsGapKeywords() != null && !plan.getAtsGapKeywords().isEmpty()) {
            job.setAtsGapKeywords(plan.getAtsGapKeywords());
        }

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