package com.ecommerce.chat.application.service;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.ecommerce.chat.application.exception.ChatError;
import com.ecommerce.chat.application.exception.ChatException;
import com.ecommerce.chat.application.model.ChatModels.Actor;
import com.ecommerce.chat.application.model.ChatModels.AttachmentView;
import com.ecommerce.chat.application.model.ChatModels.ConversationView;
import com.ecommerce.chat.application.model.ChatModels.CreateConversationCommand;
import com.ecommerce.chat.application.model.ChatModels.MessagePage;
import com.ecommerce.chat.application.model.ChatModels.MessageView;
import com.ecommerce.chat.application.model.ChatModels.ReadView;
import com.ecommerce.chat.application.model.ChatModels.SendMessageCommand;
import com.ecommerce.chat.infrastructure.persistence.entity.ChatMessageEntity;
import com.ecommerce.chat.infrastructure.persistence.entity.ChatAttachmentUploadEntity;
import com.ecommerce.chat.infrastructure.persistence.entity.ConversationEntity;
import com.ecommerce.chat.infrastructure.persistence.entity.ConversationMemberEntity;
import com.ecommerce.chat.infrastructure.persistence.entity.OutboxEventEntity;
import com.ecommerce.chat.infrastructure.persistence.mapper.ChatMessageMapper;
import com.ecommerce.chat.infrastructure.persistence.mapper.ConversationMapper;
import com.ecommerce.chat.infrastructure.persistence.mapper.ConversationMemberMapper;
import com.ecommerce.chat.infrastructure.persistence.mapper.MessageReceiptMapper;
import com.ecommerce.chat.infrastructure.persistence.mapper.OutboxEventMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
public class ChatApplicationService {

    private static final String OPEN = "OPEN";
    private static final String CLOSED = "CLOSED";
    private static final String STORED = "STORED";
    private static final String TEXT = "TEXT";
    private static final String IMAGE = "IMAGE";
    private static final String FILE = "FILE";

    private final ConversationMapper conversationMapper;
    private final ConversationMemberMapper memberMapper;
    private final ChatMessageMapper messageMapper;
    private final MessageReceiptMapper receiptMapper;
    private final OutboxEventMapper outboxMapper;
    private final ChatAttachmentService attachmentService;
    private final ObjectMapper objectMapper;
    private final String outboxTopic;

    public ChatApplicationService(
            ConversationMapper conversationMapper,
            ConversationMemberMapper memberMapper,
            ChatMessageMapper messageMapper,
            MessageReceiptMapper receiptMapper,
            OutboxEventMapper outboxMapper,
            ChatAttachmentService attachmentService,
            ObjectMapper objectMapper,
            @Value("${ecommerce.chat.outbox.topic}") String outboxTopic) {
        this.conversationMapper = conversationMapper;
        this.memberMapper = memberMapper;
        this.messageMapper = messageMapper;
        this.receiptMapper = receiptMapper;
        this.outboxMapper = outboxMapper;
        this.attachmentService = attachmentService;
        this.objectMapper = objectMapper;
        this.outboxTopic = outboxTopic;
    }

    @Transactional
    public ConversationView createConversation(Actor actor, CreateConversationCommand command) {
        String subject = command.subject().trim();
        String contextType = normalizeNullable(command.contextType(), true);
        String contextId = normalizeNullable(command.contextId(), false);
        if ((contextType == null) != (contextId == null)) {
            throw new ChatException(ChatError.INVALID_CONTEXT);
        }
        String requestHash = hash(subject, contextType, contextId);
        Instant now = outboxMapper.currentTime();

        ConversationEntity conversation = new ConversationEntity();
        conversation.setId(IdWorker.getId());
        conversation.setConversationNo("CHAT-" + UUID.randomUUID().toString().replace("-", "").toUpperCase(Locale.ROOT));
        conversation.setCustomerId(actor.userId());
        conversation.setClientConversationId(command.clientConversationId());
        conversation.setRequestHash(requestHash);
        conversation.setSubject(subject);
        conversation.setContextType(contextType);
        conversation.setContextId(contextId);
        conversation.setStatus(OPEN);
        conversation.setLastMessageSequence(0L);
        conversation.setVersion(0);
        conversation.setCreatedAt(now);
        conversation.setUpdatedAt(now);

        conversationMapper.insertIdempotent(conversation);
        ConversationEntity persisted = conversationMapper.selectByClientConversationId(
                actor.userId(), command.clientConversationId());
        if (persisted == null || !requestHash.equals(persisted.getRequestHash())) {
            throw new ChatException(ChatError.IDEMPOTENCY_CONFLICT);
        }
        if (!conversation.getId().equals(persisted.getId())) {
            return toConversationView(persisted, actor.userId());
        }

        ConversationMemberEntity customer = new ConversationMemberEntity();
        customer.setId(IdWorker.getId());
        customer.setConversationId(conversation.getId());
        customer.setUserId(actor.userId());
        customer.setMemberRole("CUSTOMER");
        customer.setJoinedAt(now);
        memberMapper.insert(customer);
        return toConversationView(conversation, actor.userId());
    }

    @Transactional(readOnly = true)
    public List<ConversationView> listConversations(Actor actor, int limit) {
        List<ConversationEntity> conversations = actor.supportAgent()
                ? conversationMapper.selectForSupport(limit)
                : conversationMapper.selectForMember(actor.userId(), limit);
        return conversations.stream()
                .map(conversation -> toConversationView(conversation, actor.userId()))
                .toList();
    }

    @Transactional(readOnly = true)
    public ConversationView getConversation(Actor actor, Long conversationId) {
        ConversationEntity conversation = requireConversation(conversationId);
        requireMember(conversationId, actor.userId());
        return toConversationView(conversation, actor.userId());
    }

    @Transactional
    public ConversationView claimConversation(Actor actor, Long conversationId) {
        if (!actor.supportAgent()) {
            throw new ChatException(ChatError.CONVERSATION_ACCESS_DENIED);
        }
        ConversationEntity conversation = requireConversationForUpdate(conversationId);
        if (!OPEN.equals(conversation.getStatus())) {
            throw new ChatException(ChatError.CONVERSATION_CLOSED);
        }
        if (conversation.getAssignedAgentId() != null) {
            if (conversation.getAssignedAgentId().equals(actor.userId())) {
                return toConversationView(conversation, actor.userId());
            }
            throw new ChatException(ChatError.CONVERSATION_ALREADY_ASSIGNED);
        }

        Instant now = outboxMapper.currentTime();
        conversation.setAssignedAgentId(actor.userId());
        conversation.setVersion(conversation.getVersion() + 1);
        conversation.setUpdatedAt(now);
        requireConversationUpdated(conversationMapper.updateById(conversation));

        ConversationMemberEntity agent = new ConversationMemberEntity();
        agent.setId(IdWorker.getId());
        agent.setConversationId(conversationId);
        agent.setUserId(actor.userId());
        agent.setMemberRole("AGENT");
        agent.setJoinedAt(now);
        memberMapper.insert(agent);
        receiptMapper.insertMissingOfflineHistory(conversationId, actor.userId(), now);
        return toConversationView(conversation, actor.userId());
    }

    @Transactional
    public ConversationView closeConversation(Actor actor, Long conversationId) {
        ConversationEntity conversation = requireConversationForUpdate(conversationId);
        requireMember(conversationId, actor.userId());
        if (CLOSED.equals(conversation.getStatus())) {
            return toConversationView(conversation, actor.userId());
        }
        if (!OPEN.equals(conversation.getStatus())) {
            throw new ChatException(ChatError.CONVERSATION_CLOSED);
        }
        conversation.setStatus(CLOSED);
        conversation.setVersion(conversation.getVersion() + 1);
        conversation.setUpdatedAt(outboxMapper.currentTime());
        if (conversationMapper.updateById(conversation) != 1) {
            throw new ChatException(ChatError.IDEMPOTENCY_CONFLICT);
        }
        return toConversationView(conversation, actor.userId());
    }

    @Transactional
    public MessageView sendMessage(Actor actor, Long conversationId, SendMessageCommand command) {
        ConversationEntity conversation = requireConversationForUpdate(conversationId);
        requireMember(conversationId, actor.userId());

        String messageType = command.messageType().trim().toUpperCase(Locale.ROOT);
        if (!TEXT.equals(messageType) && !IMAGE.equals(messageType) && !FILE.equals(messageType)) {
            throw new ChatException(ChatError.INVALID_MESSAGE_TYPE);
        }
        String content = command.content() == null ? "" : command.content().trim();
        if ((TEXT.equals(messageType) && content.isBlank()) || content.length() > 4000) {
            throw new ChatException(ChatError.INVALID_MESSAGE_CONTENT);
        }
        String attachmentIdentity = command.attachmentUploadIds().stream()
                .map(String::valueOf)
                .collect(java.util.stream.Collectors.joining(","));
        String requestHash = hash(messageType, content, attachmentIdentity);
        ChatMessageEntity existing = messageMapper.selectByClientMessageId(
                conversationId, actor.userId(), command.clientMessageId());
        if (existing != null) {
            if (!requestHash.equals(existing.getRequestHash())) {
                throw new ChatException(ChatError.IDEMPOTENCY_CONFLICT);
            }
            return toMessageView(existing);
        }
        if (!OPEN.equals(conversation.getStatus())) {
            throw new ChatException(ChatError.CONVERSATION_CLOSED);
        }
        List<ChatAttachmentUploadEntity> uploads = attachmentService.lockReadyUploads(
                actor,
                conversationId,
                messageType,
                command.attachmentUploadIds());

        Instant now = outboxMapper.currentTime();
        long nextSequence = conversation.getLastMessageSequence() + 1;
        conversation.setLastMessageSequence(nextSequence);
        conversation.setVersion(conversation.getVersion() + 1);
        conversation.setUpdatedAt(now);
        requireConversationUpdated(conversationMapper.updateById(conversation));

        ChatMessageEntity message = new ChatMessageEntity();
        message.setId(IdWorker.getId());
        message.setConversationId(conversationId);
        message.setSenderId(actor.userId());
        message.setClientMessageId(command.clientMessageId());
        message.setRequestHash(requestHash);
        message.setMessageSequence(nextSequence);
        message.setMessageType(messageType);
        message.setContent(content);
        message.setStatus(STORED);
        message.setCreatedAt(now);
        message.setUpdatedAt(now);
        messageMapper.insert(message);
        List<AttachmentView> attachments = attachmentService.bindToMessage(
                message.getId(),
                uploads,
                now);

        for (ConversationMemberEntity member : memberMapper.selectMembers(conversationId)) {
            if (!member.getUserId().equals(actor.userId())) {
                receiptMapper.insertOffline(message.getId(), member.getUserId(), now);
            }
        }
        insertMessageStoredOutbox(conversation, message, now);
        return toMessageView(message, attachments);
    }

    @Transactional(readOnly = true)
    public MessagePage listMessages(
            Actor actor,
            Long conversationId,
            Long beforeSequence,
            int size) {
        requireConversation(conversationId);
        requireMember(conversationId, actor.userId());

        List<ChatMessageEntity> selected = new ArrayList<>(
                messageMapper.selectPageBefore(conversationId, beforeSequence, size + 1));
        boolean hasMore = selected.size() > size;
        if (hasMore) {
            selected.remove(selected.size() - 1);
        }
        Long nextBeforeSequence = hasMore && !selected.isEmpty()
                ? selected.get(selected.size() - 1).getMessageSequence()
                : null;
        Collections.reverse(selected);
        Map<Long, List<AttachmentView>> attachments = attachmentService.attachmentsForMessages(
                selected.stream().map(ChatMessageEntity::getId).toList());
        return new MessagePage(
                selected.stream()
                        .map(message -> toMessageView(
                                message,
                                attachments.getOrDefault(message.getId(), List.of())))
                        .toList(),
                nextBeforeSequence,
                hasMore);
    }

    @Transactional
    public ReadView markRead(Actor actor, Long conversationId, Long lastReadMessageId) {
        requireConversationForUpdate(conversationId);
        ConversationMemberEntity member = requireMember(conversationId, actor.userId());
        ChatMessageEntity message = messageMapper.selectInConversation(conversationId, lastReadMessageId);
        if (message == null) {
            throw new ChatException(ChatError.MESSAGE_NOT_FOUND);
        }
        if (member.getLastReadMessageSequence() != null
                && member.getLastReadMessageSequence() >= message.getMessageSequence()) {
            return new ReadView(
                    conversationId,
                    member.getLastReadMessageId(),
                    member.getLastReadMessageSequence(),
                    member.getLastReadAt());
        }

        Instant now = outboxMapper.currentTime();
        memberMapper.advanceReadPosition(
                conversationId,
                actor.userId(),
                message.getId(),
                message.getMessageSequence(),
                now);
        receiptMapper.markExistingRead(conversationId, actor.userId(), message.getMessageSequence(), now);
        receiptMapper.insertMissingRead(conversationId, actor.userId(), message.getMessageSequence(), now);
        messageMapper.markReadThrough(
                conversationId,
                actor.userId(),
                message.getMessageSequence(),
                now);
        return new ReadView(conversationId, message.getId(), message.getMessageSequence(), now);
    }

    private ConversationEntity requireConversation(Long conversationId) {
        ConversationEntity conversation = conversationMapper.selectById(conversationId);
        if (conversation == null) {
            throw new ChatException(ChatError.CONVERSATION_NOT_FOUND);
        }
        return conversation;
    }

    private ConversationEntity requireConversationForUpdate(Long conversationId) {
        ConversationEntity conversation = conversationMapper.selectForUpdate(conversationId);
        if (conversation == null) {
            throw new ChatException(ChatError.CONVERSATION_NOT_FOUND);
        }
        return conversation;
    }

    private void requireConversationUpdated(int rows) {
        if (rows != 1) {
            throw new ChatException(ChatError.IDEMPOTENCY_CONFLICT);
        }
    }

    private ConversationMemberEntity requireMember(Long conversationId, Long userId) {
        ConversationMemberEntity member = memberMapper.selectMember(conversationId, userId);
        if (member == null) {
            throw new ChatException(ChatError.CONVERSATION_ACCESS_DENIED);
        }
        return member;
    }

    private ConversationView toConversationView(ConversationEntity conversation, Long viewerId) {
        return new ConversationView(
                conversation.getId(),
                conversation.getConversationNo(),
                conversation.getCustomerId(),
                conversation.getAssignedAgentId(),
                conversation.getSubject(),
                conversation.getContextType(),
                conversation.getContextId(),
                conversation.getStatus(),
                conversation.getLastMessageSequence(),
                messageMapper.countUnread(conversation.getId(), viewerId),
                conversation.getVersion(),
                conversation.getCreatedAt(),
                conversation.getUpdatedAt());
    }

    private MessageView toMessageView(ChatMessageEntity message) {
        return toMessageView(message, attachmentService.attachmentsForMessage(message.getId()));
    }

    private MessageView toMessageView(
            ChatMessageEntity message,
            List<AttachmentView> attachments) {
        return new MessageView(
                message.getId(),
                message.getConversationId(),
                message.getSenderId(),
                message.getClientMessageId(),
                message.getMessageSequence(),
                message.getMessageType(),
                message.getContent(),
                attachments,
                message.getStatus(),
                message.getCreatedAt());
    }

    private void insertMessageStoredOutbox(
            ConversationEntity conversation,
            ChatMessageEntity message,
            Instant now) {
        String eventId = UUID.randomUUID().toString();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("eventId", eventId);
        payload.put("eventType", "ChatMessageStored");
        payload.put("payloadVersion", 1);
        payload.put("occurredAt", now);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("conversationId", conversation.getId());
        data.put("conversationNo", conversation.getConversationNo());
        data.put("messageId", message.getId());
        data.put("messageSequence", message.getMessageSequence());
        data.put("senderId", message.getSenderId());
        data.put("messageType", message.getMessageType());
        data.put("status", message.getStatus());
        payload.put("payload", data);

        OutboxEventEntity event = new OutboxEventEntity();
        event.setId(eventId);
        event.setEventType("ChatMessageStored");
        event.setAggregateType("ChatConversation");
        event.setAggregateId(conversation.getConversationNo());
        event.setAggregateVersion(conversation.getVersion());
        event.setDestinationTopic(outboxTopic);
        event.setPayload(writeJson(payload));
        event.setStatus("PENDING");
        event.setAttempts(0);
        Instant persistedAt = now.truncatedTo(ChronoUnit.MILLIS);
        event.setNextAttemptAt(persistedAt);
        event.setCreatedAt(persistedAt);
        event.setUpdatedAt(persistedAt);
        outboxMapper.insert(event);
    }

    private String normalizeNullable(String value, boolean uppercase) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim();
        return uppercase ? normalized.toUpperCase(Locale.ROOT) : normalized;
    }

    private String hash(String... values) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String normalized = String.join("\u001f", java.util.Arrays.stream(values)
                    .map(value -> value == null ? "" : value)
                    .toList());
            return HexFormat.of().formatHex(digest.digest(normalized.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Chat event serialization failed", exception);
        }
    }
}
