package com.resumeshaper.jd;

import com.resumeshaper.common.dto.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/jd")
@RequiredArgsConstructor
public class JDController {

    private final JDAnalyzerService analyzerService;

    /**
     * POST /api/jd/analyze
     * Body: { "jdText": "...", "roleLabel": "..." }
     * Returns structured JD analysis (public, no auth required).
     */
    @PostMapping("/analyze")
    public ResponseEntity<ApiResponse<Map<String, Object>>> analyze(
            @RequestBody Map<String, String> body) {

        String jdText   = body.get("jdText");
        String roleLabel = body.getOrDefault("roleLabel", "Software Engineer");

        if (jdText == null || jdText.isBlank()) {
            return ResponseEntity.badRequest()
                    .body(ApiResponse.error("jdText is required"));
        }

        Map<String, Object> result = analyzerService.analyze(jdText, roleLabel);
        return ResponseEntity.ok(ApiResponse.success(result));
    }
}
