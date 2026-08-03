package com.ecommerce.chat.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ecommerce.chat.infrastructure.persistence.entity.ChatAttachmentEntity;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface ChatAttachmentMapper extends BaseMapper<ChatAttachmentEntity> {

    @Select("""
            SELECT *
            FROM chat_attachment
            WHERE message_id = #{messageId}
            ORDER BY sort_order, id
            """)
    List<ChatAttachmentEntity> selectByMessageId(@Param("messageId") Long messageId);

    @Select("""
            <script>
            SELECT *
            FROM chat_attachment
            WHERE message_id IN
            <foreach collection="messageIds" item="messageId" open="(" separator="," close=")">
                #{messageId}
            </foreach>
            ORDER BY message_id, sort_order, id
            </script>
            """)
    List<ChatAttachmentEntity> selectByMessageIds(@Param("messageIds") List<Long> messageIds);

    @Select("""
            SELECT a.*
            FROM chat_attachment a
            JOIN chat_message m ON m.id = a.message_id
            WHERE a.id = #{attachmentId}
              AND a.message_id = #{messageId}
              AND m.conversation_id = #{conversationId}
            """)
    ChatAttachmentEntity selectInMessage(
            @Param("conversationId") Long conversationId,
            @Param("messageId") Long messageId,
            @Param("attachmentId") Long attachmentId);
}
