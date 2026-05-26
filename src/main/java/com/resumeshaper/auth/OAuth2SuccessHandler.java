package com.resumeshaper.auth;

import com.resumeshaper.config.AppProperties;
import com.resumeshaper.resume.ResumeJob;
import com.resumeshaper.resume.ResumeJobRepository;
import com.resumeshaper.session.GuestSession;
import com.resumeshaper.session.GuestSessionRepository;
import com.resumeshaper.user.User;
import com.resumeshaper.user.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationSuccessHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

@Slf4j
@Component
@RequiredArgsConstructor
public class OAuth2SuccessHandler extends SimpleUrlAuthenticationSuccessHandler {

    private final JwtTokenProvider       tokenProvider;
    private final UserService            userService;
    private final AppProperties          appProperties;
    private final ResumeJobRepository    resumeJobRepository;
    private final GuestSessionRepository guestSessionRepository;

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request,
                                        HttpServletResponse response,
                                        Authentication authentication) throws IOException {

        OAuth2User oAuth2User = (OAuth2User) authentication.getPrincipal();
        String registrationId = extractRegistrationId(request);

        User user = userService.findOrCreateOAuth2User(oAuth2User, registrationId);
        String token = tokenProvider.generateTokenForUser(user.getId());
        String refreshToken = tokenProvider.generateRefreshToken(user.getId());

        // Priority 5: Claim guest session if guestToken passed as state param
        String guestToken = request.getParameter("guestToken");
        if (guestToken != null && !guestToken.isBlank()) {
            claimGuestSession(guestToken, user);
        }

        String targetUrl = UriComponentsBuilder
                .fromUriString(appProperties.getOauth2().getRedirectUri())
                .queryParam("token", token)
                .queryParam("refreshToken", refreshToken)
                .build().toUriString();

        log.info("OAuth2 success for user={} provider={}", user.getEmail(), registrationId);
        getRedirectStrategy().sendRedirect(request, response, targetUrl);
    }

    // ── Priority 5: Claim guest session ──────────────────────
    private void claimGuestSession(String guestToken, User user) {
        try {
            Optional<GuestSession> sessionOpt = guestSessionRepository.findByToken(guestToken);
            if (sessionOpt.isEmpty()) {
                log.warn("Guest token {} not found during claim", guestToken);
                return;
            }

            GuestSession session = sessionOpt.get();
            if (session.isClaimed()) {
                log.warn("Guest session {} already claimed", guestToken);
                return;
            }

            // Reassign all guest jobs to this user
            List<ResumeJob> guestJobs = resumeJobRepository
                    .findUnclaimedByGuestToken(guestToken);

            for (ResumeJob job : guestJobs) {
                job.setUser(user);
                job.setGuestToken(null);
                resumeJobRepository.save(job);
                log.info("Transferred guest job={} to user={}", job.getId(), user.getId());
            }

            // Mark session as claimed
            session.setClaimedBy(user);
            guestSessionRepository.save(session);

            log.info("Claimed guest session {} → {} jobs transferred to user={}",
                    guestToken, guestJobs.size(), user.getId());

        } catch (Exception ex) {
            log.error("Failed to claim guest session {}: {}", guestToken, ex.getMessage());
        }
    }

    private String extractRegistrationId(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String[] parts = uri.split("/");
        return parts[parts.length - 1];
    }
}