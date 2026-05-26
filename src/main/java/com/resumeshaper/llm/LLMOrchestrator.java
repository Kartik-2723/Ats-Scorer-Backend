package com.resumeshaper.llm;

import com.resumeshaper.common.exception.GeminiQuotaExhaustedException;
import com.resumeshaper.resume.JobStatus;
import com.resumeshaper.resume.ResumeJob;
import com.resumeshaper.resume.ResumeJobRepository;
import com.resumeshaper.resume.ResumeRendererService;
import com.resumeshaper.storage.S3FileStorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class LLMOrchestrator {

    private final GeminiApiClient       gemini;
    private final PromptBuilder         prompts;
    private final ResumeJobRepository   jobRepository;
    private final ResumeRendererService rendererService;
    private final S3FileStorageService  storage;

    @Async("llmExecutor")
    public void runPipeline(ResumeJob job) {
        log.info("Starting LLM pipeline for job={}", job.getId());
        try {
            // ── Stage 1: JD Analysis ──────────────────────────
            updateStatus(job, JobStatus.ANALYZING);
            Map<String, Object> jdAnalysis;
            if (job.getJdText() != null && !job.getJdText().isBlank()) {
                jdAnalysis = gemini.generateJson(
                        prompts.systemInstruction(),
                        prompts.jdAnalysisPrompt(job.getJdText(), job.getRoleLabel())
                );
            } else {
                jdAnalysis = Map.of(
                        "requiredSkills", java.util.List.of(),
                        "keywords",       java.util.List.of(job.getRoleLabel()),
                        "tone",           "professional",
                        "seniority",      "mid"
                );
            }
            saveJdAnalysis(job, jdAnalysis);

            // ── Stage 2: Gap analysis + before score ──────────
            Map<String, Object> gapAnalysis = gemini.generateJson(
                    prompts.systemInstruction(),
                    prompts.gapAnalysisPrompt(job.getParsedResume(), jdAnalysis, job.getRoleLabel())
            );

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

            // ── Stage 4: ATS Score ────────────────────────────
            updateStatus(job, JobStatus.SCORING);
            Map<String, Object> atsReport = gemini.generateJson(
                    prompts.systemInstruction(),
                    prompts.atsScorePrompt(shapedResume, jdAnalysis)
            );
            int scoreAfter = ((Number) atsReport.getOrDefault("overallScore", 0)).intValue();

            // ── Stage 5: Render DOCX + upload to S3 ──────────
            String s3Key = null;
            try {
                byte[] docx = rendererService.render(shapedResume);
                s3Key = storage.uploadBytes(
                        docx,
                        "shaped/" + job.getId() + "/v1.docx",
                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
                );
                log.info("Rendered and uploaded DOCX for job={} → {}", job.getId(), s3Key);
            } catch (Exception ex) {
                log.warn("DOCX render failed for job={}, continuing without file: {}",
                        job.getId(), ex.getMessage());
            }

            finalise(job, atsReport, scoreAfter, s3Key);
            log.info("Pipeline DONE for job={} atsAfter={}", job.getId(), scoreAfter);

        } catch (GeminiQuotaExhaustedException ex) {
            log.warn("All Gemini models quota-exhausted for job={}", job.getId());
            cleanupS3OnFailure(job);
            markFailed(job, "QUOTA_EXHAUSTED");

        } catch (Exception ex) {
            log.error("Pipeline FAILED for job={}", job.getId(), ex);
            cleanupS3OnFailure(job);
            markFailed(job, ex.getMessage());
        }
    }

    // ── S3 cleanup on pipeline failure ────────────────────────
    //
    // Fix #2: Only delete the partial shaped output (if one was started).
    // The original upload is intentionally preserved so the user can retry
    // without re-uploading. GuestCleanupScheduler handles final deletion
    // of originals after 24 h for guest sessions.

    private void cleanupS3OnFailure(ResumeJob job) {
        try {
            if (job.getShapedFileKey() != null) {
                storage.delete(job.getShapedFileKey());
                log.info("Deleted partial shaped S3 file on failure for job={}", job.getId());
            }
        } catch (Exception ex) {
            log.warn("Failed to cleanup shaped S3 file on pipeline failure for job={}: {}",
                    job.getId(), ex.getMessage());
        }
    }

    @Transactional
    protected void updateStatus(ResumeJob job, JobStatus status) {
        job.setStatus(status);
        jobRepository.save(job);
    }

    @Transactional
    protected void saveJdAnalysis(ResumeJob job, Map<String, Object> jdAnalysis) {
        job.setJdAnalysis(jdAnalysis);
        jobRepository.save(job);
    }

    @Transactional
    protected void saveShapedResume(ResumeJob job, Map<String, Object> shaped, int scoreBefore) {
        job.setShapedResume(shaped);
        job.setAtsScoreBefore(scoreBefore);
        jobRepository.save(job);
    }

    @Transactional
    protected void finalise(ResumeJob job, Map<String, Object> atsReport,
                            int scoreAfter, String shapedFileKey) {
        job.setAtsReport(atsReport);
        job.setAtsScoreAfter(scoreAfter);
        job.setShapedFileKey(shapedFileKey);
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