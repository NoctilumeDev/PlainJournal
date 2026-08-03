package com.ecommerce.chat.infrastructure.realtime;

import com.ecommerce.chat.application.service.ChatAttachmentService;
import com.ecommerce.chat.infrastructure.persistence.entity.ChatMessageEntity;
import com.ecommerce.chat.infrastructure.persistence.mapper.ChatMessageMapper;
import com.ecommerce.chat.infrastructure.persistence.mapper.MessageReceiptMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@ConditionalOnProperty(
        prefix = "ecommerce.chat.realtime",
        name = "enabled",
        havingValue = "true")
public class ChatRealtimeDeliveryService {

    private final ChatMessageMapper messageMapper;
    private final MessageReceiptMapper receiptMapper;
    private final ChatAttachmentService attachmentService;
    private final LocalChatSessionRegistry sessions;
    private final ChatDeliveryStateService stateService;
    private final ChatRealtimeProperties properties;
    private final ObjectMapper objectMapper;

    public ChatRealtimeDeliveryService(
            ChatMessageMapper messageMapper,
            MessageReceiptMapper receiptMapper,
            ChatAttachmentService attachmentService,
            LocalChatSessionRegistry sessions,
            ChatDeliveryStateService stateService,
            ChatRealtimeProperties properties,
            ObjectMapper objectMapper) {
        this.messageMapper = messageMapper;
        this.receiptMapper = receiptMapper;
        this.attachmentService = attachmentService;
        this.sessions = sessions;
        this.stateService = stateService;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public boolean deliver(Long messageId, Long recipientId) throws IOException {
        String receiptState = receiptMapper.selectState(messageId, recipientId);
        if (receiptState == null || "READ".equals(receiptState)) {
            return false;
        }
        ChatMessageEntity message = messageMapper.selectById(messageId);
        if (message == null) {
            return false;
        }
        int deliveredSessions = sessions.sendToUser(recipientId, frame(message));
        if (deliveredSessions == 0) {
            return false;
        }
        stateService.markDelivered(messageId, recipientId);
        return true;
    }

    public int replayOffline(Long recipientId) throws IOException {
        List<ChatMessageEntity> messages = messageMapper.selectOfflineForRecipient(
                recipientId,
                properties.offlineReplayBatchSize());
        int delivered = 0;
        for (ChatMessageEntity message : messages) {
            if (deliver(message.getId(), recipientId)) {
                delivered++;
            }
        }
        return delivered;
    }

    private String frame(ChatMessageEntity message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", "CHAT_MESSAGE");
        body.put("nodeId", properties.nodeId());
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("id", message.getId().toString());
        value.put("conversationId", message.getConversationId().toString());
        value.put("senderId", message.getSenderId().toString());
        value.put("clientMessageId", message.getClientMessageId());
        value.put("sequence", message.getMessageSequence());
        value.put("messageType", message.getMessageType());
        value.put("content", message.getContent());
        value.put("attachments", attachmentService.attachmentsForMessage(message.getId()));
        value.put("status", message.getStatus());
        value.put("createdAt", message.getCreatedAt());
        body.put("message", value);
        try {
            return objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Chat WebSocket frame serialization failed", exception);
        }
    }
}
