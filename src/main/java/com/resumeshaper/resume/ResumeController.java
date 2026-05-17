package com.resumeshaper.resume;

import com.resumeshaper.common.dto.ApiResponse;
import com.resumeshaper.resume.dto.ResumeDto;
import com.resumeshaper.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/resume")
@RequiredArgsConstructor
public class ResumeController {

    private final ResumeJobService resumeJobService;

    /**
     * POST /api/resume/upload
     * Multipart: file + JSON metadata
     * Works for both guest (X-Guest-Token header) and authenticated users.
     */
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<Map<String, Object>>> upload(
            @RequestParam("file")        MultipartFile file,
            @RequestParam("roleLabel")   String roleLabel,
            @RequestParam(value = "roleCategory",  required = false) String roleCategory,
            @RequestParam(value = "customRole",    defaultValue = "false") boolean customRole,
            @RequestParam(value = "jdText",        required = false) String jdText,
            @RequestParam(value = "guestToken",    required = false) String guestToken,
            @AuthenticationPrincipal User user) throws IOException {

        ResumeDto.UploadRequest req = new ResumeDto.UploadRequest(
                roleLabel, roleCategory, customRole, jdText, guestToken);

        ResumeJob job = resumeJobService.upload(file, req, user);

        return ResponseEntity.accepted().body(ApiResponse.success(Map.of(
                "jobId",  job.getId(),
                "status", job.getStatus()
        )));
    }

    /**
     * GET /api/resume/status/{jobId}
     * Poll until status == DONE or FAILED
     */
    @GetMapping("/status/{jobId}")
    public ResponseEntity<ApiResponse<ResumeDto.StatusResponse>> status(
            @PathVariable UUID jobId,
            @RequestParam(required = false) String guestToken,
            @AuthenticationPrincipal User user) {

        ResumeDto.StatusResponse status = resumeJobService.getStatus(jobId, guestToken, user);
        return ResponseEntity.ok(ApiResponse.success(status));
    }

    /**
     * GET /api/resume/{jobId}
     * Full result with before/after scores, shaped resume JSON, versions
     */
    @GetMapping("/{jobId}")
    public ResponseEntity<ApiResponse<ResumeDto.ResumeJobDetailDto>> detail(
            @PathVariable UUID jobId,
            @RequestParam(required = false) String guestToken,
            @AuthenticationPrincipal User user) {

        return ResponseEntity.ok(ApiResponse.success(
                resumeJobService.getDetail(jobId, guestToken, user)));
    }

    /**
     * GET /api/resume/download/{jobId}
     * Returns a pre-signed S3 URL for the shaped DOCX
     */
    @GetMapping("/download/{jobId}")
    public ResponseEntity<ApiResponse<Map<String, String>>> download(
            @PathVariable UUID jobId,
            @RequestParam(required = false) String guestToken,
            @AuthenticationPrincipal User user) {

        String url = resumeJobService.getDownloadUrl(jobId, guestToken, user);
        return ResponseEntity.ok(ApiResponse.success(Map.of("downloadUrl", url)));
    }

    /**
     * POST /api/resume/edit
     * Apply manual block edits from the editor screen
     */
    @PostMapping("/edit")
    public ResponseEntity<ApiResponse<Map<String, Object>>> edit(
            @RequestBody ResumeDto.BlockEditRequest req,
            @AuthenticationPrincipal User user) throws IOException {

        ResumeJob job = resumeJobService.applyEdits(req, user);
        return ResponseEntity.ok(ApiResponse.success(Map.of(
                "jobId",         job.getId(),
                "atsScoreAfter", job.getAtsScoreAfter(),
                "status",        job.getStatus()
        )));
    }

    /**
     * POST /api/resume/{jobId}/reshape
     * Re-run the full AI pipeline on an existing job
     */
    @PostMapping("/{jobId}/reshape")
    public ResponseEntity<ApiResponse<Map<String, Object>>> reshape(
            @PathVariable UUID jobId,
            @RequestParam(required = false) String guestToken,
            @AuthenticationPrincipal User user) {

        ResumeJob job = resumeJobService.reshape(jobId, guestToken, user);
        return ResponseEntity.accepted().body(ApiResponse.success(Map.of(
                "jobId",  job.getId(),
                "status", job.getStatus()
        )));
    }
}
