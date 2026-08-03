package com.ecommerce.chat.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ecommerce.chat.infrastructure.persistence.entity.ConversationMemberEntity;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.Instant;
import java.util.List;

public interface ConversationMemberMapper extends BaseMapper<ConversationMemberEntity> {

    @Select("""
            SELECT *
            FROM conversation_member
            WHERE conversation_id = #{conversationId} AND user_id = #{userId}
            """)
    ConversationMemberEntity selectMember(
            @Param("conversationId") Long conversationId,
            @Param("userId") Long userId);

    @Select("""
            SELECT *
            FROM conversation_member
            WHERE conversation_id = #{conversationId}
            ORDER BY id
            """)
    List<ConversationMemberEntity> selectMembers(@Param("conversationId") Long conversationId);

    @Select("""
            SELECT user_id
            FROM conversation_member
            WHERE conversation_id = #{conversationId}
              AND user_id <> #{senderId}
            ORDER BY id
            """)
    List<Long> selectRecipientIds(
            @Param("conversationId") Long conversationId,
            @Param("senderId") Long senderId);

    @Update("""
            UPDATE conversation_member
            SET last_read_message_id = #{messageId},
                last_read_message_sequence = #{messageSequence},
                last_read_at = #{readAt}
            WHERE conversation_id = #{conversationId}
              AND user_id = #{userId}
              AND (
                  last_read_message_sequence IS NULL
                  OR last_read_message_sequence < #{messageSequence}
              )
            """)
    int advanceReadPosition(
            @Param("conversationId") Long conversationId,
            @Param("userId") Long userId,
            @Param("messageId") Long messageId,
            @Param("messageSequence") long messageSequence,
            @Param("readAt") Instant readAt);
}
