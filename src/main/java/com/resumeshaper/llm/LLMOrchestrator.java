package com.resumeshaper.llm;

import com.resumeshaper.resume.JobStatus;
import com.resumeshaper.resume.ResumeJob;
import com.resumeshaper.resume.ResumeJobRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

/**
 * Runs the 5-stage AI pipeline asynchronously.
 * Each stage persists progress to the DB so the client can poll /status.
 *
 * Pipeline:
 *  1. PARSING    → parse resume → parsedResume JSONB
 *  2. ANALYZING  → JD analysis + gap analysis
 *  3. RESHAPING  → Gemini rewrites the resume
 *  4. SCORING    → before + after ATS score
 *  5. DONE       → save shaped file to S3
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LLMOrchestrator {

    private final GeminiApiClient gemini;
    private final PromptBuilder prompts;
    private final ResumeJobRepository jobRepository;

    @Async("llmExecutor")
    public void runPipeline(ResumeJob job) {
        log.info("Starting LLM pipeline for job={}", job.getId());
        try {
            // ── Stage 1: JD Analysis (if JD provided) ────────
            updateStatus(job, JobStatus.ANALYZING);
            Map<String, Object> jdAnalysis;
            if (job.getJdText() != null && !job.getJdText().isBlank()) {
                jdAnalysis = gemini.generateJson(
                        prompts.systemInstruction(),
                        prompts.jdAnalysisPrompt(job.getJdText(), job.getRoleLabel())
                );
            } else {
                // No JD → use role label only (minimal fallback)
                jdAnalysis = Map.of(
                        "requiredSkills", java.util.List.of(),
                        "keywords",       java.util.List.of(job.getRoleLabel()),
                        "tone",           "professional",
                        "seniority",      "mid"
                );
            }
            saveField(job, "jdAnalysis", jdAnalysis);

            // ── Stage 2: Gap analysis ─────────────────────────
            Map<String, Object> gapAnalysis = gemini.generateJson(
                    prompts.systemInstruction(),
                    prompts.gapAnalysisPrompt(job.getParsedResume(), jdAnalysis, job.getRoleLabel())
            );

            // Score BEFORE reshape
            Map<String, Object> beforeScore = gemini.generateJson(
                    prompts.systemInstruction(),
                    prompts.originalScorePrompt(job.getParsedResume(), jdAnalysis)
            );
            int scoreBefore = ((Number) beforeScore.getOrDefault("overallScore", 40)).intValue();

            // ── Stage 3: Reshape ──────────────────────────────
            updateStatus(job, JobStatus.RESHAPING);
            Map<String, Object> shapedResume = gemini.generateJson(
                    prompts.systemInstruction(),
                    prompts.reshapePrompt(job.getParsedResume(), jdAnalysis, gapAnalysis, job.getRoleLabel())
            );
            saveShapedResume(job, shapedResume, scoreBefore);

            // ── Stage 4: ATS Score after ──────────────────────
            updateStatus(job, JobStatus.SCORING);
            Map<String, Object> atsReport = gemini.generateJson(
                    prompts.systemInstruction(),
                    prompts.atsScorePrompt(shapedResume, jdAnalysis)
            );
            int scoreAfter = ((Number) atsReport.getOrDefault("overallScore", 0)).intValue();

            finalise(job, atsReport, scoreAfter);
            log.info("Pipeline DONE for job={} atsAfter={}", job.getId(), scoreAfter);

        } catch (Exception ex) {
            log.error("Pipeline FAILED for job={}", job.getId(), ex);
            markFailed(job, ex.getMessage());
        }
    }

    // ── DB helpers ───────────────────────────────────────────

    @Transactional
    protected void updateStatus(ResumeJob job, JobStatus status) {
        job.setStatus(status);
        jobRepository.save(job);
    }

    @Transactional
    protected void saveField(ResumeJob job, String field, Map<String, Object> value) {
        switch (field) {
            case "jdAnalysis"    -> job.setJdAnalysis(value);
            case "shapedResume"  -> job.setShapedResume(value);
        }
        jobRepository.save(job);
    }

    @Transactional
    protected void saveShapedResume(ResumeJob job, Map<String, Object> shaped, int scoreBefore) {
        job.setShapedResume(shaped);
        job.setAtsScoreBefore(scoreBefore);
        jobRepository.save(job);
    }

    @Transactional
    protected void finalise(ResumeJob job, Map<String, Object> atsReport, int scoreAfter) {
        job.setAtsReport(atsReport);
        job.setAtsScoreAfter(scoreAfter);
        job.setStatus(JobStatus.DONE);
        jobRepository.save(job);
    }

    @Transactional
    protected void markFailed(ResumeJob job, String msg) {
        job.setStatus(JobStatus.FAILED);
        job.setErrorMessage(msg);
        jobRepository.save(job);
    }
}
