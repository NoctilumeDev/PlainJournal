package com.ecommerce.identity.infrastructure.persistence.mapper;

import com.ecommerce.identity.infrastructure.persistence.entity.UserRoleEntity;
import org.apache.ibatis.annotations.Insert;

public interface UserRoleMapper {

    @Insert("""
            INSERT INTO user_role (user_id, role_id, created_at)
            VALUES (#{userId}, #{roleId}, #{createdAt})
            """)
    int insertAssignment(UserRoleEntity assignment);
}
