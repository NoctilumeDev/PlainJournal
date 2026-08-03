package com.ecommerce.identity.application.model;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;

import java.util.List;

public record UserProfile(
        @JsonSerialize(using = ToStringSerializer.class)
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
