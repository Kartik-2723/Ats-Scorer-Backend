package com.resumeshaper.user;

import com.resumeshaper.common.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserService implements UserDetailsService {

    private final UserRepository userRepository;

    // ── UserDetailsService (for JWT filter) ──────────────────

    @Override
    public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new UsernameNotFoundException("User not found: " + email));
    }

    public UserDetails loadUserById(String id) {
        UUID uuid = UUID.fromString(id);
        return userRepository.findById(uuid)
                .orElseThrow(() -> new UsernameNotFoundException("User not found: " + id));
    }

    // ── OAuth2 ───────────────────────────────────────────────

    @Transactional
    public User findOrCreateOAuth2User(OAuth2User oAuth2User, String registrationId) {
        String email      = extractEmail(oAuth2User, registrationId);
        String providerId = extractProviderId(oAuth2User, registrationId);
        String name       = oAuth2User.getAttribute("name");
        String avatar     = extractAvatar(oAuth2User, registrationId);
        String provider   = registrationId.toUpperCase();

        return userRepository
                .findByProviderAndProviderId(provider, providerId)
                .map(existing -> {
                    existing.setName(name);
                    existing.setAvatarUrl(avatar);
                    if (email != null) existing.setEmail(email);
                    return userRepository.save(existing);
                })
                .orElseGet(() -> {
                    // Check if email already exists (e.g. signed up via OTP or other OAuth)
                    if (email != null) {
                        return userRepository.findByEmail(email)
                                .map(existingUser -> {
                                    // Link this OAuth provider to the existing account
                                    log.info("Linking provider={} to existing user email={}",
                                            provider, email);
                                    existingUser.setProvider(provider);
                                    existingUser.setProviderId(providerId);
                                    existingUser.setName(name != null ? name : existingUser.getName());
                                    existingUser.setAvatarUrl(avatar != null ? avatar : existingUser.getAvatarUrl());
                                    return userRepository.save(existingUser);
                                })
                                .orElseGet(() -> {
                                    log.info("Creating new user email={} provider={}", email, provider);
                                    return userRepository.save(User.builder()
                                            .email(email)
                                            .name(name)
                                            .avatarUrl(avatar)
                                            .provider(provider)
                                            .providerId(providerId)
                                            .role(UserRole.USER)
                                            .build());
                                });
                    }

                    // No email from provider (rare GitHub case)
                    log.info("Creating new user providerId={} provider={}", providerId, provider);
                    return userRepository.save(User.builder()
                            .email(providerId + "@" + provider.toLowerCase() + ".oauth")
                            .name(name)
                            .avatarUrl(avatar)
                            .provider(provider)
                            .providerId(providerId)
                            .role(UserRole.USER)
                            .build());
                });
    }

    // ── Queries ──────────────────────────────────────────────

    @Transactional(readOnly = true)
    public User findById(UUID id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("User", id.toString()));
    }

    // ── Email / OTP ──────────────────────────────────────────

    @Transactional
    public User findOrCreateEmailUser(String email) {
        String normalizedEmail = email.toLowerCase().trim();

        return userRepository.findByEmail(normalizedEmail)
                .map(existing -> {
                    // If they previously signed up via OAuth, block collision
                    if (!"EMAIL".equals(existing.getProvider())) {
                        throw new IllegalStateException(
                                "This email is linked to a " + existing.getProvider()
                                        + " account. Please sign in with "
                                        + existing.getProvider() + " instead."
                        );
                    }
                    return existing;
                })
                .orElseGet(() -> {
                    log.info("Creating new EMAIL user: {}", normalizedEmail);
                    return userRepository.save(User.builder()
                            .email(normalizedEmail)
                            .provider("EMAIL")
                            .providerId(null)
                            .role(UserRole.USER)
                            .build());
                });
    }

    // ── Private helpers ──────────────────────────────────────

    private String extractEmail(OAuth2User user, String provider) {
        if ("github".equalsIgnoreCase(provider)) {
            // GitHub may not expose email publicly; attribute may be null
            return user.getAttribute("email");
        }
        return user.getAttribute("email");
    }

    private String extractProviderId(OAuth2User user, String provider) {
        Object id = user.getAttribute("sub");          // Google
        if (id == null) id = user.getAttribute("id");  // GitHub
        return id != null ? id.toString() : user.getName();
    }

    private String extractAvatar(OAuth2User user, String provider) {
        if ("github".equalsIgnoreCase(provider)) {
            return user.getAttribute("avatar_url");
        }
        return user.getAttribute("picture");
    }
}
