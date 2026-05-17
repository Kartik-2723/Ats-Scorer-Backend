package com.resumeshaper.auth;

import com.resumeshaper.common.dto.ApiResponse;
import com.resumeshaper.user.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final JwtTokenProvider tokenProvider;
    private final UserService userService;

    /**
     * POST /api/auth/refresh
     * Body: { "refreshToken": "..." }
     * Returns new access token.
     */
    @PostMapping("/refresh")
    public ResponseEntity<ApiResponse<Map<String, String>>> refresh(
            @RequestBody Map<String, String> body) {

        String refreshToken = body.get("refreshToken");
        if (refreshToken == null || !tokenProvider.validateToken(refreshToken)) {
            return ResponseEntity.status(401)
                    .body(ApiResponse.error("Invalid or expired refresh token"));
        }

        UUID userId = tokenProvider.getUserId(refreshToken);
        userService.loadUserById(userId.toString()); // throws if user not found

        String newAccessToken  = tokenProvider.generateTokenForUser(userId);
        String newRefreshToken = tokenProvider.generateRefreshToken(userId);

        return ResponseEntity.ok(ApiResponse.success(Map.of(
                "token", newAccessToken,
                "refreshToken", newRefreshToken
        )));
    }

    /**
     * GET /api/auth/me  – lightweight "am I logged in?" check
     */
    @GetMapping("/me")
    public ResponseEntity<ApiResponse<Map<String, Object>>> me(
            @RequestHeader("Authorization") String authHeader) {

        String token = authHeader.replace("Bearer ", "");
        UUID userId = tokenProvider.getUserId(token);
        var user = userService.findById(userId);

        return ResponseEntity.ok(ApiResponse.success(Map.of(
                "id",    user.getId(),
                "email", user.getEmail(),
                "name",  user.getName() != null ? user.getName() : "",
                "avatar", user.getAvatarUrl() != null ? user.getAvatarUrl() : ""
        )));
    }
}
