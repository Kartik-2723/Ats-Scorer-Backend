package com.resumeshaper.auth;

import com.resumeshaper.common.dto.ApiResponse;
import com.resumeshaper.resume.ResumeJob;
import com.resumeshaper.resume.ResumeJobRepository;
import com.resumeshaper.session.GuestSession;
import com.resumeshaper.session.GuestSessionRepository;
import com.resumeshaper.user.User;
import com.resumeshaper.user.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final JwtTokenProvider       tokenProvider;
    private final UserService            userService;
    private final ResumeJobRepository    resumeJobRepository;
    private final GuestSessionRepository guestSessionRepository;
    private final OtpService otpService;

    /**
     * POST /api/auth/refresh
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
        userService.loadUserById(userId.toString());

        String newAccessToken  = tokenProvider.generateTokenForUser(userId);
        String newRefreshToken = tokenProvider.generateRefreshToken(userId);

        return ResponseEntity.ok(ApiResponse.success(Map.of(
                "token",        newAccessToken,
                "refreshToken", newRefreshToken
        )));
    }

    /**
     * GET /api/auth/me
     */
    @GetMapping("/me")
    public ResponseEntity<ApiResponse<Map<String, Object>>> me(
            @RequestHeader("Authorization") String authHeader) {

        String token = authHeader.replace("Bearer ", "");
        UUID userId = tokenProvider.getUserId(token);
        var user = userService.findById(userId);

        return ResponseEntity.ok(ApiResponse.success(Map.of(
                "id",     user.getId(),
                "email",  user.getEmail(),
                "name",   user.getName()      != null ? user.getName()      : "",
                "avatar", user.getAvatarUrl() != null ? user.getAvatarUrl() : ""
        )));
    }

    /**
     * POST /api/auth/claim-session
     * Body: { "guestToken": "..." }
     * Links all guest resume jobs to the authenticated user.
     */
    @PostMapping("/claim-session")
    public ResponseEntity<ApiResponse<Map<String, Object>>> claimSession(
            @RequestBody Map<String, String> body,
            @AuthenticationPrincipal User user) {

        String guestToken = body.get("guestToken");
        if (guestToken == null || guestToken.isBlank()) {
            return ResponseEntity.badRequest()
                    .body(ApiResponse.error("guestToken is required"));
        }

        Optional<GuestSession> sessionOpt = guestSessionRepository.findByToken(guestToken);
        if (sessionOpt.isEmpty()) {
            return ResponseEntity.ok(ApiResponse.success(Map.of(
                    "claimed", 0,
                    "message", "Guest session not found or already expired"
            )));
        }

        GuestSession session = sessionOpt.get();
        if (session.isClaimed()) {
            return ResponseEntity.ok(ApiResponse.success(Map.of(
                    "claimed", 0,
                    "message", "Session already claimed"
            )));
        }

        // Transfer all guest jobs to this user
        List<ResumeJob> guestJobs = resumeJobRepository
                .findUnclaimedByGuestToken(guestToken);

        for (ResumeJob job : guestJobs) {
            job.setUser(user);
            job.setGuestToken(null);
            resumeJobRepository.save(job);
        }

        // Mark session as claimed
        session.setClaimedBy(user);
        guestSessionRepository.save(session);

        log.info("Claimed guest session {} → {} jobs transferred to user={}",
                guestToken, guestJobs.size(), user.getId());

        return ResponseEntity.ok(ApiResponse.success(Map.of(
                "claimed", guestJobs.size(),
                "message", guestJobs.size() + " resume(s) transferred to your account"
        )));
    }

    /**
     * POST /api/auth/otp/send
     * Body: { "email": "user@example.com" }
     */
    @PostMapping("/otp/send")
    public ResponseEntity<ApiResponse<Map<String, String>>> sendOtp(
            @RequestBody Map<String, String> body) {

        String email = body.get("email");
        if (email == null || email.isBlank()) {
            return ResponseEntity.badRequest()
                    .body(ApiResponse.error("Email is required"));
        }

        try {
            otpService.sendOtp(email);
            return ResponseEntity.ok(ApiResponse.success(
                    Map.of("message", "OTP sent to " + email)));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(ApiResponse.error(e.getMessage()));
        } catch (Exception e) {
            log.error("Failed to send OTP to {}: {}", email, e.getMessage());
            return ResponseEntity.internalServerError()
                    .body(ApiResponse.error("Failed to send OTP. Please try again."));
        }
    }

    /**
     * POST /api/auth/otp/verify
     * Body: { "email": "user@example.com", "otp": "123456" }
     */
    @PostMapping("/otp/verify")
    public ResponseEntity<ApiResponse<Map<String, String>>> verifyOtp(
            @RequestBody Map<String, String> body) {

        String email = body.get("email");
        String otp   = body.get("otp");

        if (email == null || email.isBlank() || otp == null || otp.isBlank()) {
            return ResponseEntity.badRequest()
                    .body(ApiResponse.error("Email and OTP are required"));
        }

        try {
            boolean valid = otpService.verifyOtp(email, otp);
            if (!valid) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(ApiResponse.error("Incorrect code. Please try again."));
            }

            User user = userService.findOrCreateEmailUser(email);
            String token        = tokenProvider.generateTokenForUser(user.getId());
            String refreshToken = tokenProvider.generateRefreshToken(user.getId());

            log.info("Email OTP login success for {}", email);
            return ResponseEntity.ok(ApiResponse.success(Map.of(
                    "token",        token,
                    "refreshToken", refreshToken
            )));

        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(ApiResponse.error(e.getMessage()));
        } catch (Exception e) {
            log.error("OTP verify error for {}: {}", email, e.getMessage());
            return ResponseEntity.internalServerError()
                    .body(ApiResponse.error("Verification failed. Please try again."));
        }
    }
}