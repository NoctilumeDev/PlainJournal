package com.ecommerce.chat.infrastructure.realtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
@ConditionalOnProperty(
        prefix = "ecommerce.chat.realtime",
        name = "enabled",
        havingValue = "true")
public class ChatWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(ChatWebSocketHandler.class);

    private final LocalChatSessionRegistry sessions;
    private final RedisChatPresenceStore presenceStore;
    private final ChatRealtimeDeliveryService deliveryService;
    private final ChatRealtimeProperties properties;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public ChatWebSocketHandler(
            LocalChatSessionRegistry sessions,
            RedisChatPresenceStore presenceStore,
            ChatRealtimeDeliveryService deliveryService,
            ChatRealtimeProperties properties,
            ObjectMapper objectMapper,
            Clock clock) {
        this.sessions = sessions;
        this.presenceStore = presenceStore;
        this.deliveryService = deliveryService;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        Long userId = userId(session);
        sessions.register(userId, session);
        try {
            presenceStore.register(userId);
        } catch (RuntimeException exception) {
            sessions.remove(session.getId());
            session.close(CloseStatus.SERVICE_OVERLOAD.withReason("Presence store unavailable"));
            throw exception;
        }
        send(session, Map.of(
                "type", "CONNECTED",
                "nodeId", properties.nodeId(),
                "sessionId", session.getId(),
                "connectedAt", clock.instant(),
                "roles", ChatWebSocketHandshakeInterceptor.roles(session.getAttributes())));
        try {
            deliveryService.replayOffline(userId);
        } catch (Exception exception) {
            log.warn("Offline chat replay failed after WebSocket connection: userId={}, nodeId={}",
                    userId, properties.nodeId(), exception);
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        JsonNode frame = objectMapper.readTree(message.getPayload());
        if (!"PING".equals(frame.path("type").asText())) {
            send(session, Map.of(
                    "type", "ERROR",
                    "code", "UNSUPPORTED_FRAME",
                    "message", "Only PING is accepted; chat messages must use the persistent REST API"));
            return;
        }
        presenceStore.register(userId(session));
        send(session, Map.of(
                "type", "PONG",
                "nodeId", properties.nodeId(),
                "serverTime", clock.instant()));
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
        log.debug("Chat WebSocket transport error: sessionId={}, nodeId={}",
                session.getId(), properties.nodeId(), exception);
        if (session.isOpen()) {
            session.close(CloseStatus.SERVER_ERROR);
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        Long userId = sessions.remove(session.getId());
        if (userId == null || sessions.hasSessions(userId)) {
            return;
        }
        try {
            presenceStore.unregister(userId);
        } catch (RuntimeException exception) {
            log.warn("Chat route cleanup failed and will rely on TTL expiry: userId={}, nodeId={}",
                    userId, properties.nodeId(), exception);
        }
    }

    private Long userId(WebSocketSession session) {
        Object value = session.getAttributes().get(
                ChatWebSocketHandshakeInterceptor.USER_ID_ATTRIBUTE);
        if (value instanceof Long userId && userId > 0) {
            return userId;
        }
        throw new IllegalStateException("Authenticated chat user ID is missing");
    }

    private void send(WebSocketSession session, Map<String, Object> value) throws Exception {
        Map<String, Object> ordered = new LinkedHashMap<>(value);
        synchronized (session) {
            session.sendMessage(new TextMessage(objectMapper.writeValueAsString(ordered)));
        }
    }
}
