package com.ecommerce.chat.infrastructure.realtime;

import com.ecommerce.chat.infrastructure.persistence.mapper.ChatMessageMapper;
import com.ecommerce.chat.infrastructure.persistence.mapper.MessageReceiptMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Service
public class ChatDeliveryStateService {

    private final MessageReceiptMapper receiptMapper;
    private final ChatMessageMapper messageMapper;

    public ChatDeliveryStateService(
            MessageReceiptMapper receiptMapper,
            ChatMessageMapper messageMapper) {
        this.receiptMapper = receiptMapper;
        this.messageMapper = messageMapper;
    }

    @Transactional
    public void markDelivered(Long messageId, Long recipientId) {
        Instant now = receiptMapper.currentTime();
        receiptMapper.markDelivered(messageId, recipientId, now);
        messageMapper.markDelivered(messageId, now);
    }
}
