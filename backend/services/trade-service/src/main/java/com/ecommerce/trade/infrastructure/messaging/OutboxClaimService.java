package com.ecommerce.trade.infrastructure.messaging;

import com.ecommerce.trade.infrastructure.persistence.entity.OutboxEventEntity;
import com.ecommerce.trade.infrastructure.persistence.mapper.OutboxEventMapper;
import com.ecommerce.trade.infrastructure.sharding.TradeShardRouter;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class OutboxClaimService {

    private final OutboxEventMapper outboxMapper;
    private final TransactionTemplate transactionTemplate;
    private final TradeShardRouter shardRouter;
    private final AtomicInteger nextShard = new AtomicInteger();

    public OutboxClaimService(
            OutboxEventMapper outboxMapper,
            PlatformTransactionManager transactionManager,
            TradeShardRouter shardRouter) {
        this.outboxMapper = outboxMapper;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.transactionTemplate.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.shardRouter = shardRouter;
    }

    public ClaimBatch claimBatch(
            String owner,
            Duration leaseDuration,
            int limit) {
        List<ClaimedEvent> claimedEvents = new ArrayList<>();
        int staleClaimsRecovered = 0;
        int contendedClaims = 0;
        int shardCount = shardRouter.shardCount();
        int startShard = Math.floorMod(nextShard.getAndIncrement(), shardCount);
        for (int offset = 0; offset < shardCount
                && claimedEvents.size() < limit; offset++) {
            int shardIndex = (startShard + offset) % shardCount;
            int remaining = limit - claimedEvents.size();
            int currentShard = shardIndex;
            ClaimBatch shardBatch = shardRouter.executeOnShard(
                    currentShard,
                    () -> transactionTemplate.execute(
                            ignored -> claimCurrentShard(
                                    owner, leaseDuration, remaining, currentShard)));
            if (shardBatch != null) {
                claimedEvents.addAll(shardBatch.events());
                staleClaimsRecovered += shardBatch.staleClaimsRecovered();
                contendedClaims += shardBatch.contendedClaims();
            }
        }
        return new ClaimBatch(claimedEvents, staleClaimsRecovered, contendedClaims);
    }

    private ClaimBatch claimCurrentShard(
            String owner,
            Duration leaseDuration,
            int limit,
            int shardIndex) {
        Instant claimedAt = outboxMapper.currentTime();
        Instant claimUntil = claimedAt.plus(leaseDuration);
        int staleClaimsRecovered = 0;
        for (String eventId : outboxMapper.selectExpiredClaimIdsForUpdate(claimedAt, limit)) {
            staleClaimsRecovered += outboxMapper.resetStaleClaim(eventId, claimedAt);
        }
        List<ClaimedEvent> claimedEvents = new ArrayList<>();
        int contendedClaims = 0;
        for (OutboxEventEntity event : outboxMapper.selectPublishableForUpdate(claimedAt, limit)) {
            if (outboxMapper.claim(event.getId(), owner, claimedAt, claimUntil) == 1) {
                claimedEvents.add(new ClaimedEvent(shardIndex, event));
            } else {
                contendedClaims++;
            }
        }
        return new ClaimBatch(claimedEvents, staleClaimsRecovered, contendedClaims);
    }

    public record ClaimedEvent(
            int shardIndex,
            String id,
            String eventType,
            String aggregateType,
            String aggregateId,
            String destinationTopic,
            String payload) {

        public ClaimedEvent(int shardIndex, OutboxEventEntity event) {
            this(shardIndex, event.getId(), event.getEventType(),
                    event.getAggregateType(), event.getAggregateId(),
                    event.getDestinationTopic(), event.getPayload());
        }
    }

    public record ClaimBatch(
            List<ClaimedEvent> events,
            int staleClaimsRecovered,
            int contendedClaims) {

        public ClaimBatch {
            events = List.copyOf(events);
        }
    }
}
