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
@TableName("conversation_member")
public class ConversationMemberEntity {
    @TableId
    private Long id;
    private Long conversationId;
    private Long userId;
    private String memberRole;
    private Long lastReadMessageId;
    private Long lastReadMessageSequence;
    private Instant lastReadAt;
    private Instant joinedAt;
}
