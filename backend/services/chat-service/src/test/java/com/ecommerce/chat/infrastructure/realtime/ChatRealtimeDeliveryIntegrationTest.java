package com.ecommerce.chat.infrastructure.realtime;

import com.ecommerce.chat.application.model.ChatModels.Actor;
import com.ecommerce.chat.application.model.ChatModels.AttachmentView;
import com.ecommerce.chat.application.model.ChatModels.CreateConversationCommand;
import com.ecommerce.chat.application.model.ChatModels.MessageView;
import com.ecommerce.chat.application.model.ChatModels.SendMessageCommand;
import com.ecommerce.chat.application.service.ChatApplicationService;
import com.ecommerce.chat.application.service.ChatAttachmentService;
import com.ecommerce.chat.infrastructure.persistence.mapper.ChatMessageMapper;
import com.ecommerce.chat.infrastructure.persistence.mapper.MessageReceiptMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.mockito.ArgumentCaptor;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ActiveProfiles("test")
@SpringBootTest
class ChatRealtimeDeliveryIntegrationTest {

    private static final Long CUSTOMER_ID = 9201L;
    private static final Long AGENT_ID = 9202L;

    private final ChatApplicationService chatService;
    private final ChatMessageMapper messageMapper;
    private final MessageReceiptMapper receiptMapper;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbcTemplate;

    @Autowired
    ChatRealtimeDeliveryIntegrationTest(
            ChatApplicationService chatService,
            ChatMessageMapper messageMapper,
            MessageReceiptMapper receiptMapper,
            ObjectMapper objectMapper,
            JdbcTemplate jdbcTemplate) {
        this.chatService = chatService;
        this.messageMapper = messageMapper;
        this.receiptMapper = receiptMapper;
        this.objectMapper = objectMapper;
        this.jdbcTemplate = jdbcTemplate;
    }

    @AfterEach
    void cleanData() {
        jdbcTemplate.update("DELETE FROM message_receipt");
        jdbcTemplate.update("DELETE FROM outbox_event");
        jdbcTemplate.update("DELETE FROM chat_message");
        jdbcTemplate.update("DELETE FROM conversation_member");
        jdbcTemplate.update("DELETE FROM chat_conversation");
    }

    @Test
    void socketWriteAdvancesDeliveredAndReadStatesWithoutChangingMessageOwnership() throws Exception {
        Long conversationId = chatService.createConversation(
                new Actor(CUSTOMER_ID, false),
                new CreateConversationCommand(
                        "realtime-delivery-conversation",
                        "Realtime delivery integration",
                        null,
                        null)).id();
        chatService.claimConversation(new Actor(AGENT_ID, true), conversationId);
        MessageView message = chatService.sendMessage(
                new Actor(CUSTOMER_ID, false),
                conversationId,
                new SendMessageCommand(
                        "realtime-delivery-message",
                        "TEXT",
                        "This body must be loaded from MySQL at the target node."));

        LocalChatSessionRegistry sessions = new LocalChatSessionRegistry();
        WebSocketSession socket = mock(WebSocketSession.class);
        when(socket.getId()).thenReturn("agent-session");
        when(socket.isOpen()).thenReturn(true);
        sessions.register(AGENT_ID, socket);
        ChatDeliveryStateService stateService = new ChatDeliveryStateService(
                receiptMapper,
                messageMapper);
        ChatAttachmentService attachmentService = mock(ChatAttachmentService.class);
        when(attachmentService.attachmentsForMessage(message.id())).thenReturn(java.util.List.of(
                new AttachmentView(9301L, "proof.png", "image/png", 12)));
        ChatRealtimeDeliveryService deliveryService = new ChatRealtimeDeliveryService(
                messageMapper,
                receiptMapper,
                attachmentService,
                sessions,
                stateService,
                realtimeProperties(),
                objectMapper);

        assertThat(deliveryService.deliver(message.id(), AGENT_ID)).isTrue();
        ArgumentCaptor<TextMessage> frame = ArgumentCaptor.forClass(TextMessage.class);
        verify(socket).sendMessage(frame.capture());
        assertThat(objectMapper.readTree(frame.getValue().getPayload())
                .at("/message/attachments/0/fileName").asText()).isEqualTo("proof.png");
        assertThat(receiptMapper.selectState(message.id(), AGENT_ID)).isEqualTo("DELIVERED");
        assertThat(messageMapper.selectById(message.id()).getStatus()).isEqualTo("DELIVERED");

        chatService.markRead(new Actor(AGENT_ID, true), conversationId, message.id());
        assertThat(receiptMapper.selectState(message.id(), AGENT_ID)).isEqualTo("READ");
        assertThat(messageMapper.selectById(message.id()).getStatus()).isEqualTo("READ");
    }

    private ChatRealtimeProperties realtimeProperties() {
        return new ChatRealtimeProperties(
                true,
                null,
                "test",
                "chat-test-node",
                "127.0.0.1:18082",
                "ecommerce-chat-events-test",
                "ecommerce-chat-delivery-events-test",
                "chat-dispatcher-test",
                "chat-delivery-test",
                0,
                500,
                Duration.ofSeconds(1),
                Duration.ofSeconds(15),
                20,
                Duration.ofSeconds(12),
                Duration.ofSeconds(4),
                100);
    }
}
