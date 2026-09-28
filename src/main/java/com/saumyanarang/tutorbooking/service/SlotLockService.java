package com.saumyanarang.tutorbooking.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * Short-lived distributed lock per slot, backed by Redis.
 *
 * Acquire = SET booking:lock:slot:{id} <token> NX PX 5000  (atomic "create only if absent, auto-expire")
 * Release = delete the key ONLY if it still holds our token (Lua script, so check+delete is atomic).
 */
@Service
public class SlotLockService {

    private static final Logger logger = LoggerFactory.getLogger(SlotLockService.class);
    private static final Duration LOCK_TTL = Duration.ofSeconds(5);

    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
            Long.class);

    private final StringRedisTemplate redis;

    public SlotLockService(RedisConnectionFactory connectionFactory) {
        this.redis = new StringRedisTemplate(connectionFactory);
    }

    /** @return a unique token if the lock was acquired, or null if someone else holds it. */
    public String tryLock(Long slotId) {
        String token = UUID.randomUUID().toString();
        Boolean acquired = redis.opsForValue().setIfAbsent(key(slotId), token, LOCK_TTL);
        if (Boolean.TRUE.equals(acquired)) {
            logger.debug("Acquired lock for slot {}", slotId);
            return token;
        }
        logger.debug("Lock for slot {} is held by another request", slotId);
        return null;
    }

    /** Releases the lock only if we still own it (protects against deleting a lock that expired and was re-acquired). */
    public void unlock(Long slotId, String token) {
        Long deleted = redis.execute(UNLOCK_SCRIPT, List.of(key(slotId)), token);
        if (deleted == null || deleted == 0L) {
            logger.warn("Lock for slot {} was not released by us (already expired or taken over)", slotId);
        }
    }

    private String key(Long slotId) {
        return "booking:lock:slot:" + slotId;
    }
}
