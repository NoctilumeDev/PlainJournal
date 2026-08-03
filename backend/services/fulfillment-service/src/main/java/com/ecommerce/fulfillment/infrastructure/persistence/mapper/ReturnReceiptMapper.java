package com.ecommerce.fulfillment.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ecommerce.fulfillment.infrastructure.persistence.entity.ReturnReceiptEntity;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.Instant;

public interface ReturnReceiptMapper extends BaseMapper<ReturnReceiptEntity> {

    @Select("SELECT CURRENT_TIMESTAMP(3)")
    Instant currentTime();

    @Select("SELECT * FROM return_receipt WHERE return_receipt_no = #{returnReceiptNo} FOR UPDATE")
    ReturnReceiptEntity selectByReceiptNoForUpdate(@Param("returnReceiptNo") String returnReceiptNo);

    @Select("SELECT * FROM return_receipt WHERE after_sale_no = #{afterSaleNo} FOR UPDATE")
    ReturnReceiptEntity selectByAfterSaleNoForUpdate(@Param("afterSaleNo") String afterSaleNo);
}
