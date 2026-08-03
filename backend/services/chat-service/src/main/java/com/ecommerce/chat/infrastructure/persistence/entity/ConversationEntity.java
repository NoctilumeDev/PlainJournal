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
@TableName("chat_conversation")
public class ConversationEntity {
    @TableId
    private Long id;
    private String conversationNo;
    private Long customerId;
    private Long assignedAgentId;
    private String clientConversationId;
    private String requestHash;
    private String subject;
    private String contextType;
    private String contextId;
    private String status;
    private Long lastMessageSequence;
    private Integer version;
    private Instant createdAt;
    private Instant updatedAt;
}
