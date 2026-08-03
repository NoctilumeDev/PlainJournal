package com.ecommerce.trade.infrastructure.persistence.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.Instant;

public interface ConsumedEventMapper {

    @Insert("""
            INSERT IGNORE INTO consumed_event
                (event_id, consumer_group, owner_user_id, payload_fingerprint, consumed_at)
            VALUES
                (#{eventId}, #{consumerGroup}, #{ownerUserId}, #{payloadFingerprint}, #{consumedAt})
            """)
    int insertIfAbsent(
            @Param("eventId") String eventId,
            @Param("consumerGroup") String consumerGroup,
            @Param("ownerUserId") Long ownerUserId,
            @Param("payloadFingerprint") String payloadFingerprint,
            @Param("consumedAt") Instant consumedAt);

    @Select("""
            SELECT owner_user_id
            FROM consumed_event
            WHERE event_id = #{eventId}
              AND consumer_group = #{consumerGroup}
            """)
    Long selectOwnerUserId(
            @Param("eventId") String eventId,
            @Param("consumerGroup") String consumerGroup);

    @Select("""
            SELECT payload_fingerprint
            FROM consumed_event
            WHERE event_id = #{eventId}
              AND consumer_group = #{consumerGroup}
            """)
    String selectPayloadFingerprint(
            @Param("eventId") String eventId,
            @Param("consumerGroup") String consumerGroup);
}
