package com.ecommerce.marketing.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ecommerce.marketing.infrastructure.persistence.entity.FlashSaleAdmissionEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.Instant;
import java.util.List;

public interface FlashSaleAdmissionMapper extends BaseMapper<FlashSaleAdmissionEntity> {

    @Select("SELECT CURRENT_TIMESTAMP(3)")
    Instant currentTime();

    @Insert("""
            INSERT INTO flash_sale_admission
                (id, request_token, activity_no, user_id, address_id, request_hash, status,
                 remaining_admissions, order_no, failure_code, version, accepted_at,
                 completed_at, created_at, updated_at)
            VALUES
                (#{entity.id}, #{entity.requestToken}, #{entity.activityNo}, #{entity.userId},
                 #{entity.addressId}, #{entity.requestHash}, #{entity.status},
                 #{entity.remainingAdmissions}, #{entity.orderNo}, #{entity.failureCode},
                 #{entity.version}, #{entity.acceptedAt}, #{entity.completedAt},
                 #{entity.createdAt}, #{entity.updatedAt})
            ON DUPLICATE KEY UPDATE id = id
            """)
    int insertOrLockExisting(@Param("entity") FlashSaleAdmissionEntity entity);

    @Select("SELECT * FROM flash_sale_admission WHERE request_token = #{requestToken} FOR UPDATE")
    FlashSaleAdmissionEntity selectByTokenForUpdate(@Param("requestToken") String requestToken);

    @Select("""
            SELECT * FROM flash_sale_admission
            WHERE activity_no = #{activityNo} AND user_id = #{userId}
            FOR UPDATE
            """)
    FlashSaleAdmissionEntity selectByActivityAndUserForUpdate(
            @Param("activityNo") String activityNo,
            @Param("userId") Long userId);

    @Select("SELECT * FROM flash_sale_admission WHERE request_token = #{requestToken}")
    FlashSaleAdmissionEntity selectByToken(@Param("requestToken") String requestToken);

    @Select("""
            SELECT request_token
            FROM flash_sale_admission
            WHERE status = 'ADMISSION_PENDING'
            ORDER BY created_at, id
            LIMIT #{limit}
            """)
    List<String> selectPendingTokens(@Param("limit") int limit);

    @Select("""
            SELECT COUNT(*)
            FROM flash_sale_admission
            WHERE activity_no = #{activityNo}
              AND status IN ('QUEUED', 'ORDER_CREATED', 'FAILED', 'RESULT_UNKNOWN')
            """)
    int countAcceptedByActivity(@Param("activityNo") String activityNo);

    @Select("""
            SELECT request_token
            FROM flash_sale_admission
            WHERE status = 'QUEUED' AND accepted_at <= #{acceptedBefore}
            ORDER BY accepted_at, id
            LIMIT #{limit}
            """)
    List<String> selectTimedOutTokens(
            @Param("acceptedBefore") Instant acceptedBefore,
            @Param("limit") int limit);

    @Update("""
            UPDATE flash_sale_admission
            SET status = 'RESULT_UNKNOWN',
                failure_code = 'PROCESSING_TIMEOUT',
                version = version + 1,
                updated_at = #{now}
            WHERE request_token = #{requestToken}
              AND status = 'QUEUED'
            """)
    int markResultUnknown(
            @Param("requestToken") String requestToken,
            @Param("now") Instant now);
}
