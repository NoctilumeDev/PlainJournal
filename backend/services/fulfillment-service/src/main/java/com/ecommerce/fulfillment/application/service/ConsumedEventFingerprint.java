package com.ecommerce.fulfillment.application.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

final class ConsumedEventFingerprint {

    private ConsumedEventFingerprint() {
    }

    static String of(ObjectMapper objectMapper, Object payload) {
        try {
            byte[] canonical = objectMapper.writeValueAsBytes(payload);
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(canonical));
        } catch (JsonProcessingException | NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Unable to fingerprint consumed event", exception);
        }
    }

    static boolean matches(String stored, String candidate) {
        return stored != null && MessageDigest.isEqual(
                stored.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                candidate.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
