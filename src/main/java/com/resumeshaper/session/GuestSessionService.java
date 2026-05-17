package com.resumeshaper.session;

import com.resumeshaper.common.exception.AppException;
import com.resumeshaper.config.AppProperties;
import com.resumeshaper.user.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.Base64;

@Slf4j
@Service
@RequiredArgsConstructor
public class GuestSessionService {

    private final GuestSessionRepository repository;
    private final AppProperties appProperties;
    private final SecureRandom secureRandom = new SecureRandom();

    /** Create a new guest session token. */
    @Transactional
    public GuestSession create() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);

        GuestSession session = GuestSession.builder()
                .token(token)
                .expiresAt(OffsetDateTime.now()
                        .plusHours(appProperties.getGuest().getSessionTtlHours()))
                .build();

        return repository.save(session);
    }

    /** Validate a guest token and return the session (throws on invalid/expired). */
    @Transactional(readOnly = true)
    public GuestSession validate(String token) {
        GuestSession session = repository.findByToken(token)
                .orElseThrow(() -> new AppException("Invalid guest session", HttpStatus.UNAUTHORIZED));

        if (session.isExpired()) {
            throw new AppException("Guest session expired", HttpStatus.UNAUTHORIZED);
        }
        return session;
    }

    /**
     * Claim a guest session → link all its resume jobs to a real user account.
     * Called immediately after OAuth2 sign-up if user had a guest token.
     */
    @Transactional
    public void claimSession(String token, User user) {
        GuestSession session = repository.findByToken(token)
                .orElseThrow(() -> new AppException("Guest session not found", HttpStatus.NOT_FOUND));

        if (session.isClaimed()) {
            log.warn("Guest session {} already claimed by {}", token, session.getClaimedBy().getId());
            return;
        }
        if (session.isExpired()) {
            throw new AppException("Cannot claim expired guest session", HttpStatus.GONE);
        }

        session.setClaimedBy(user);
        repository.save(session);
        log.info("Guest session {} claimed by user {}", token, user.getId());
    }

    public boolean exists(String token) {
        return repository.findByToken(token)
                .map(s -> !s.isExpired())
                .orElse(false);
    }
}
