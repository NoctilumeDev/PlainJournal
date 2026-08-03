package com.ecommerce.trade.infrastructure.id;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import com.ecommerce.trade.infrastructure.sharding.TradeShardRouter;

import java.sql.Timestamp;
import java.time.Instant;

@Repository
public class DistributedIdWorkerLeaseStore {

    private final JdbcTemplate jdbcTemplate;
    private final TradeShardRouter shardRouter;

    public DistributedIdWorkerLeaseStore(JdbcTemplate jdbcTemplate, TradeShardRouter shardRouter) {
        this.jdbcTemplate = jdbcTemplate;
        this.shardRouter = shardRouter;
    }

    public Instant currentTime() {
        return shardRouter.executeOnShard(0, () -> {
            Timestamp value = jdbcTemplate.queryForObject(
                    "SELECT CURRENT_TIMESTAMP(3)",
                    Timestamp.class);
            if (value == null) {
                throw new IllegalStateException("database did not return a lease clock value");
            }
            return value.toInstant();
        });
    }

    public boolean tryAcquire(
            String namespace,
            int workerId,
            String owner,
            Instant now,
            Instant leaseUntil) {
        return shardRouter.executeOnShard(0, () -> tryAcquireOnPrimaryShard(
                namespace, workerId, owner, now, leaseUntil));
    }

    private boolean tryAcquireOnPrimaryShard(
            String namespace,
            int workerId,
            String owner,
            Instant now,
            Instant leaseUntil) {
        Timestamp nowValue = Timestamp.from(now);
        Timestamp leaseUntilValue = Timestamp.from(leaseUntil);
        try {
            jdbcTemplate.update("""
                    INSERT INTO distributed_id_worker_lease
                        (namespace, worker_id, lease_owner, lease_until, lease_version, created_at, updated_at)
                    VALUES (?, ?, ?, ?, 0, ?, ?)
                    """, namespace, workerId, owner, leaseUntilValue, nowValue, nowValue);
        } catch (DuplicateKeyException ignored) {
            // The conditional update below is the ownership decision.
        }
        return jdbcTemplate.update("""
                UPDATE distributed_id_worker_lease
                SET lease_owner = ?, lease_until = ?, lease_version = lease_version + 1, updated_at = ?
                WHERE namespace = ?
                  AND worker_id = ?
                  AND (lease_until <= ? OR lease_owner = ?)
                """, owner, leaseUntilValue, nowValue, namespace, workerId, nowValue, owner) == 1;
    }

    public boolean renew(
            String namespace,
            int workerId,
            String owner,
            Instant now,
            Instant leaseUntil) {
        return shardRouter.executeOnShard(0, () -> jdbcTemplate.update("""
                UPDATE distributed_id_worker_lease
                SET lease_until = ?, lease_version = lease_version + 1, updated_at = ?
                WHERE namespace = ?
                  AND worker_id = ?
                  AND lease_owner = ?
                  AND lease_until > ?
                """, Timestamp.from(leaseUntil), Timestamp.from(now),
                namespace, workerId, owner, Timestamp.from(now)) == 1);
    }

    public boolean release(String namespace, int workerId, String owner) {
        return shardRouter.executeOnShard(0, () -> jdbcTemplate.update("""
                DELETE FROM distributed_id_worker_lease
                WHERE namespace = ? AND worker_id = ? AND lease_owner = ?
                """, namespace, workerId, owner) == 1);
    }
}
