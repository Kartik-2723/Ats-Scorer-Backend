package com.resumeshaper.user;

import java.util.UUID;

public record UserDto(
        UUID   id,
        String email,
        String name,
        String avatarUrl,
        String provider,
        String role
) {
    public static UserDto from(User user) {
        return new UserDto(
                user.getId(),
                user.getEmail(),
                user.getName(),
                user.getAvatarUrl(),
                user.getProvider(),
                user.getRole().name()
        );
    }
}
