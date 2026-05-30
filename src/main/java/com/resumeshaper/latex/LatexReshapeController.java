package com.resumeshaper.latex;

import com.resumeshaper.ats.ATSReport;
import com.resumeshaper.common.dto.ApiResponse;
import com.resumeshaper.common.exception.AppException;
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
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/latex/reshape")
@RequiredArgsConstructor
public class LatexReshapeController {

    private static final List<String> ALLOWED_PDF_TYPES = List.of(
            "application/pdf", "application/x-pdf");
    private static final List<String> ALLOWED_TEX_TYPES = List.of(
            "text/plain", "application/x-tex", "text/x-tex",
            "application/octet-stream");
    private static final long MAX_FILE_BYTES = 10 * 1024 * 1024;

    private final LatexReshapeOrchestrator orchestrator;
    private final ResumeJobRepository      jobRepository;
    private final GuestSessionService      guestSessionService;
    private final S3FileStorageService     storage;

    // ─────────────────────────────────────────────────────────────────────────
    // POST /api/latex/reshape
    // ─────────────────────────────────────────────────────────────────────────

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<Map<String, Object>>> submit(
            @RequestParam("file")                                         MultipartFile file,
            @RequestParam("roleLabel")                                    String roleLabel,
            @RequestParam(value = "roleCategory",  required = false)      String roleCategory,
            @RequestParam(value = "customRole",    defaultValue = "false") boolean customRole,
            @RequestParam(value = "jdText",        required = false)       String jdText,
            @RequestParam(value = "guestToken",    required = false)       String guestToken,
            @AuthenticationPrincipal User user
    ) throws IOException {

        validateOwner(guestToken, user);

        if (file.isEmpty()) {
            throw new AppException("Uploaded file is empty", HttpStatus.BAD_REQUEST);
        }
        if (file.getSize() > MAX_FILE_BYTES) {
            throw new AppException("File exceeds 10 MB limit", HttpStatus.BAD_REQUEST);
        }

        InputType inputType = detectInputType(file);
        log.info("LaTeX reshape submit: inputType={} role={} user/guest={}",
                inputType, roleLabel, user != null ? user.getEmail() : guestToken);

        LatexReshapeRequest req = new LatexReshapeRequest();
        req.setRoleLabel(roleLabel);
        req.setRoleCategory(roleCategory);
        req.setCustomRole(customRole);
        req.setJdText(jdText);
        req.setGuestToken(guestToken);

        ResumeJob job = orchestrator.submit(file, inputType, req, user);

        return ResponseEntity.accepted().body(ApiResponse.success(Map.of(
                "jobId",  job.getId(),
                "status", job.getStatus()
        )));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // POST /api/latex/reshape/{jobId}/reshape  — retry existing job
    // Resets the job to PENDING and re-enqueues the pipeline.
    // The original file is already on S3 — no re-upload needed.
    // ─────────────────────────────────────────────────────────────────────────

    @PostMapping("/{jobId}/reshape")
    public ResponseEntity<ApiResponse<Map<String, Object>>> retrigger(
            @PathVariable UUID jobId,
            @RequestParam(required = false) String guestToken,
            @AuthenticationPrincipal User user
    ) {
        validateOwner(guestToken, user);

        ResumeJob job = findAndAuthorize(jobId, guestToken, user);

        if (job.getOriginalFileKey() == null || job.getOriginalFileKey().isBlank()) {
            throw new AppException(
                    "Cannot retry — original file no longer available on storage.",
                    HttpStatus.UNPROCESSABLE_ENTITY);
        }

        log.info("LaTeX reshape retrigger: jobId={} user/guest={}",
                jobId, user != null ? user.getEmail() : guestToken);

        orchestrator.retrigger(job);

        return ResponseEntity.accepted().body(ApiResponse.success(Map.of(
                "jobId",  job.getId(),
                "status", JobStatus.PENDING
        )));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // GET /api/latex/reshape/{jobId}/status
    // ─────────────────────────────────────────────────────────────────────────

    @GetMapping("/{jobId}/status")
    public ResponseEntity<ApiResponse<Map<String, Object>>> status(
            @PathVariable UUID jobId,
            @RequestParam(required = false) String guestToken,
            @AuthenticationPrincipal User user
    ) {
        ResumeJob job = findAndAuthorize(jobId, guestToken, user);

        Map<String, Object> body = job.getStatus() == JobStatus.FAILED
                ? Map.of(
                "jobId",        job.getId(),
                "status",       job.getStatus(),
                "errorMessage", nullSafe(job.getErrorMessage()))
                : Map.of(
                "jobId",  job.getId(),
                "status", job.getStatus());

        return ResponseEntity.ok(ApiResponse.success(body));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // GET /api/latex/reshape/{jobId}/result
    // ─────────────────────────────────────────────────────────────────────────

    @GetMapping("/{jobId}/result")
    public ResponseEntity<ApiResponse<LatexReshapeResponse>> result(
            @PathVariable UUID jobId,
            @RequestParam(required = false) String guestToken,
            @AuthenticationPrincipal User user
    ) {
        ResumeJob job = findAndAuthorize(jobId, guestToken, user);

        if (job.getStatus() != JobStatus.DONE) {
            return ResponseEntity.status(HttpStatus.ACCEPTED)
                    .body(ApiResponse.success((LatexReshapeResponse) Map.of(
                            "status",  job.getStatus(),
                            "message", "Job not complete yet"
                    )));
        }

        String pdfUrl = storage.presignedUrl(job.getCompiledPdfKey());

        @SuppressWarnings("unchecked")
        List<String> changesLog = job.getShapedResume() != null
                ? (List<String>) job.getShapedResume().getOrDefault("changesLog", List.of())
                : List.of();

        ATSReport atsReport = buildAtsReportFromJob(job);

        LatexReshapeResponse response = LatexReshapeResponse.builder()
                .jobId(job.getId())
                .shapedLatex(job.getShapedLatex())
                .pdfUrl(pdfUrl)
                .atsScoreBefore(job.getAtsScoreBefore())
                .atsScoreAfter(job.getAtsScoreAfter())
                .changesLog(changesLog)
                .compileAttempts(job.getLatexCompileAttempts())
                .profileTypeDetected(job.getProfileTypeDetected())
                .atsGapKeywords(job.getAtsGapKeywords() != null
                        ? job.getAtsGapKeywords() : List.of())
                .contentFlags(job.getContentFlags() != null
                        ? job.getContentFlags() : List.of())
                .atsReport(atsReport)
                .build();

        return ResponseEntity.ok(ApiResponse.success(response));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Exception handler
    // ─────────────────────────────────────────────────────────────────────────

    @ExceptionHandler(LatexCompileException.class)
    public ResponseEntity<LatexErrorResponse> handleCompileError(LatexCompileException ex) {
        log.warn("LaTeX compilation failed: {}", ex.getMessage());
        return ResponseEntity
                .status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(new LatexErrorResponse(ex.getMessage(), ex.getCompilerLog()));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private ATSReport buildAtsReportFromJob(ResumeJob job) {
        Map<String, Object> raw = job.getAtsReport();
        if (raw == null || raw.isEmpty()) return null;

        try {
            return ATSReport.builder()
                    .overallScore(toInt(raw.get("overallScore")))
                    .keywordScore(toInt(raw.get("keywordScore")))
                    .sectionScore(toInt(raw.get("sectionScore")))
                    .formatScore(toInt(raw.get("formatScore")))
                    .verbScore(toInt(raw.get("verbScore")))
                    .matchedKeywords(toStringList(raw.get("matchedKeywords")))
                    .missingKeywords(toStringList(raw.get("missingKeywords")))
                    .presentSections(toStringList(raw.get("presentSections")))
                    .formatIssues(toStringList(raw.get("formatIssues")))
                    .build();
        } catch (Exception ex) {
            log.warn("job={} failed to reconstruct ATSReport from JSONB: {}",
                    job.getId(), ex.getMessage());
            return null;
        }
    }

    private int toInt(Object v) {
        if (v instanceof Number n) return n.intValue();
        return 0;
    }

    @SuppressWarnings("unchecked")
    private List<String> toStringList(Object v) {
        if (v instanceof List<?> list) {
            return list.stream()
                    .filter(i -> i instanceof String)
                    .map(i -> (String) i)
                    .toList();
        }
        return List.of();
    }

    private InputType detectInputType(MultipartFile file) {
        String originalName = file.getOriginalFilename();
        String contentType  = file.getContentType();

        if (originalName != null) {
            String lower = originalName.toLowerCase();
            if (lower.endsWith(".tex") || lower.endsWith(".latex")) return InputType.LATEX;
            if (lower.endsWith(".pdf"))                              return InputType.PDF;
        }

        if (contentType != null) {
            if (ALLOWED_PDF_TYPES.stream().anyMatch(contentType::startsWith)) return InputType.PDF;
            if (ALLOWED_TEX_TYPES.stream().anyMatch(contentType::startsWith)) return InputType.LATEX;
        }

        throw new AppException(
                "Unsupported file type. Upload a PDF (.pdf) or LaTeX (.tex) file.",
                HttpStatus.BAD_REQUEST);
    }

    private void validateOwner(String guestToken, User user) {
        if (user == null && (guestToken == null || guestToken.isBlank())) {
            throw new AppException(
                    "Authentication required — provide JWT or guestToken",
                    HttpStatus.UNAUTHORIZED);
        }
        if (user == null) {
            guestSessionService.validate(guestToken);
        }
    }

    private ResumeJob findAndAuthorize(UUID jobId, String guestToken, User user) {
        ResumeJob job = jobRepository.findById(jobId)
                .orElseThrow(() -> new AppException("Job not found", HttpStatus.NOT_FOUND));

        boolean isOwner = (user != null && job.getUser() != null
                && job.getUser().getId().equals(user.getId()))
                || (guestToken != null && guestToken.equals(job.getGuestToken()));

        if (!isOwner) {
            throw new AppException("Access denied", HttpStatus.FORBIDDEN);
        }
        return job;
    }

    private String nullSafe(String s) {
        return s != null ? s : "";
    }
}