package com.ecommerce.marketing.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ecommerce.marketing.infrastructure.persistence.entity.FlashSaleActivityEntity;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.Instant;

public interface FlashSaleActivityMapper extends BaseMapper<FlashSaleActivityEntity> {

    @Select("SELECT CURRENT_TIMESTAMP(3)")
    Instant currentTime();

    @Select("SELECT * FROM flash_sale_activity WHERE activity_no = #{activityNo}")
    FlashSaleActivityEntity selectByActivityNo(@Param("activityNo") String activityNo);

    @Select("SELECT * FROM flash_sale_activity WHERE activity_no = #{activityNo} FOR UPDATE")
    FlashSaleActivityEntity selectByActivityNoForUpdate(@Param("activityNo") String activityNo);
}
