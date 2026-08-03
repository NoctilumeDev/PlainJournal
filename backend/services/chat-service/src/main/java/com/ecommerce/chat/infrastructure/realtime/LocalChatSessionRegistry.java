package com.ecommerce.chat.infrastructure.realtime;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Component
@ConditionalOnProperty(
        prefix = "ecommerce.chat.realtime",
        name = "enabled",
        havingValue = "true")
public class LocalChatSessionRegistry {

    private final ConcurrentHashMap<String, LocalSession> sessionsById = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, Set<String>> sessionIdsByUser = new ConcurrentHashMap<>();

    public void register(Long userId, WebSocketSession session) {
        LocalSession localSession = new LocalSession(userId, session);
        sessionsById.put(session.getId(), localSession);
        sessionIdsByUser.computeIfAbsent(userId, ignored -> ConcurrentHashMap.newKeySet())
                .add(session.getId());
    }

    public Long remove(String sessionId) {
        LocalSession removed = sessionsById.remove(sessionId);
        if (removed == null) {
            return null;
        }
        sessionIdsByUser.computeIfPresent(removed.userId(), (userId, sessionIds) -> {
            sessionIds.remove(sessionId);
            return sessionIds.isEmpty() ? null : sessionIds;
        });
        return removed.userId();
    }

    public boolean hasSessions(Long userId) {
        Set<String> sessionIds = sessionIdsByUser.get(userId);
        return sessionIds != null && !sessionIds.isEmpty();
    }

    public Set<Long> onlineUsers() {
        return Collections.unmodifiableSet(sessionIdsByUser.keySet());
    }

    public int sendToUser(Long userId, String payload) throws IOException {
        Set<String> sessionIds = sessionIdsByUser.get(userId);
        if (sessionIds == null || sessionIds.isEmpty()) {
            return 0;
        }
        List<IOException> failures = new ArrayList<>();
        int delivered = 0;
        for (String sessionId : List.copyOf(sessionIds)) {
            LocalSession local = sessionsById.get(sessionId);
            if (local == null || !local.session().isOpen()) {
                remove(sessionId);
                continue;
            }
            try {
                synchronized (local.session()) {
                    local.session().sendMessage(new TextMessage(payload));
                }
                delivered++;
            } catch (IOException exception) {
                failures.add(exception);
                remove(sessionId);
            }
        }
        if (delivered == 0 && !failures.isEmpty()) {
            throw failures.get(0);
        }
        return delivered;
    }

    private record LocalSession(Long userId, WebSocketSession session) {
    }
}
