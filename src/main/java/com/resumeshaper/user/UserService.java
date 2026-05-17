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
                    // Update profile fields that may have changed
                    existing.setName(name);
                    existing.setAvatarUrl(avatar);
                    if (email != null) existing.setEmail(email);
                    return userRepository.save(existing);
                })
                .orElseGet(() -> {
                    log.info("Creating new user email={} provider={}", email, provider);
                    return userRepository.save(User.builder()
                            .email(email != null ? email : providerId + "@" + provider.toLowerCase() + ".oauth")
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
