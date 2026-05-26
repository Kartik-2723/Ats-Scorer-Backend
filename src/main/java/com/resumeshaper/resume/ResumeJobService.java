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
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
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

    private static final int MAX_RESUMES_PER_USER = 5;

    private final ResumeJobRepository     jobRepository;
    private final ResumeVersionRepository versionRepository;
    private final ResumeParserService     parserService;
    private final ResumeRendererService   rendererService;
    private final S3FileStorageService    storage;
    private final LLMOrchestrator         orchestrator;
    private final GuestSessionService     guestSessionService;

    // ── Upload + kick off pipeline ────────────────────────────

    @Transactional
    public ResumeJob upload(MultipartFile file,
                            ResumeDto.UploadRequest req,
                            User authenticatedUser) throws IOException {

        // Validate file type
        String filename = file.getOriginalFilename();
        if (filename == null ||
                (!filename.toLowerCase().endsWith(".pdf") &&
                        !filename.toLowerCase().endsWith(".docx"))) {
            throw new AppException("Only PDF and DOCX files are accepted", HttpStatus.BAD_REQUEST);
        }

        String guestToken = null;

        if (authenticatedUser != null) {
            // ── Priority 3: User resume limit check ──────────
            long count = jobRepository.countByUserId(authenticatedUser.getId());
            if (count >= MAX_RESUMES_PER_USER) {
                throw new AppException(
                        "Resume limit reached. Please delete an existing resume to upload a new one.",
                        HttpStatus.CONFLICT);
            }
        } else {
            // ── Priority 1: Guest cleanup on new upload ───────
            guestToken = req.guestToken();
            if (guestToken == null || !guestSessionService.exists(guestToken)) {
                throw new AppException("Valid guest token required", HttpStatus.UNAUTHORIZED);
            }
            cleanupPreviousGuestJobs(guestToken);
        }

        // Upload original to S3
        String s3Key = storage.upload(file, "originals");

        // Parse resume
        Map<String, Object> parsedResume = parserService.parse(file);

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

    // ── Priority 1: Delete previous guest jobs + S3 files ────

    private void cleanupPreviousGuestJobs(String guestToken) {
        List<ResumeJob> previous = jobRepository.findAllByGuestToken(guestToken);
        for (ResumeJob old : previous) {
            if (old.getOriginalFileKey() != null) storage.delete(old.getOriginalFileKey());
            if (old.getShapedFileKey()  != null) storage.delete(old.getShapedFileKey());
            jobRepository.delete(old); // versions cascade
            log.info("Cleaned up previous guest job={}", old.getId());
        }
    }

    // ── Priority 4: Delete user resume ───────────────────────

    @Transactional
    public void deleteResume(UUID jobId, UUID userId) {
        ResumeJob job = jobRepository.findByIdAndUserId(jobId, userId)
                .orElseThrow(() -> new AppException("Job not found", HttpStatus.NOT_FOUND));

        // Delete S3 files
        if (job.getOriginalFileKey() != null) storage.delete(job.getOriginalFileKey());
        if (job.getShapedFileKey()   != null) storage.delete(job.getShapedFileKey());

        // Delete all version S3 files
        versionRepository.findByJobIdOrderByVersionNumberDesc(jobId)
                .forEach(v -> {
                    if (v.getShapedFileKey() != null) storage.delete(v.getShapedFileKey());
                });

        // Delete DB row (versions cascade)
        jobRepository.delete(job);
        log.info("Deleted resume job={} for user={}", jobId, userId);
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
            default        -> "Unknown status";
        };
        return new ResumeDto.StatusResponse(job.getId(), job.getStatus(), step, job.getAtsScoreAfter());
    }

    // ── Get full result ───────────────────────────────────────

    @Transactional(readOnly = true)
    public ResumeDto.ResumeJobDetailDto getDetail(UUID jobId, String guestToken, User user) {
        ResumeJob job = findJob(jobId, guestToken, user);

        String downloadUrl = job.getShapedFileKey() != null
                ? storage.presignedUrl(job.getShapedFileKey()) : null;

        List<ResumeVersion> versions =
                versionRepository.findByJobIdOrderByVersionNumberDesc(jobId);
        List<ResumeDto.VersionSummaryDto> versionDtos = versions.stream()
                .map(v -> ResumeDto.VersionSummaryDto.from(v,
                        v.getShapedFileKey() != null
                                ? storage.presignedUrl(v.getShapedFileKey()) : null))
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

        // Priority 6: Delete old shaped S3 file before creating new one
        if (job.getShapedFileKey() != null) {
            storage.delete(job.getShapedFileKey());
        }

        // Apply edits
        job.setShapedResume(req.shapedResume());
        job.setStatus(JobStatus.SCORING);

        // Re-generate DOCX + upload
        byte[] docx = rendererService.render(req.shapedResume());
        String key = storage.uploadBytes(docx,
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

        // Priority 6: Delete old shaped S3 file before reshaping
        if (job.getShapedFileKey() != null) {
            storage.delete(job.getShapedFileKey());
            job.setShapedFileKey(null);
        }

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
        Specification<ResumeJob> spec = Specification
                .where(ResumeJobSpec.forUser(userId))
                .and(ResumeJobSpec.roleContains(search))
                .and(ResumeJobSpec.isStarred(starred));

        Pageable sortedPageable = PageRequest.of(
                pageable.getPageNumber(),
                pageable.getPageSize(),
                Sort.by("createdAt").descending()
        );

        return jobRepository.findAll(spec, sortedPageable)
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