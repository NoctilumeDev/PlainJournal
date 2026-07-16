package com.ecommerce.marketing.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ecommerce.marketing.infrastructure.persistence.entity.UserBenefitEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface UserBenefitMapper extends BaseMapper<UserBenefitEntity> {

    @Insert("""
            INSERT IGNORE INTO user_benefit
                (id, benefit_no, grant_key, rule_id, user_id, status, locked_order_no,
                 locked_at, redeemed_order_no, redeemed_at, version, created_at, updated_at)
            VALUES
                (#{entity.id}, #{entity.benefitNo}, #{entity.grantKey}, #{entity.ruleId},
                 #{entity.userId}, #{entity.status}, #{entity.lockedOrderNo}, #{entity.lockedAt},
                 #{entity.redeemedOrderNo}, #{entity.redeemedAt}, #{entity.version},
                 #{entity.createdAt}, #{entity.updatedAt})
            """)
    int insertIfAbsent(@Param("entity") UserBenefitEntity entity);

    @Select("SELECT * FROM user_benefit WHERE benefit_no = #{benefitNo} FOR UPDATE")
    UserBenefitEntity selectByBenefitNoForUpdate(@Param("benefitNo") String benefitNo);

    @Select("SELECT * FROM user_benefit WHERE user_id = #{userId} AND grant_key = #{grantKey} FOR UPDATE")
    UserBenefitEntity selectByGrantKeyForUpdate(@Param("userId") Long userId, @Param("grantKey") String grantKey);
}
