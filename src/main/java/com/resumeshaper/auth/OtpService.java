package com.resumeshaper.auth;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Duration;

@Slf4j
@Service
@RequiredArgsConstructor
public class OtpService {

    private static final String OTP_PREFIX      = "otp:code:";
    private static final String ATTEMPTS_PREFIX = "otp:attempts:";
    private static final String RESEND_PREFIX   = "otp:resend:";
    private static final int    RESEND_COOLDOWN_SECONDS = 30;

    private final StringRedisTemplate redisTemplate;
    private final EmailService        emailService;

    @Value("${app.otp.ttl-minutes}")
    private int otpTtlMinutes;

    @Value("${app.otp.attempts-ttl-minutes}")
    private int attemptsTtlMinutes;

    @Value("${app.otp.max-attempts}")
    private int maxAttempts;

    // ── Send ─────────────────────────────────────────────────

    /**
     * Generates and emails an OTP. Enforces 30s resend cooldown.
     * @throws IllegalStateException if called within cooldown window
     */
    public void sendOtp(String email) {
        String normalizedEmail = email.toLowerCase().trim();
        String resendKey = RESEND_PREFIX + normalizedEmail;

        // Enforce resend cooldown
        if (Boolean.TRUE.equals(redisTemplate.hasKey(resendKey))) {
            Long ttl = redisTemplate.getExpire(resendKey);
            throw new IllegalStateException(
                    "Please wait " + (ttl != null ? ttl : RESEND_COOLDOWN_SECONDS)
                            + " seconds before requesting a new code."
            );
        }

        String otp  = generateOtp();
        String codeKey     = OTP_PREFIX      + normalizedEmail;
        String attemptsKey = ATTEMPTS_PREFIX + normalizedEmail;

        // Store OTP + reset attempt counter
        redisTemplate.opsForValue().set(codeKey,     otp, Duration.ofMinutes(otpTtlMinutes));
        redisTemplate.opsForValue().set(attemptsKey, "0", Duration.ofMinutes(attemptsTtlMinutes));

        // Set resend cooldown
        redisTemplate.opsForValue().set(resendKey, "1",
                Duration.ofSeconds(RESEND_COOLDOWN_SECONDS));

        emailService.sendOtp(normalizedEmail, otp);
        log.info("OTP sent to {}", normalizedEmail);
    }

    // ── Verify ───────────────────────────────────────────────

    /**
     * Verifies OTP. Returns true on success (and cleans up Redis).
     * Returns false on wrong code. Throws on expiry or too many attempts.
     */
    public boolean verifyOtp(String email, String code) {
        String normalizedEmail = email.toLowerCase().trim();
        String codeKey     = OTP_PREFIX      + normalizedEmail;
        String attemptsKey = ATTEMPTS_PREFIX + normalizedEmail;

        String stored = redisTemplate.opsForValue().get(codeKey);
        if (stored == null) {
            throw new IllegalStateException("OTP expired. Please request a new code.");
        }

        String attemptsStr = redisTemplate.opsForValue().get(attemptsKey);
        int attempts = attemptsStr != null ? Integer.parseInt(attemptsStr) : 0;

        if (attempts >= maxAttempts) {
            // Clean up so user can request fresh OTP
            redisTemplate.delete(codeKey);
            redisTemplate.delete(attemptsKey);
            throw new IllegalStateException(
                    "Too many incorrect attempts. Please request a new code.");
        }

        if (!stored.equals(code.trim())) {
            redisTemplate.opsForValue().increment(attemptsKey);
            log.warn("Wrong OTP for {} (attempt {})", normalizedEmail, attempts + 1);
            return false;
        }

        // ✅ Correct — clean up all keys
        redisTemplate.delete(codeKey);
        redisTemplate.delete(attemptsKey);
        redisTemplate.delete(RESEND_PREFIX + normalizedEmail);
        log.info("OTP verified for {}", normalizedEmail);
        return true;
    }

    // ── Helpers ──────────────────────────────────────────────

    private String generateOtp() {
        return String.valueOf(100000 + new SecureRandom().nextInt(900000));
    }
}