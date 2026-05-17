package com.resumeshaper.session;

import com.resumeshaper.common.dto.ApiResponse;
import com.resumeshaper.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/guest")
@RequiredArgsConstructor
public class GuestSessionController {

    private final GuestSessionService guestSessionService;

    /** POST /api/guest/session  – issue a new guest token */
    @PostMapping("/session")
    public ResponseEntity<ApiResponse<Map<String, String>>> create() {
        GuestSession session = guestSessionService.create();
        return ResponseEntity.ok(ApiResponse.success(Map.of(
                "guestToken", session.getToken(),
                "expiresAt",  session.getExpiresAt().toString()
        )));
    }

    /**
     * POST /api/guest/claim
     * Body: { "guestToken": "..." }
     * Requires authenticated user (just signed up via OAuth2).
     * Links all guest resume jobs to the new account.
     */
    @PostMapping("/claim")
    public ResponseEntity<ApiResponse<String>> claim(
            @AuthenticationPrincipal User user,
            @RequestBody Map<String, String> body) {

        String guestToken = body.get("guestToken");
        guestSessionService.claimSession(guestToken, user);
        return ResponseEntity.ok(ApiResponse.success("Session claimed successfully"));
    }
}
