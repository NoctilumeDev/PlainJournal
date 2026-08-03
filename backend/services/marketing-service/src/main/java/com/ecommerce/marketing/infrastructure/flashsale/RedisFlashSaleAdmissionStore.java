package com.ecommerce.marketing.infrastructure.flashsale;

import com.ecommerce.marketing.application.port.FlashSaleAdmissionStore;
import com.ecommerce.marketing.application.port.FlashSaleAdmissionStoreException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Component
@ConditionalOnProperty(
        prefix = "ecommerce.marketing.flash-sale",
        name = "redis-enabled",
        havingValue = "true",
        matchIfMissing = true)
public class RedisFlashSaleAdmissionStore implements FlashSaleAdmissionStore {

    private static final DefaultRedisScript<Long> PREHEAT_SCRIPT = new DefaultRedisScript<>("""
            if redis.call('EXISTS', KEYS[1]) == 1 then
                return 0
            end
            local limit = tonumber(ARGV[3])
            local admitted = tonumber(ARGV[4])
            local remaining = limit - admitted
            if remaining < 0 then
                return -1
            end
            redis.call('HSET', KEYS[1],
                'status', 'ACTIVE',
                'startsAt', ARGV[1],
                'endsAt', ARGV[2],
                'limit', ARGV[3],
                'remaining', tostring(remaining),
                'admitted', ARGV[4])
            redis.call('PEXPIRE', KEYS[1], ARGV[5])
            return 1
            """, Long.class);

    private static final DefaultRedisScript<String> ADMISSION_SCRIPT = new DefaultRedisScript<>("""
            local remaining = redis.call('HGET', KEYS[1], 'remaining')
            local endsAtValue = redis.call('HGET', KEYS[1], 'endsAt')
            local ttl = tonumber(ARGV[4])
            if endsAtValue then
                ttl = math.max(1000, tonumber(endsAtValue) - tonumber(ARGV[1]) + ttl)
            end
            local replayToken = redis.call('GET', KEYS[3])
            if replayToken then
                return 'REPLAYED|' .. replayToken .. '|' .. (remaining or '-1')
            end
            local userToken = redis.call('GET', KEYS[2])
            if userToken then
                redis.call('SET', KEYS[3], userToken, 'PX', ttl, 'NX')
                return 'REPLAYED|' .. userToken .. '|' .. (remaining or '-1')
            end

            local status = redis.call('HGET', KEYS[1], 'status')
            if not status then
                return 'NOT_READY||-1'
            end
            if status ~= 'ACTIVE' then
                return 'NOT_ACTIVE||-1'
            end
            local now = tonumber(ARGV[1])
            local startsAt = tonumber(redis.call('HGET', KEYS[1], 'startsAt'))
            local endsAt = tonumber(endsAtValue)
            if now < startsAt then
                return 'NOT_STARTED||' .. remaining
            end
            if now >= endsAt then
                return 'ENDED||' .. remaining
            end
            if tonumber(remaining) <= 0 then
                return 'SOLD_OUT||0'
            end

            local after = redis.call('HINCRBY', KEYS[1], 'remaining', -1)
            redis.call('HINCRBY', KEYS[1], 'admitted', 1)
            redis.call('SET', KEYS[2], ARGV[2], 'PX', ttl)
            redis.call('SET', KEYS[3], ARGV[2], 'PX', ttl)
            redis.call('HSET', KEYS[4],
                'activityNo', ARGV[3],
                'userId', ARGV[5],
                'status', 'ACCEPTED',
                'remaining', tostring(after),
                'acceptedAt', ARGV[1])
            redis.call('PEXPIRE', KEYS[4], ttl)
            return 'ACCEPTED|' .. ARGV[2] .. '|' .. tostring(after)
            """, String.class);

    private final StringRedisTemplate redisTemplate;
    private final FlashSaleAdmissionProperties properties;

    public RedisFlashSaleAdmissionStore(
            StringRedisTemplate redisTemplate,
            FlashSaleAdmissionProperties properties) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
    }

    @Override
    public void preheat(Activity activity, Instant now) {
        Duration ttl = ttl(activity.endsAt(), activity.resultRetention(), now);
        try {
            Long result = redisTemplate.execute(
                    PREHEAT_SCRIPT,
                    List.of(metaKey(activity.activityNo())),
                    Long.toString(activity.startsAt().toEpochMilli()),
                    Long.toString(activity.endsAt().toEpochMilli()),
                    Integer.toString(activity.admissionLimit()),
                    Integer.toString(activity.admittedCount()),
                    Long.toString(ttl.toMillis()));
            if (result == null || result < 0L) {
                throw new FlashSaleAdmissionStoreException(
                        "Flash-sale accepted count exceeds the configured admission limit");
            }
        } catch (FlashSaleAdmissionStoreException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new FlashSaleAdmissionStoreException("Unable to preheat flash-sale admission", exception);
        }
    }

    @Override
    public Decision admit(
            String activityNo,
            Long userId,
            String requestKey,
            String candidateToken,
            Instant now) {
        String metaKey = metaKey(activityNo);
        String userKey = prefix() + ":activity:" + activityNo + ":user:" + userId;
        String requestHash = hash(userId + ":" + requestKey);
        String idempotencyKey = prefix() + ":activity:" + activityNo + ":request:" + requestHash;
        String tokenKey = tokenKey(candidateToken);
        try {
            String raw = redisTemplate.execute(
                    ADMISSION_SCRIPT,
                    List.of(metaKey, userKey, idempotencyKey, tokenKey),
                    Long.toString(now.toEpochMilli()),
                    candidateToken,
                    activityNo,
                    Long.toString(properties.resultRetention().toMillis()),
                    Long.toString(userId));
            Decision decision = parse(raw, now);
            if (decision.outcome() != Outcome.REPLAYED) {
                return decision;
            }
            Snapshot snapshot = find(decision.requestToken())
                    .orElseThrow(() -> new FlashSaleAdmissionStoreException(
                            "Replayed flash-sale admission result is missing"));
            return new Decision(
                    Outcome.REPLAYED,
                    snapshot.requestToken(),
                    snapshot.remainingAdmissions(),
                    snapshot.acceptedAt());
        } catch (FlashSaleAdmissionStoreException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new FlashSaleAdmissionStoreException("Unable to execute flash-sale admission", exception);
        }
    }

    @Override
    public Optional<Snapshot> find(String requestToken) {
        try {
            Map<Object, Object> values = redisTemplate.opsForHash().entries(tokenKey(requestToken));
            if (values.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(new Snapshot(
                    requestToken,
                    required(values, "activityNo"),
                    Long.valueOf(required(values, "userId")),
                    required(values, "status"),
                    Integer.parseInt(required(values, "remaining")),
                    Instant.ofEpochMilli(Long.parseLong(required(values, "acceptedAt")))));
        } catch (FlashSaleAdmissionStoreException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new FlashSaleAdmissionStoreException("Unable to query flash-sale admission", exception);
        }
    }

    private Decision parse(String raw, Instant now) {
        if (raw == null || raw.isBlank()) {
            throw new FlashSaleAdmissionStoreException("Flash-sale admission script returned no result");
        }
        String[] parts = raw.split("\\|", -1);
        if (parts.length != 3) {
            throw new FlashSaleAdmissionStoreException("Flash-sale admission script returned an invalid result");
        }
        Outcome outcome;
        try {
            outcome = Outcome.valueOf(parts[0]);
        } catch (IllegalArgumentException exception) {
            throw new FlashSaleAdmissionStoreException("Flash-sale admission outcome is unknown", exception);
        }
        String token = parts[1].isBlank() ? null : parts[1];
        int remaining = Integer.parseInt(parts[2]);
        return new Decision(outcome, token, remaining,
                outcome == Outcome.ACCEPTED || outcome == Outcome.REPLAYED ? now : null);
    }

    private String required(Map<Object, Object> values, String field) {
        Object value = values.get(field);
        if (value == null || value.toString().isBlank()) {
            throw new FlashSaleAdmissionStoreException("Flash-sale admission result is incomplete");
        }
        return value.toString();
    }

    private Duration ttl(Instant endsAt, Duration retention, Instant now) {
        Duration duration = Duration.between(now, endsAt.plus(retention));
        return duration.isNegative() || duration.isZero() ? Duration.ofSeconds(1) : duration;
    }

    private String metaKey(String activityNo) {
        return prefix() + ":activity:" + activityNo + ":meta";
    }

    private String tokenKey(String requestToken) {
        return prefix() + ":token:" + requestToken;
    }

    private String prefix() {
        return "ecommerce:" + properties.namespace() + ":marketing:flash-sale";
    }

    private String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }
}
