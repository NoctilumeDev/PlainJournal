package com.ecommerce.identity.application.model;

import java.util.List;

public record UserProfile(
        Long id,
        String email,
        String displayName,
        String status,
        List<String> roles
) {

    public UserProfile {
        roles = List.copyOf(roles);
    }
}
