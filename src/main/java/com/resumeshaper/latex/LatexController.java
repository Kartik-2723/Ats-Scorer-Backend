package com.resumeshaper.latex;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/api/latex")
@RequiredArgsConstructor
public class LatexController {

    private final LatexCompilerService compilerService;

    /**
     * POST /api/latex/compile
     *
     * Accepts a JSON body:  { "latex": "\\documentclass{article}..." }
     * Returns:              application/pdf bytes
     *
     * Accessible by both guests and authenticated users.
     * principal is null for unauthenticated requests.
     */
    @PostMapping("/compile")
    public ResponseEntity<byte[]> compile(
            @Valid @RequestBody LatexRequest request,
            @AuthenticationPrincipal UserDetails principal
    ) {
        String user = (principal != null) ? principal.getUsername() : "guest";
        log.info("LaTeX compile request from [{}], source length={}", user, request.getLatex().length());

        byte[] pdf = compilerService.compile(request.getLatex());

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_PDF);
        headers.setContentLength(pdf.length);
        // inline so the browser renders it directly; change to ATTACHMENT to force download
        headers.setContentDispositionFormData("inline", "document.pdf");

        return new ResponseEntity<>(pdf, headers, HttpStatus.OK);
    }

    // ── Exception handler ─────────────────────────────────────────────────────

    @ExceptionHandler(LatexCompileException.class)
    public ResponseEntity<LatexErrorResponse> handleCompileError(LatexCompileException ex) {
        log.warn("LaTeX compilation failed: {}", ex.getMessage());
        return ResponseEntity
                .status(HttpStatus.UNPROCESSABLE_ENTITY)   // 422 — request was valid JSON but LaTeX was bad
                .body(new LatexErrorResponse(ex.getMessage(), ex.getCompilerLog()));
    }
}