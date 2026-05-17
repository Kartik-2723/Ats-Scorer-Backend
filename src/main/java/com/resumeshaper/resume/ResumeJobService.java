package com.resumeshaper.resume;

import com.resumeshaper.common.exception.AppException;
import com.resumeshaper.llm.LLMOrchestrator;
import com.resumeshaper.resume.dto.ResumeDto;
import com.resumeshaper.session.GuestSessionService;
import com.resumeshaper.storage.S3FileStorageService;
import com.resumeshaper.user.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class ResumeJobService {

    private final ResumeJobRepository        jobRepository;
    private final ResumeVersionRepository    versionRepository;
    private final ResumeParserService        parserService;
    private final ResumeRendererService      rendererService;
    private final S3FileStorageService       storage;
    private final LLMOrchestrator            orchestrator;
    private final GuestSessionService        guestSessionService;

    // ── Upload + kick off pipeline ────────────────────────────

    @Transactional
    public ResumeJob upload(MultipartFile file,
                             ResumeDto.UploadRequest req,
                             User authenticatedUser) throws IOException {

        // Validate file type
        String filename = file.getOriginalFilename();
        if (filename == null ||
            (!filename.toLowerCase().endsWith(".pdf") && !filename.toLowerCase().endsWith(".docx"))) {
            throw new AppException("Only PDF and DOCX files are accepted", HttpStatus.BAD_REQUEST);
        }

        // Upload original to S3
        String s3Key = storage.upload(file, "originals");

        // Parse resume
        Map<String, Object> parsedResume = parserService.parse(file);

        // Validate guest token (if guest)
        String guestToken = null;
        if (authenticatedUser == null) {
            guestToken = req.guestToken();
            if (guestToken == null || !guestSessionService.exists(guestToken)) {
                throw new AppException("Valid guest token required", HttpStatus.UNAUTHORIZED);
            }
        }

        // Create job
        ResumeJob job = ResumeJob.builder()
                .user(authenticatedUser)
                .guestToken(guestToken)
                .roleLabel(req.roleLabel())
                .roleCategory(req.roleCategory())
                .customRole(req.customRole())
                .jdText(req.jdText())
                .originalFileKey(s3Key)
                .originalFileName(filename)
                .parsedResume(parsedResume)
                .status(JobStatus.PENDING)
                .build();

        job = jobRepository.save(job);

        // Kick off async pipeline
        orchestrator.runPipeline(job);
        log.info("Created job={} for role={}", job.getId(), req.roleLabel());
        return job;
    }

    // ── Status polling ────────────────────────────────────────

    @Transactional(readOnly = true)
    public ResumeDto.StatusResponse getStatus(UUID jobId, String guestToken, User user) {
        ResumeJob job = findJob(jobId, guestToken, user);
        String step = switch (job.getStatus()) {
            case PENDING   -> "Queued...";
            case PARSING   -> "Parsing your resume";
            case ANALYZING -> "Analyzing job description";
            case RESHAPING -> "Reshaping with AI";
            case SCORING   -> "Calculating ATS score";
            case DONE      -> "Done!";
            case FAILED    -> "Processing failed";
        };
        return new ResumeDto.StatusResponse(job.getId(), job.getStatus(), step, job.getAtsScoreAfter());
    }

    // ── Get full result ───────────────────────────────────────

    @Transactional(readOnly = true)
    public ResumeDto.ResumeJobDetailDto getDetail(UUID jobId, String guestToken, User user) {
        ResumeJob job = findJob(jobId, guestToken, user);

        String downloadUrl = job.getShapedFileKey() != null
                ? storage.presignedUrl(job.getShapedFileKey()) : null;

        List<ResumeVersion> versions = versionRepository.findByJobIdOrderByVersionNumberDesc(jobId);
        List<ResumeDto.VersionSummaryDto> versionDtos = versions.stream()
                .map(v -> ResumeDto.VersionSummaryDto.from(v,
                        v.getShapedFileKey() != null ? storage.presignedUrl(v.getShapedFileKey()) : null))
                .toList();

        return ResumeDto.ResumeJobDetailDto.from(job, downloadUrl, versionDtos);
    }

    // ── Apply manual block edits + re-score ──────────────────

    @Transactional
    public ResumeJob applyEdits(ResumeDto.BlockEditRequest req, User user) throws IOException {
        UUID jobId = req.jobId();
        ResumeJob job = findJob(jobId, req.guestToken(), user);

        if (job.getStatus() != JobStatus.DONE && job.getStatus() != JobStatus.FAILED) {
            throw new AppException("Job must be in DONE state before editing", HttpStatus.CONFLICT);
        }

        // Save previous version
        saveVersion(job);

        // Apply edits
        job.setShapedResume(req.shapedResume());
        job.setStatus(JobStatus.SCORING);

        // Re-generate DOCX + upload
        byte[] docx = rendererService.render(req.shapedResume());
        String key  = storage.uploadBytes(docx,
                "shaped/" + jobId + "/v" + versionRepository.countByJobId(jobId) + ".docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document");
        job.setShapedFileKey(key);
        job.setStatus(JobStatus.DONE);

        return jobRepository.save(job);
    }

    // ── Re-shape (run full pipeline again) ───────────────────

    @Transactional
    public ResumeJob reshape(UUID jobId, String guestToken, User user) {
        ResumeJob job = findJob(jobId, guestToken, user);
        saveVersion(job);
        job.setStatus(JobStatus.PENDING);
        job = jobRepository.save(job);
        orchestrator.runPipeline(job);
        return job;
    }

    // ── Download presigned URL ────────────────────────────────

    public String getDownloadUrl(UUID jobId, String guestToken, User user) {
        ResumeJob job = findJob(jobId, guestToken, user);
        if (job.getShapedFileKey() == null) {
            throw new AppException("Shaped resume not yet ready", HttpStatus.ACCEPTED);
        }
        return storage.presignedUrl(job.getShapedFileKey());
    }

    // ── Dashboard queries ─────────────────────────────────────

    @Transactional(readOnly = true)
    public Page<ResumeDto.ResumeJobSummaryDto> findByUser(UUID userId, String search,
                                                           Boolean starred, Pageable pageable) {
        return jobRepository.findByUserId(userId, search, starred, pageable)
                .map(ResumeDto.ResumeJobSummaryDto::from);
    }

    @Transactional
    public boolean toggleStar(UUID userId, String jobIdStr) {
        UUID jobId = UUID.fromString(jobIdStr);
        ResumeJob job = jobRepository.findByIdAndUserId(jobId, userId)
                .orElseThrow(() -> new AppException("Job not found", HttpStatus.NOT_FOUND));
        job.setStarred(!job.isStarred());
        jobRepository.save(job);
        return job.isStarred();
    }

    // ── Helpers ───────────────────────────────────────────────

    private ResumeJob findJob(UUID jobId, String guestToken, User user) {
        if (user != null) {
            return jobRepository.findByIdAndUserId(jobId, user.getId())
                    .orElseThrow(() -> new AppException("Job not found", HttpStatus.NOT_FOUND));
        }
        if (guestToken != null) {
            return jobRepository.findByIdAndGuestToken(jobId, guestToken)
                    .orElseThrow(() -> new AppException("Job not found", HttpStatus.NOT_FOUND));
        }
        throw new AppException("Authentication required", HttpStatus.UNAUTHORIZED);
    }

    private void saveVersion(ResumeJob job) {
        if (job.getShapedResume() == null) return;
        int nextVersion = versionRepository.countByJobId(job.getId()) + 1;
        ResumeVersion version = ResumeVersion.builder()
                .job(job)
                .versionNumber(nextVersion)
                .shapedResume(job.getShapedResume())
                .atsScore(job.getAtsScoreAfter())
                .shapedFileKey(job.getShapedFileKey())
                .build();
        versionRepository.save(version);
    }
}
