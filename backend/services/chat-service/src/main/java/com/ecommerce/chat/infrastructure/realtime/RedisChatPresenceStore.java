package com.ecommerce.chat.infrastructure.realtime;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

@Component
@ConditionalOnProperty(
        prefix = "ecommerce.chat.realtime",
        name = "enabled",
        havingValue = "true")
public class RedisChatPresenceStore {

    private static final DefaultRedisScript<Long> REFRESH_SCRIPT = new DefaultRedisScript<>("""
            local time = redis.call('TIME')
            local now = (time[1] * 1000) + math.floor(time[2] / 1000)
            local presenceTtl = tonumber(ARGV[2])
            redis.call('SET', KEYS[1], tostring(now), 'PX', presenceTtl)
            redis.call('ZADD', KEYS[2], now + presenceTtl, ARGV[1])
            redis.call('PEXPIRE', KEYS[2], tonumber(ARGV[3]))
            return now
            """, Long.class);
    @SuppressWarnings("rawtypes")
    private static final DefaultRedisScript<List> ONLINE_NODES_SCRIPT =
            new DefaultRedisScript<>("""
                    local time = redis.call('TIME')
                    local now = (time[1] * 1000) + math.floor(time[2] / 1000)
                    redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', now)
                    local candidates = redis.call('ZRANGEBYSCORE', KEYS[1], now + 1, '+inf')
                    local online = {}
                    for _, nodeId in ipairs(candidates) do
                        if redis.call('EXISTS', ARGV[1] .. nodeId) == 1 then
                            table.insert(online, nodeId)
                        else
                            redis.call('ZREM', KEYS[1], nodeId)
                        end
                    end
                    return online
                    """, List.class);

    private final StringRedisTemplate redisTemplate;
    private final ChatRealtimeProperties properties;

    public RedisChatPresenceStore(
            StringRedisTemplate redisTemplate,
            ChatRealtimeProperties properties) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
    }

    public void register(Long userId) {
        refreshUser(userId);
    }

    public void refresh(Set<Long> userIds) {
        if (userIds.isEmpty()) {
            return;
        }
        for (Long userId : userIds) {
            refreshUser(userId);
        }
    }

    public void unregister(Long userId) {
        redisTemplate.opsForZSet().remove(userRoutesKey(userId), properties.nodeId());
    }

    public Set<String> onlineNodes(Long userId) {
        @SuppressWarnings("unchecked")
        List<String> candidates = redisTemplate.execute(
                ONLINE_NODES_SCRIPT,
                List.of(userRoutesKey(userId)),
                nodeKeyPrefix());
        if (candidates == null || candidates.isEmpty()) {
            return Set.of();
        }
        return Set.copyOf(candidates);
    }

    public boolean isNodeOnline(String nodeId) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(nodeKey(nodeId)));
    }

    private void refreshUser(Long userId) {
        redisTemplate.execute(
                REFRESH_SCRIPT,
                List.of(
                nodeKey(properties.nodeId()),
                        userRoutesKey(userId)),
                properties.nodeId(),
                Long.toString(properties.presenceTtl().toMillis()),
                Long.toString(properties.presenceTtl().multipliedBy(3).toMillis()));
    }

    private String nodeKey(String nodeId) {
        return nodeKeyPrefix() + nodeId;
    }

    private String nodeKeyPrefix() {
        return properties.redisKeyPrefix() + "node:";
    }

    private String userRoutesKey(Long userId) {
        return properties.redisKeyPrefix() + "user:" + userId + ":nodes";
    }
}
