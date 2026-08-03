package com.ecommerce.identity.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ecommerce.identity.infrastructure.persistence.entity.UserAccountEntity;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.Instant;

public interface UserAccountMapper extends BaseMapper<UserAccountEntity> {

    @Select("SELECT CURRENT_TIMESTAMP(3)")
    Instant currentTime();

    @Select("SELECT id FROM user_account WHERE id = #{userId} FOR UPDATE")
    Long lockUser(@Param("userId") Long userId);
}
