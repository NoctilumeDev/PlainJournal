package com.ecommerce.chat.infrastructure.messaging;

import com.ecommerce.chat.infrastructure.persistence.mapper.ChatMessageMapper;
import com.ecommerce.chat.infrastructure.persistence.mapper.OutboxEventMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Set;

@Service
public class ChatOutboxCompletionService {

    private static final Set<String> DISPATCHED_OR_LATER =
            Set.of("DISPATCHED", "DELIVERED", "READ");

    private final OutboxEventMapper outboxMapper;
    private final ChatMessageMapper messageMapper;

    public ChatOutboxCompletionService(
            OutboxEventMapper outboxMapper,
            ChatMessageMapper messageMapper) {
        this.outboxMapper = outboxMapper;
        this.messageMapper = messageMapper;
    }

    @Transactional
    public boolean markPublished(
            String eventId,
            String publisherId,
            Long messageId,
            Instant now) {
        if (outboxMapper.markPublished(eventId, publisherId, now) != 1) {
            return false;
        }
        if (messageMapper.markDispatched(messageId, now) != 1) {
            String status = messageMapper.selectStatus(messageId);
            if (status == null || !DISPATCHED_OR_LATER.contains(status)) {
                throw new IllegalStateException(
                        "Chat Outbox message is missing or has an invalid dispatch state: "
                                + "messageId=" + messageId + ", status=" + status);
            }
        }
        return true;
    }
}
