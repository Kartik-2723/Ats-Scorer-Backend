package com.resumeshaper.auth;

import com.resumeshaper.config.AppProperties;
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

@Slf4j
@Component
@RequiredArgsConstructor
public class OAuth2SuccessHandler extends SimpleUrlAuthenticationSuccessHandler {

    private final JwtTokenProvider tokenProvider;
    private final UserService userService;
    private final AppProperties appProperties;

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request,
                                        HttpServletResponse response,
                                        Authentication authentication) throws IOException {

        OAuth2User oAuth2User = (OAuth2User) authentication.getPrincipal();
        String registrationId = extractRegistrationId(request);

        User user = userService.findOrCreateOAuth2User(oAuth2User, registrationId);
        String token = tokenProvider.generateTokenForUser(user.getId());
        String refreshToken = tokenProvider.generateRefreshToken(user.getId());

        String targetUrl = UriComponentsBuilder
                .fromUriString(appProperties.getOauth2().getRedirectUri())
                .queryParam("token", token)
                .queryParam("refreshToken", refreshToken)
                .build().toUriString();

        log.info("OAuth2 success for user={} provider={}", user.getEmail(), registrationId);
        getRedirectStrategy().sendRedirect(request, response, targetUrl);
    }

    private String extractRegistrationId(HttpServletRequest request) {
        String uri = request.getRequestURI();
        // URI pattern: /api/auth/oauth2/callback/{registrationId}
        String[] parts = uri.split("/");
        return parts[parts.length - 1];
    }
}
