package com.ecommerce.chat.infrastructure.persistence.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.Instant;

public interface MessageReceiptMapper {

    @Select("SELECT CURRENT_TIMESTAMP(3)")
    Instant currentTime();

    @Select("""
            SELECT state
            FROM message_receipt
            WHERE message_id = #{messageId} AND recipient_id = #{recipientId}
            """)
    String selectState(
            @Param("messageId") Long messageId,
            @Param("recipientId") Long recipientId);

    @Insert("""
            INSERT INTO message_receipt (
                message_id, recipient_id, state, delivered_at, read_at, updated_at
            ) VALUES (
                #{messageId}, #{recipientId}, 'OFFLINE', NULL, NULL, #{now}
            )
            """)
    int insertOffline(
            @Param("messageId") Long messageId,
            @Param("recipientId") Long recipientId,
            @Param("now") Instant now);

    @Insert("""
            INSERT INTO message_receipt (
                message_id, recipient_id, state, delivered_at, read_at, updated_at
            )
            SELECT m.id, #{recipientId}, 'OFFLINE', NULL, NULL, #{now}
            FROM chat_message m
            WHERE m.conversation_id = #{conversationId}
              AND m.sender_id <> #{recipientId}
              AND NOT EXISTS (
                  SELECT 1
                  FROM message_receipt r
                  WHERE r.message_id = m.id AND r.recipient_id = #{recipientId}
              )
            """)
    int insertMissingOfflineHistory(
            @Param("conversationId") Long conversationId,
            @Param("recipientId") Long recipientId,
            @Param("now") Instant now);

    @Update("""
            UPDATE message_receipt
            SET state = 'READ', read_at = #{now}, updated_at = #{now}
            WHERE recipient_id = #{recipientId}
              AND message_id IN (
                  SELECT id
                  FROM chat_message
                  WHERE conversation_id = #{conversationId}
                    AND sender_id <> #{recipientId}
                    AND message_sequence <= #{messageSequence}
              )
              AND state <> 'READ'
            """)
    int markExistingRead(
            @Param("conversationId") Long conversationId,
            @Param("recipientId") Long recipientId,
            @Param("messageSequence") long messageSequence,
            @Param("now") Instant now);

    @Insert("""
            INSERT INTO message_receipt (
                message_id, recipient_id, state, delivered_at, read_at, updated_at
            )
            SELECT m.id, #{recipientId}, 'READ', NULL, #{now}, #{now}
            FROM chat_message m
            WHERE m.conversation_id = #{conversationId}
              AND m.sender_id <> #{recipientId}
              AND m.message_sequence <= #{messageSequence}
              AND NOT EXISTS (
                  SELECT 1
                  FROM message_receipt r
                  WHERE r.message_id = m.id AND r.recipient_id = #{recipientId}
              )
            """)
    int insertMissingRead(
            @Param("conversationId") Long conversationId,
            @Param("recipientId") Long recipientId,
            @Param("messageSequence") long messageSequence,
            @Param("now") Instant now);

    @Update("""
            UPDATE message_receipt
            SET state = 'DELIVERED',
                delivered_at = COALESCE(delivered_at, #{now}),
                updated_at = #{now}
            WHERE message_id = #{messageId}
              AND recipient_id = #{recipientId}
              AND state = 'OFFLINE'
            """)
    int markDelivered(
            @Param("messageId") Long messageId,
            @Param("recipientId") Long recipientId,
            @Param("now") Instant now);
}
