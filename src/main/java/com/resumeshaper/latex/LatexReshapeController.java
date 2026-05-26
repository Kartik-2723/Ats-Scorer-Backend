package com.resumeshaper.latex;

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
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Endpoints for the LaTeX reshape pipeline.
 *
 * POST /api/latex/reshape              — submit PDF or .tex file
 * GET  /api/latex/reshape/{id}/status  — poll until DONE | FAILED
 * GET  /api/latex/reshape/{id}/result  — fetch shapedLatex + pdfUrl when DONE
 */
@Slf4j
@RestController
@RequestMapping("/api/latex/reshape")
@RequiredArgsConstructor
public class LatexReshapeController {

    private static final List<String> ALLOWED_PDF_TYPES = List.of(
            "application/pdf", "application/x-pdf");
    private static final List<String> ALLOWED_TEX_TYPES = List.of(
            "text/plain", "application/x-tex", "text/x-tex",
            "application/octet-stream");   // browsers often send .tex as octet-stream
    private static final long MAX_FILE_BYTES = 10 * 1024 * 1024; // 10 MB

    private final LatexReshapeOrchestrator orchestrator;
    private final ResumeJobRepository      jobRepository;
    private final GuestSessionService      guestSessionService;
    private final S3FileStorageService     storage;

    // ─────────────────────────────────────────────────────────────────────────
    // POST /api/latex/reshape
    // Accepts multipart: file (PDF or .tex) + metadata fields
    // ─────────────────────────────────────────────────────────────────────────

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<Map<String, Object>>> submit(
            @RequestParam("file")                                    MultipartFile file,
            @RequestParam("roleLabel")                               String roleLabel,
            @RequestParam(value = "roleCategory",  required = false) String roleCategory,
            @RequestParam(value = "customRole",    defaultValue = "false") boolean customRole,
            @RequestParam(value = "jdText",        required = false) String jdText,
            @RequestParam(value = "guestToken",    required = false) String guestToken,
            @AuthenticationPrincipal User user
    ) throws IOException {

        // ── Auth check ────────────────────────────────────────────────────────
        validateOwner(guestToken, user);

        // ── File validation ───────────────────────────────────────────────────
        if (file.isEmpty()) {
            throw new AppException("Uploaded file is empty", HttpStatus.BAD_REQUEST);
        }
        if (file.getSize() > MAX_FILE_BYTES) {
            throw new AppException("File exceeds 10 MB limit", HttpStatus.BAD_REQUEST);
        }

        InputType inputType = detectInputType(file);
        log.info("LaTeX reshape submit: inputType={} role={} user/guest={}",
                inputType, roleLabel, user != null ? user.getEmail() : guestToken);

        // ── Build request ─────────────────────────────────────────────────────
        LatexReshapeRequest req = new LatexReshapeRequest();
        req.setRoleLabel(roleLabel);
        req.setRoleCategory(roleCategory);
        req.setCustomRole(customRole);
        req.setJdText(jdText);
        req.setGuestToken(guestToken);

        // ── Create job + kick off async pipeline ──────────────────────────────
        ResumeJob job = orchestrator.submit(file, inputType, req, user);

        return ResponseEntity.accepted().body(ApiResponse.success(Map.of(
                "jobId",  job.getId(),
                "status", job.getStatus()
        )));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // GET /api/latex/reshape/{jobId}/status
    // Lightweight poll endpoint — frontend hits this until DONE | FAILED
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
    // Returns full result — only call when status == DONE
    // ─────────────────────────────────────────────────────────────────────────

    @GetMapping("/{jobId}/result")
    public ResponseEntity<ApiResponse<LatexReshapeResponse>> result(
            @PathVariable UUID jobId,
            @RequestParam(required = false) String guestToken,
            @AuthenticationPrincipal User user
    ) {
        ResumeJob job = findAndAuthorize(jobId, guestToken, user);

        if (job.getStatus() != JobStatus.DONE) {
            throw new AppException(
                    "Job is not complete yet. Current status: " + job.getStatus(),
                    HttpStatus.CONFLICT);
        }

        // Generate pre-signed PDF URL (60 min TTL, from AppProperties)
        String pdfUrl = storage.presignedUrl(job.getCompiledPdfKey());

        // Parse changesLog from shapedResume JSONB if present, else empty list
        @SuppressWarnings("unchecked")
        List<String> changesLog = job.getShapedResume() != null
                ? (List<String>) job.getShapedResume().getOrDefault("changesLog", List.of())
                : List.of();

        LatexReshapeResponse response = LatexReshapeResponse.builder()
                .jobId(job.getId())
                .shapedLatex(job.getShapedLatex())
                .pdfUrl(pdfUrl)
                .atsScoreBefore(job.getAtsScoreBefore())
                .atsScoreAfter(job.getAtsScoreAfter())
                .changesLog(changesLog)
                .compileAttempts(job.getLatexCompileAttempts())
                .build();

        return ResponseEntity.ok(ApiResponse.success(response));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Exception handler — compile errors
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

    private InputType detectInputType(MultipartFile file) {
        String originalName = file.getOriginalFilename();
        String contentType  = file.getContentType();

        // Prefer filename extension — more reliable than browser MIME
        if (originalName != null) {
            String lower = originalName.toLowerCase();
            if (lower.endsWith(".tex") || lower.endsWith(".latex")) return InputType.LATEX;
            if (lower.endsWith(".pdf"))                              return InputType.PDF;
        }

        // Fall back to content type
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
            guestSessionService.validate(guestToken);  // throws on invalid/expired
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