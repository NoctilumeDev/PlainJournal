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
@TableName("chat_attachment")
public class ChatAttachmentEntity {
    @TableId
    private Long id;
    private Long messageId;
    private Long uploadId;
    private String objectKey;
    private String originalFilename;
    private String mimeType;
    private Long sizeBytes;
    private String sha256;
    private Integer sortOrder;
    private Instant createdAt;
}
