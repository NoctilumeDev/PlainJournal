package com.ecommerce.identity.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Getter
@Setter
@NoArgsConstructor
@TableName("login_record")
public class LoginRecordEntity {

    @TableId
    private Long id;
    private Long userId;
    private String normalizedEmail;
    private Boolean successful;
    private String failureCode;
    private String clientIp;
    private String userAgent;
    private Instant createdAt;
}
