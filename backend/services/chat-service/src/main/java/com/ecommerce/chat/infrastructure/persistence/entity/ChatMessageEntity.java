package com.ecommerce.chat.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Getter
@Setter
@NoArgsConstructor
@TableName("chat_message")
public class ChatMessageEntity {
    @TableId
    private Long id;
    private Long conversationId;
    private Long senderId;
    private String clientMessageId;
    private String requestHash;
    private Long messageSequence;
    private String messageType;
    private String content;
    private String status;
    private Instant createdAt;
    private Instant updatedAt;
}
