package com.ecommerce.identity.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Getter
@Setter
@NoArgsConstructor
@TableName("user_account")
public class UserAccountEntity {

    @TableId
    private Long id;
    private String email;
    private String passwordHash;
    private String displayName;
    private String status;
    @Version
    private Integer version;
    private Instant createdAt;
    private Instant updatedAt;
}
