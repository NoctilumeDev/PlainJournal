package com.ecommerce.marketing.infrastructure.persistence.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.Instant;

public interface ConsumedEventMapper {

    @Insert("""
            INSERT IGNORE INTO consumed_event
                (event_id, consumer_group, payload_fingerprint, consumed_at)
            VALUES (#{eventId}, #{consumerGroup}, #{payloadFingerprint}, #{consumedAt})
            """)
    int insertIfAbsent(
            @Param("eventId") String eventId,
            @Param("consumerGroup") String consumerGroup,
            @Param("payloadFingerprint") String payloadFingerprint,
            @Param("consumedAt") Instant consumedAt);

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
