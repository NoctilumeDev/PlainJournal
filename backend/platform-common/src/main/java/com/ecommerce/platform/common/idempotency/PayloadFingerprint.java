package com.ecommerce.platform.common.idempotency;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public final class PayloadFingerprint {

    private PayloadFingerprint() {
    }

    public static String of(Object... components) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (Object component : components) {
                String type = component == null ? "<null>" : component.getClass().getName();
                String value = component == null ? "<null>" : component.toString();
                update(digest, type);
                update(digest, value);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    public static boolean matches(String stored, String candidate) {
        return stored != null && candidate != null && MessageDigest.isEqual(
                stored.getBytes(StandardCharsets.UTF_8),
                candidate.getBytes(StandardCharsets.UTF_8));
    }

    private static void update(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update((byte) (bytes.length >>> 24));
        digest.update((byte) (bytes.length >>> 16));
        digest.update((byte) (bytes.length >>> 8));
        digest.update((byte) bytes.length);
        digest.update(bytes);
    }
}
