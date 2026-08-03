package com.ecommerce.chat.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ecommerce.chat.infrastructure.persistence.entity.ChatMessageEntity;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.Instant;
import java.util.List;

public interface ChatMessageMapper extends BaseMapper<ChatMessageEntity> {

    @Select("""
            SELECT *
            FROM chat_message
            WHERE conversation_id = #{conversationId}
              AND sender_id = #{senderId}
              AND client_message_id = #{clientMessageId}
            """)
    ChatMessageEntity selectByClientMessageId(
            @Param("conversationId") Long conversationId,
            @Param("senderId") Long senderId,
            @Param("clientMessageId") String clientMessageId);

    @Select("""
            SELECT *
            FROM chat_message
            WHERE conversation_id = #{conversationId}
              AND (#{beforeSequence} IS NULL OR message_sequence < #{beforeSequence})
            ORDER BY message_sequence DESC
            LIMIT #{limit}
            """)
    List<ChatMessageEntity> selectPageBefore(
            @Param("conversationId") Long conversationId,
            @Param("beforeSequence") Long beforeSequence,
            @Param("limit") int limit);

    @Select("""
            SELECT *
            FROM chat_message
            WHERE id = #{messageId} AND conversation_id = #{conversationId}
            """)
    ChatMessageEntity selectInConversation(
            @Param("conversationId") Long conversationId,
            @Param("messageId") Long messageId);

    @Select("""
            SELECT COUNT(*)
            FROM chat_message m
            JOIN conversation_member cm
              ON cm.conversation_id = m.conversation_id
             AND cm.user_id = #{userId}
            WHERE m.conversation_id = #{conversationId}
              AND m.sender_id <> #{userId}
              AND m.message_sequence > COALESCE(cm.last_read_message_sequence, 0)
            """)
    long countUnread(
            @Param("conversationId") Long conversationId,
            @Param("userId") Long userId);

    @Update("""
            UPDATE chat_message
            SET status = 'DISPATCHED', updated_at = #{now}
            WHERE id = #{messageId} AND status = 'STORED'
            """)
    int markDispatched(
            @Param("messageId") Long messageId,
            @Param("now") Instant now);

    @Select("SELECT status FROM chat_message WHERE id = #{messageId}")
    String selectStatus(@Param("messageId") Long messageId);

    @Select("""
            SELECT m.*
            FROM chat_message m
            JOIN message_receipt r ON r.message_id = m.id
            WHERE r.recipient_id = #{recipientId}
              AND r.state = 'OFFLINE'
            ORDER BY m.created_at, m.id
            LIMIT #{limit}
            """)
    List<ChatMessageEntity> selectOfflineForRecipient(
            @Param("recipientId") Long recipientId,
            @Param("limit") int limit);

    @Update("""
            UPDATE chat_message
            SET status = 'DELIVERED', updated_at = #{now}
            WHERE id = #{messageId}
              AND status IN ('STORED', 'DISPATCHED')
            """)
    int markDelivered(
            @Param("messageId") Long messageId,
            @Param("now") Instant now);

    @Update("""
            UPDATE chat_message
            SET status = 'READ', updated_at = #{now}
            WHERE conversation_id = #{conversationId}
              AND sender_id <> #{recipientId}
              AND message_sequence <= #{messageSequence}
              AND status IN ('STORED', 'DISPATCHED', 'DELIVERED')
            """)
    int markReadThrough(
            @Param("conversationId") Long conversationId,
            @Param("recipientId") Long recipientId,
            @Param("messageSequence") long messageSequence,
            @Param("now") Instant now);
}
