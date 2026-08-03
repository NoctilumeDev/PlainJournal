package com.ecommerce.chat.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ecommerce.chat.infrastructure.persistence.entity.ConversationEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface ConversationMapper extends BaseMapper<ConversationEntity> {

    @Insert("""
            INSERT INTO chat_conversation (
                id, conversation_no, customer_id, assigned_agent_id,
                client_conversation_id, request_hash, subject, context_type,
                context_id, status, last_message_sequence, version, created_at, updated_at
            ) VALUES (
                #{id}, #{conversationNo}, #{customerId}, #{assignedAgentId},
                #{clientConversationId}, #{requestHash}, #{subject}, #{contextType},
                #{contextId}, #{status}, #{lastMessageSequence}, #{version}, #{createdAt}, #{updatedAt}
            )
            ON DUPLICATE KEY UPDATE id = chat_conversation.id
            """)
    int insertIdempotent(ConversationEntity entity);

    @Select("""
            SELECT *
            FROM chat_conversation
            WHERE customer_id = #{customerId}
              AND client_conversation_id = #{clientConversationId}
            """)
    ConversationEntity selectByClientConversationId(
            @Param("customerId") Long customerId,
            @Param("clientConversationId") String clientConversationId);

    @Select("SELECT * FROM chat_conversation WHERE id = #{id} FOR UPDATE")
    ConversationEntity selectForUpdate(@Param("id") Long id);

    @Select("""
            SELECT c.*
            FROM chat_conversation c
            JOIN conversation_member m ON m.conversation_id = c.id
            WHERE m.user_id = #{userId}
            ORDER BY c.updated_at DESC, c.id DESC
            LIMIT #{limit}
            """)
    List<ConversationEntity> selectForMember(
            @Param("userId") Long userId,
            @Param("limit") int limit);

    @Select("""
            SELECT *
            FROM chat_conversation
            WHERE status = 'OPEN'
            ORDER BY CASE WHEN assigned_agent_id IS NULL THEN 0 ELSE 1 END,
                     updated_at DESC, id DESC
            LIMIT #{limit}
            """)
    List<ConversationEntity> selectForSupport(@Param("limit") int limit);
}
