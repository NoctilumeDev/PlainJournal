package com.ecommerce.chat.application.service;

import com.ecommerce.chat.application.model.ChatModels.Actor;
import com.ecommerce.chat.application.model.ChatModels.ConversationView;
import com.ecommerce.chat.application.model.ChatModels.CreateConversationCommand;
import com.ecommerce.chat.infrastructure.persistence.entity.ConversationEntity;
import com.ecommerce.chat.infrastructure.persistence.entity.ConversationMemberEntity;
import com.ecommerce.chat.infrastructure.persistence.mapper.ChatMessageMapper;
import com.ecommerce.chat.infrastructure.persistence.mapper.ConversationMapper;
import com.ecommerce.chat.infrastructure.persistence.mapper.ConversationMemberMapper;
import com.ecommerce.chat.infrastructure.persistence.mapper.MessageReceiptMapper;
import com.ecommerce.chat.infrastructure.persistence.mapper.OutboxEventMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatApplicationServiceIdempotencyTest {

    @Test
    void doesNotTrustMysqlFoundRowsCountForDuplicateConversation() {
        ConversationMapper conversationMapper = mock(ConversationMapper.class);
        ConversationMemberMapper memberMapper = mock(ConversationMemberMapper.class);
        ChatMessageMapper messageMapper = mock(ChatMessageMapper.class);
        MessageReceiptMapper receiptMapper = mock(MessageReceiptMapper.class);
        OutboxEventMapper outboxMapper = mock(OutboxEventMapper.class);
        ChatAttachmentService attachmentService = mock(ChatAttachmentService.class);
        AtomicReference<ConversationEntity> attempted = new AtomicReference<>();

        when(conversationMapper.insertIdempotent(any())).thenAnswer(invocation -> {
            attempted.set(invocation.getArgument(0));
            // Connector/J may report one found row for a no-op duplicate update.
            return 1;
        });
        when(conversationMapper.selectByClientConversationId(anyLong(), anyString()))
                .thenAnswer(invocation -> persistedDuplicate(attempted.get()));
        when(messageMapper.countUnread(anyLong(), anyLong())).thenReturn(0L);
        when(outboxMapper.currentTime()).thenReturn(
                Instant.parse("2026-07-24T09:00:00Z"));

        ChatApplicationService service = new ChatApplicationService(
                conversationMapper,
                memberMapper,
                messageMapper,
                receiptMapper,
                outboxMapper,
                attachmentService,
                new ObjectMapper(),
                "ecommerce-chat-events-test");

        ConversationView result = service.createConversation(
                new Actor(1001L, false),
                new CreateConversationCommand(
                        "same-client-key",
                        "Same request",
                        null,
                        null));

        assertThat(result.id()).isEqualTo(9001L);
        assertThat(result.subject()).isEqualTo("Same request");
        verify(memberMapper, never()).insert(any(ConversationMemberEntity.class));
    }

    private ConversationEntity persistedDuplicate(ConversationEntity attempted) {
        ConversationEntity persisted = new ConversationEntity();
        persisted.setId(9001L);
        persisted.setConversationNo("CHAT-PERSISTED");
        persisted.setCustomerId(attempted.getCustomerId());
        persisted.setClientConversationId(attempted.getClientConversationId());
        persisted.setRequestHash(attempted.getRequestHash());
        persisted.setSubject(attempted.getSubject());
        persisted.setContextType(attempted.getContextType());
        persisted.setContextId(attempted.getContextId());
        persisted.setStatus(attempted.getStatus());
        persisted.setLastMessageSequence(attempted.getLastMessageSequence());
        persisted.setVersion(attempted.getVersion());
        persisted.setCreatedAt(attempted.getCreatedAt());
        persisted.setUpdatedAt(attempted.getUpdatedAt());
        return persisted;
    }
}
