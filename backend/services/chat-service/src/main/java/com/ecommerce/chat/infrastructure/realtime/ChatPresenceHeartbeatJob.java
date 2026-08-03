package com.ecommerce.chat.infrastructure.realtime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
        prefix = "ecommerce.chat.realtime",
        name = "enabled",
        havingValue = "true")
public class ChatPresenceHeartbeatJob {

    private static final Logger log = LoggerFactory.getLogger(ChatPresenceHeartbeatJob.class);

    private final LocalChatSessionRegistry sessions;
    private final RedisChatPresenceStore presenceStore;

    public ChatPresenceHeartbeatJob(
            LocalChatSessionRegistry sessions,
            RedisChatPresenceStore presenceStore) {
        this.sessions = sessions;
        this.presenceStore = presenceStore;
    }

    @Scheduled(
            initialDelayString = "${ecommerce.chat.realtime.refresh-interval:4s}",
            fixedDelayString = "${ecommerce.chat.realtime.refresh-interval:4s}")
    public void refresh() {
        try {
            presenceStore.refresh(sessions.onlineUsers());
        } catch (RuntimeException exception) {
            log.warn("Chat presence heartbeat failed; routes will expire unless Redis recovers", exception);
        }
    }
}
