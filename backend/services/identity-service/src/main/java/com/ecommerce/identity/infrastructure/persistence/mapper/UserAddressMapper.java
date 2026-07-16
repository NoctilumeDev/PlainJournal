package com.ecommerce.identity.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ecommerce.identity.infrastructure.persistence.entity.UserAddressEntity;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface UserAddressMapper extends BaseMapper<UserAddressEntity> {

    @Select("SELECT * FROM user_address WHERE id = #{addressId} AND user_id = #{userId} FOR UPDATE")
    UserAddressEntity selectOwnedForUpdate(@Param("userId") Long userId, @Param("addressId") Long addressId);

    @Update("UPDATE user_address SET is_default = FALSE, updated_at = #{updatedAt} " +
            "WHERE user_id = #{userId} AND is_default = TRUE")
    int clearDefault(@Param("userId") Long userId, @Param("updatedAt") java.time.Instant updatedAt);
}
