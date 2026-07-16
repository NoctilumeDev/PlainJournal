package com.ecommerce.fulfillment.infrastructure.persistence.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;

import java.time.Instant;

public interface ConsumedEventMapper {

    @Insert("""
            INSERT IGNORE INTO consumed_event (event_id, consumer_group, consumed_at)
            VALUES (#{eventId}, #{consumerGroup}, #{consumedAt})
            """)
    int insertIfAbsent(
            @Param("eventId") String eventId,
            @Param("consumerGroup") String consumerGroup,
            @Param("consumedAt") Instant consumedAt);
}
