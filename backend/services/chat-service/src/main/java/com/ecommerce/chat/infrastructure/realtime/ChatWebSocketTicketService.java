package com.ecommerce.chat.infrastructure.realtime;

import com.ecommerce.chat.application.exception.ChatError;
import com.ecommerce.chat.application.exception.ChatException;
import com.ecommerce.chat.application.model.ChatModels.WebSocketTicketIdentity;
import com.ecommerce.chat.application.model.ChatModels.WebSocketTicketView;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

@Component
public class ChatWebSocketTicketService {

    private static final Logger log = LoggerFactory.getLogger(ChatWebSocketTicketService.class);
    static final String QUERY_PARAMETER = "ticket";
    private static final int MAXIMUM_ISSUE_ATTEMPTS = 3;
    private static final Set<String> ALLOWED_ROLES = Set.of(
            "ROLE_CUSTOMER",
            "ROLE_ADMIN",
            "ROLE_OPERATOR");
    private static final Pattern TICKET_PATTERN = Pattern.compile("[A-Za-z0-9_-]{32,128}");
    private static final DefaultRedisScript<String> CONSUME_SCRIPT = new DefaultRedisScript<>("""
            local value = redis.call('GET', KEYS[1])
            if not value then
                return nil
            end
            redis.call('DEL', KEYS[1])
            return value
            """, String.class);

    private final StringRedisTemplate redisTemplate;
    private final ChatWebSocketTicketProperties properties;
    private final ObjectMapper objectMapper;
    private final SecureRandom secureRandom;
    private final Clock clock;

    public ChatWebSocketTicketService(
            StringRedisTemplate redisTemplate,
            ChatWebSocketTicketProperties properties,
            ObjectMapper objectMapper,
            SecureRandom secureRandom,
            Clock clock) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.secureRandom = secureRandom;
        this.clock = clock;
    }

    public WebSocketTicketView issue(Long userId, List<String> authorities) {
        ensureEnabled();
        List<String> roles = normalizedRoles(authorities);
        if (userId == null || userId <= 0 || roles.isEmpty()) {
            throw new ChatException(ChatError.WEBSOCKET_TICKET_ACCESS_DENIED);
        }
        Instant issuedAt = clock.instant();
        Instant expiresAt = issuedAt.plus(properties.ttl());
        StoredTicket storedTicket = new StoredTicket(
                properties.namespace(),
                userId,
                roles,
                properties.targetPath(),
                issuedAt,
                expiresAt);
        String payload = serialize(storedTicket);
        try {
            for (int attempt = 0; attempt < MAXIMUM_ISSUE_ATTEMPTS; attempt++) {
                String ticket = newTicket();
                Boolean stored = redisTemplate.opsForValue().setIfAbsent(
                        ticketKey(ticket),
                        payload,
                        properties.ttl());
                if (Boolean.TRUE.equals(stored)) {
                    return new WebSocketTicketView(
                            ticket,
                            properties.targetPath(),
                            QUERY_PARAMETER,
                            expiresAt);
                }
            }
        } catch (DataAccessException exception) {
            throw new ChatException(ChatError.CHAT_REALTIME_UNAVAILABLE, exception);
        }
        throw new ChatException(ChatError.CHAT_REALTIME_UNAVAILABLE);
    }

    public Optional<WebSocketTicketIdentity> consume(String ticket, String requestPath) {
        if (!properties.enabled()
                || ticket == null
                || !TICKET_PATTERN.matcher(ticket).matches()
                || !properties.targetPath().equals(requestPath)) {
            return Optional.empty();
        }
        String payload;
        try {
            payload = redisTemplate.execute(CONSUME_SCRIPT, List.of(ticketKey(ticket)));
        } catch (DataAccessException exception) {
            throw new ChatException(ChatError.CHAT_REALTIME_UNAVAILABLE, exception);
        }
        if (payload == null) {
            return Optional.empty();
        }
        try {
            StoredTicket stored = objectMapper.readValue(payload, StoredTicket.class);
            List<String> roles = normalizedRoles(stored.roles());
            String rejectionReason = rejectionReason(stored, roles, requestPath);
            if (rejectionReason != null) {
                log.warn("Rejecting consumed Chat WebSocket ticket: reason={}", rejectionReason);
                return Optional.empty();
            }
            return Optional.of(new WebSocketTicketIdentity(stored.userId(), roles));
        } catch (JsonProcessingException exception) {
            log.warn("Discarding malformed stored Chat WebSocket ticket payload", exception);
            return Optional.empty();
        }
    }

    private void ensureEnabled() {
        if (!properties.enabled()) {
            throw new ChatException(ChatError.CHAT_REALTIME_UNAVAILABLE);
        }
    }

    private List<String> normalizedRoles(List<String> authorities) {
        if (authorities == null) {
            return List.of();
        }
        return authorities.stream()
                .filter(ALLOWED_ROLES::contains)
                .distinct()
                .sorted()
                .toList();
    }

    private String rejectionReason(
            StoredTicket stored,
            List<String> roles,
            String requestPath) {
        if (!properties.namespace().equals(stored.namespace())) {
            return "namespace_mismatch";
        }
        if (!properties.targetPath().equals(stored.targetPath())
                || !requestPath.equals(stored.targetPath())) {
            return "path_mismatch";
        }
        if (stored.userId() == null || stored.userId() <= 0) {
            return "invalid_user";
        }
        if (roles.isEmpty()) {
            return "invalid_roles";
        }
        if (stored.issuedAt() == null
                || stored.expiresAt() == null
                || !stored.expiresAt().isAfter(stored.issuedAt())) {
            return "invalid_lifetime";
        }
        return null;
    }

    private String newTicket() {
        byte[] bytes = new byte[properties.entropyBytes()];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String ticketKey(String ticket) {
        return properties.redisKeyPrefix() + sha256(ticket);
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private String serialize(StoredTicket ticket) {
        try {
            return objectMapper.writeValueAsString(ticket);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Chat WebSocket ticket serialization failed", exception);
        }
    }

    private record StoredTicket(
            String namespace,
            Long userId,
            List<String> roles,
            String targetPath,
            Instant issuedAt,
            Instant expiresAt
    ) {
        private StoredTicket {
            roles = roles == null ? List.of() : List.copyOf(roles);
        }
    }
}
