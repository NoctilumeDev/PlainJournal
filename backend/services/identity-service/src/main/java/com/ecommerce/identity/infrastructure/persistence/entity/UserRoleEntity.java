package com.ecommerce.identity.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Getter
@Setter
@NoArgsConstructor
@TableName("user_role")
public class UserRoleEntity {

    private Long userId;
    private Long roleId;
    private Instant createdAt;
}
