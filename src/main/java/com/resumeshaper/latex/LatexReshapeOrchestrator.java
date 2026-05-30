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
import com.resumeshaper.resume.ResumeQueueService;
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

    private static final int MAX_COMPILE_ATTEMPTS  = 3;
    private static final int MIN_LATEX_LENGTH      = 1_500;
    private static final int MIN_LATEX_FIX_LENGTH  = 500;

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
    private final ResumeQueueService   queueService;

    private static final List<JobStatus> ACTIVE_STATUSES = List.of(
            JobStatus.PENDING, JobStatus.CONVERTING,
            JobStatus.RESHAPING_LATEX, JobStatus.COMPILING,
            JobStatus.FIX_RETRY, JobStatus.FITTING_PAGE
    );
    private static final int IDEMPOTENCY_WINDOW_MINUTES = 30;

    // ─────────────────────────────────────────────────────────────────────────
    // Entry point — new submission
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

        final UUID jobId = job.getId();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                queueService.enqueue(jobId);
            }
        });

        return job;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Retry entry point — resets existing job and re-enqueues
    // The original file is already on S3; no re-upload needed.
    // ─────────────────────────────────────────────────────────────────────────

    @Transactional
    public void retrigger(ResumeJob job) {
        log.info("Retrigger: jobId={} previousStatus={}", job.getId(), job.getStatus());

        // Reset all pipeline output fields so the worker runs clean
        job.setStatus(JobStatus.PENDING);
        job.setErrorMessage(null);
        job.setShapedLatex(null);
        job.setCompiledPdfKey(null);
        job.setLatexCompileAttempts(0);
        job.setAtsScoreBefore(null);
        job.setAtsScoreAfter(null);
        job.setAtsReport(null);
        job.setAtsGapKeywords(null);
        job.setContentFlags(null);
        job.setProfileTypeDetected(null);
        job.setShapedResume(null);

        // Keep rawLatex for LATEX input type — it was stored on first submit
        // and is still valid. For PDF the pipeline re-reads from S3.

        job = jobRepository.save(job);

        final UUID jobId = job.getId();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                queueService.enqueue(jobId);
            }
        });
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Pipeline — called by ResumeWorker
    // ─────────────────────────────────────────────────────────────────────────

    public void runPipeline(UUID jobId, String keyId) {

        ResumeJob job = jobRepository.findById(jobId)
                .orElseThrow(() -> new AppException("Job not found", HttpStatus.NOT_FOUND));

        InputType inputType = job.getInputType();
        String project = keyId != null ? keyId : "default";

        log.info("Pipeline start: job={} project={} inputType={} role={}",
                jobId, project, inputType, job.getRoleLabel());

        try {
            String rawLatex;
            String extractedText = null;

            updateStatus(job, JobStatus.RESHAPING_LATEX);

            // ── Stage 1: Extract text (PDF only) ─────────────────────────────
            if (inputType == InputType.PDF) {
                updateStatus(job, JobStatus.CONVERTING);
                byte[] fileBytes = storage.downloadBytes(job.getOriginalFileKey());
                extractedText = pdfExtractor.extract(fileBytes);
                log.debug("job={} project={} PDF extracted: {} chars",
                        jobId, project, extractedText.length());
                updateStatus(job, JobStatus.RESHAPING_LATEX);
            }

            // ── Stage 2: Plan ─────────────────────────────────────────────────
            String textForPlanner = (inputType == InputType.PDF)
                    ? extractedText : job.getRawLatex();

            log.info("job={} project={} Phase 1: planner", jobId, project);
            ResumePlan plan = runPlanner(jobId, textForPlanner, job, keyId);

            savePlanMetadata(job, plan);
            log.info("job={} project={} plan: profileType={} sections={} bold={} inject={} gaps={}",
                    jobId, project, plan.getProfileType(), plan.getRankedSections(),
                    plan.getMustBoldKeywords(), plan.getInjectableKeywords(),
                    plan.getAtsGapKeywords());

            scoreOriginalRulesBased(job, textForPlanner, plan);

            // ── Stage 3a: Content rewrite ─────────────────────────────────────
            log.info("job={} project={} Phase 2a: content rewrite", jobId, project);
            Map<String, Object> contentResult = gemini.generateJson(
                    keyId,
                    latexPrompts.contentRewriteSystemInstruction(),
                    latexPrompts.contentRewritePrompt(
                            textForPlanner, plan, job.getRoleLabel(), job.getJdText())
            );
            saveChangesLog(job, contentResult);

            String structuredContentJson = objectMapper.writeValueAsString(contentResult);

            // ── Stage 3b: LaTeX generation ────────────────────────────────────
            log.info("job={} project={} Phase 2b: LaTeX generation", jobId, project);
            rawLatex = gemini.generateLatex(
                    keyId,
                    latexPrompts.latexSystemInstruction(),
                    latexPrompts.latexGeneratePrompt(
                            structuredContentJson, plan, job.getRoleLabel())
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
                    log.info("job={} project={} compile OK on attempt {}",
                            jobId, project, attempt);
                    break;

                } catch (LatexCompileException ex) {
                    String logTail = ex.getCompilerLog() != null
                            ? ex.getCompilerLog().substring(
                            Math.max(0, ex.getCompilerLog().length() - 1500))
                            : "(no compiler log)";
                    log.warn("job={} project={} compile failed attempt {}/{}: {}\n── compiler log ──\n{}",
                            jobId, project, attempt, MAX_COMPILE_ATTEMPTS,
                            ex.getMessage(), logTail);

                    if (attempt == MAX_COMPILE_ATTEMPTS) {
                        throw new LatexCompileException(
                                "All " + MAX_COMPILE_ATTEMPTS + " compile attempts failed. " +
                                        "Last error: " + ex.getMessage(),
                                ex.getCompilerLog());
                    }

                    int fixAttempt = attempt + 1;
                    log.info("job={} project={} sending to LLM for fix (fixAttempt={})",
                            jobId, project, fixAttempt);
                    Map<String, Object> fixResult = gemini.generateJson(
                            keyId,
                            latexPrompts.latexSystemInstruction(),
                            latexPrompts.latexFixPrompt(
                                    latexToCompile, ex.getCompilerLog(), plan, fixAttempt)
                    );
                    latexToCompile = extractLatexFix(fixResult);
                }
            }

            // ── Stage 5: Fit to 1 page ────────────────────────────────────────
            updateStatus(job, JobStatus.FITTING_PAGE);
            FitPageResult fitResult = fitToOnePage(jobId, project, latexToCompile, compiledPdf, plan, keyId);
            latexToCompile = fitResult.latex();
            compiledPdf    = fitResult.pdf();

            // ── Stage 6: Upload compiled PDF ──────────────────────────────────
            String pdfKey = storage.uploadBytes(
                    compiledPdf,
                    "compiled/" + jobId + "/resume.pdf",
                    "application/pdf"
            );

            finalise(job, latexToCompile, pdfKey);
            log.info("Pipeline DONE: job={} project={} attempts={}",
                    jobId, project, job.getLatexCompileAttempts());

            // ── Stage 7: Async ATS score ──────────────────────────────────────
            final String finalOriginalText = textForPlanner;
            final String finalLatexCopy    = latexToCompile;

            applicationContext.getBean(LatexReshapeOrchestrator.class)
                    .scoreAsync(jobId, finalOriginalText, finalLatexCopy, job.getRoleLabel());

        } catch (GeminiQuotaExhaustedException ex) {
            log.warn("All Gemini models quota-exhausted: job={} project={}", jobId, project);
            markFailed(job, "QUOTA_EXHAUSTED — all models rate-limited. Try again later.");

        } catch (LatexCompileException ex) {
            log.error("Compile failed: job={} project={}", jobId, project);
            markFailed(job, "LaTeX compile failed after " + MAX_COMPILE_ATTEMPTS +
                    " attempts: " + ex.getMessage());

        } catch (Exception ex) {
            log.error("Pipeline FAILED: job={} project={}", jobId, project, ex);
            markFailed(job, ex.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Stage 5 — Fit to 1 page (non-fatal, 3-tier)
    // ─────────────────────────────────────────────────────────────────────────

    private FitPageResult fitToOnePage(UUID jobId,
                                       String project,
                                       String latex,
                                       byte[] pdf,
                                       ResumePlan plan,
                                       String keyId) {
        int pages = countPdfPages(pdf);
        log.info("job={} project={} fit-page: detected {} page(s)", jobId, project, pages);

        if (pages == 1) {
            log.info("job={} project={} fit-page: already 1 page — skipping", jobId, project);
            return new FitPageResult(latex, pdf);
        }

        // Tier 2: sizing compress
        try {
            log.info("job={} project={} fit-page Tier 2: sizing compress ({} pages)",
                    jobId, project, pages);
            String compressedLatex = gemini.generateLatex(
                    keyId,
                    latexPrompts.latexSystemInstruction(),
                    latexPrompts.sizingCompressPrompt(latex, pages)
            );
            byte[] compressedPdf = compiler.compile(compressedLatex);
            int newPages = countPdfPages(compressedPdf);

            if (newPages == 1) {
                log.info("job={} project={} fit-page Tier 2 SUCCESS: now 1 page", jobId, project);
                return new FitPageResult(compressedLatex, compressedPdf);
            }

            log.info("job={} project={} fit-page Tier 2 insufficient ({} pages) — escalating to Tier 3",
                    jobId, project, newPages);
            latex = compressedLatex;
            pages = newPages;

        } catch (LatexCompileException ex) {
            log.warn("job={} project={} fit-page Tier 2 compile failed — escalating to Tier 3: {}",
                    jobId, project, ex.getMessage());
        } catch (Exception ex) {
            log.warn("job={} project={} fit-page Tier 2 LLM failed — escalating to Tier 3: {}",
                    jobId, project, ex.getMessage());
        }

//        // Tier 3: content trim — last resort
//        try {
//            log.info("job={} project={} fit-page Tier 3: content trim ({} pages)",
//                    jobId, project, pages);
//            String trimmedLatex = gemini.generateLatex(
//                    keyId,
//                    latexPrompts.latexSystemInstruction(),
//                    latexPrompts.contentTrimPrompt(latex, pages)
//            );
//            byte[] trimmedPdf = compiler.compile(trimmedLatex);
//            int newPages = countPdfPages(trimmedPdf);
//            log.info("job={} project={} fit-page Tier 3 result: {} page(s)",
//                    jobId, project, newPages);
//            return new FitPageResult(trimmedLatex, trimmedPdf);
//
//        } catch (LatexCompileException ex) {
//            log.warn("job={} project={} fit-page Tier 3 compile failed — returning best available: {}",
//                    jobId, project, ex.getMessage());
//        } catch (Exception ex) {
//            log.warn("job={} project={} fit-page Tier 3 LLM failed — returning best available: {}",
//                    jobId, project, ex.getMessage());
//        }

        log.warn("job={} project={} fit-page: all tiers exhausted — using pre-fit result",
                jobId, project);
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
                                  ResumeJob job,
                                  String keyId) {
        try {
            Map<String, Object> raw = gemini.generateJson(
                    keyId,
                    latexPrompts.plannerSystemInstruction(),
                    latexPrompts.resumePlannerPrompt(
                            resumeText,
                            job.getRoleLabel(),
                            job.getRoleCategory() != null ? job.getRoleCategory() : "",
                            job.getJdText()
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
                    null,
                    "You are an ATS scoring assistant. Return only JSON.",
                    latexPrompts.atsScorePrompt(originalText, shapedLatex, roleLabel)
            );

            ResumeJob job = jobRepository.findById(jobId).orElse(null);
            if (job == null) return;

            Object after = scores.get("atsScoreAfter");
            if (after instanceof Number n) job.setAtsScoreAfter(n.intValue());

            jobRepository.save(job);
            log.info("job={} project=async atsScoreAfter={} (LLM adversarial)",
                    jobId, job.getAtsScoreAfter());

        } catch (Exception ex) {
            log.warn("job={} project=async LLM ATS scoring failed (non-fatal): {}",
                    jobId, ex.getMessage());
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

        for (Map.Entry<String, Object> entry : result.entrySet()) {
            if (entry.getValue() instanceof String s
                    && s.contains("\\documentclass")
                    && s.length() > MIN_LATEX_FIX_LENGTH) {
                log.warn("Fix prompt returned LaTeX under unexpected key='{}' — using it anyway",
                        entry.getKey());
                return s;
            }
        }

        log.error("Fix prompt returned unrecognised keys: {} — full response: {}",
                result.keySet(),
                result.entrySet().stream()
                        .map(e -> e.getKey() + "=" +
                                (e.getValue() instanceof String s
                                        ? s.substring(0, Math.min(120, s.length())) + "…"
                                        : e.getValue()))
                        .toList());

        throw new AppException(
                "LLM did not return 'fixedLatex' or 'shapedLatex' in fix response. " +
                        "Keys received: " + result.keySet(),
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