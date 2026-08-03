package com.ecommerce.platform.common.api;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.Objects;

public record KeysetCursor(Instant createdAt, long id) {

    private static final String VERSION = "v1";
    private static final String SEPARATOR = "|";

    public KeysetCursor {
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        if (id <= 0) {
            throw new IllegalArgumentException("Cursor id must be positive");
        }
    }

    public String encode() {
        String payload = VERSION + SEPARATOR + createdAt + SEPARATOR + id;
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(payload.getBytes(StandardCharsets.UTF_8));
    }

    public static KeysetCursor decode(String encoded) {
        if (encoded == null || encoded.isBlank()) {
            throw new IllegalArgumentException("Cursor must not be blank");
        }
        try {
            String payload = new String(
                    Base64.getUrlDecoder().decode(encoded),
                    StandardCharsets.UTF_8);
            String[] parts = payload.split("\\|", -1);
            if (parts.length != 3 || !VERSION.equals(parts[0])) {
                throw new IllegalArgumentException("Unsupported cursor payload");
            }
            return new KeysetCursor(Instant.parse(parts[1]), Long.parseLong(parts[2]));
        } catch (DateTimeParseException | NumberFormatException exception) {
            throw new IllegalArgumentException("Invalid cursor payload", exception);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Invalid cursor", exception);
        }
    }
}
