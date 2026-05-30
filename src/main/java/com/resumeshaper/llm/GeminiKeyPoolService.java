package com.resumeshaper.llm;

import com.resumeshaper.common.exception.GeminiQuotaExhaustedException;
import com.resumeshaper.config.AppProperties;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Manages a Redis Sorted Set of Gemini API keys.
 *
 * Score = active_count / quota_limit  →  lowest-load key always at ZPOPMIN.
 * Keys with higher RPM quotas absorb more traffic proportionally.
 *
 * All acquire/release ops are atomic Lua scripts — safe under concurrent workers.
 *
 * Fallback: if no key-pool is configured, returns null (single-key mode).
 * GeminiApiClient treats null keyId as "use app.gemini.api-key directly".
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GeminiKeyPoolService {

    // ── Redis key names ───────────────────────────────────────────────────────
    static final String POOL_ZSET   = "gemini:keypool";        // sorted set: keyId → load score
    static final String QUOTA_HASH  = "gemini:key:quotas";     // hash: keyId → quota (int)
    static final String ACTIVE_HASH = "gemini:key:active";     // hash: keyId → active count
    static final String SECRET_HASH = "gemini:key:secrets";    // hash: keyId → raw API key

    // ── Lua: atomically acquire least-loaded key ──────────────────────────────
    // KEYS[1]=POOL_ZSET  KEYS[2]=QUOTA_HASH  KEYS[3]=ACTIVE_HASH
    // Returns keyId string, or false if pool is empty.
    private static final DefaultRedisScript<String> ACQUIRE_SCRIPT =
            new DefaultRedisScript<>("""
            local r     = redis.call('ZPOPMIN', KEYS[1])
            if #r == 0 then return false end
            local kid   = r[1]
            local quota = tonumber(redis.call('HGET', KEYS[2], kid)) or 100
            local cnt   = tonumber(redis.call('HINCRBY', KEYS[3], kid, 1))
            redis.call('ZADD', KEYS[1], cnt / quota, kid)
            return kid
            """, String.class);

    // ── Lua: atomically release key ───────────────────────────────────────────
    // KEYS[1]=POOL_ZSET  KEYS[2]=QUOTA_HASH  KEYS[3]=ACTIVE_HASH  ARGV[1]=keyId
    private static final DefaultRedisScript<Long> RELEASE_SCRIPT =
            new DefaultRedisScript<>("""
            local kid   = ARGV[1]
            local quota = tonumber(redis.call('HGET', KEYS[2], kid)) or 100
            local cnt   = tonumber(redis.call('HINCRBY', KEYS[3], kid, -1))
            if cnt < 0 then cnt = 0; redis.call('HSET', KEYS[3], kid, 0) end
            redis.call('ZADD', KEYS[1], cnt / quota, kid)
            return cnt
            """, Long.class);

    private final StringRedisTemplate redis;
    private final AppProperties       appProperties;

    // ─────────────────────────────────────────────────────────────────────────
    // Initialisation
    // ─────────────────────────────────────────────────────────────────────────

    @PostConstruct
    public void initPool() {
        List<AppProperties.Gemini.KeyEntry> pool = appProperties.getGemini().getKeyPool();

        if (pool == null || pool.isEmpty()) {
            log.info("GeminiKeyPoolService: no key-pool configured — single-key mode");
            return;
        }

        for (AppProperties.Gemini.KeyEntry entry : pool) {
            // Always refresh quota and secret from config on startup
            redis.opsForHash().put(QUOTA_HASH,  entry.getId(), String.valueOf(entry.getQuota()));
            redis.opsForHash().put(SECRET_HASH, entry.getId(), entry.getApiKey());

            // Only add to sorted set if not already present — preserves active counts across restarts
            if (redis.opsForZSet().score(POOL_ZSET, entry.getId()) == null) {
                redis.opsForZSet().add(POOL_ZSET, entry.getId(), 0.0);
                log.debug("GeminiKeyPoolService: registered keyId={} quota={}", entry.getId(), entry.getQuota());
            }
        }

        log.info("GeminiKeyPoolService: pool ready — {} key(s)", pool.size());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Atomically acquires the least-loaded key.
     * Returns the keyId (logical name), NOT the raw API key.
     * Returns null in single-key mode (pool not configured).
     */
    public String acquireKeyId() {
        if (!isPoolMode()) return null;

        List<String> keys = List.of(POOL_ZSET, QUOTA_HASH, ACTIVE_HASH);
        String keyId = redis.execute(ACQUIRE_SCRIPT, keys);

        if (keyId == null || keyId.isBlank()) {
            log.error("GeminiKeyPoolService: sorted set is empty — all keys missing from Redis");
            throw new GeminiQuotaExhaustedException();
        }

        log.debug("GeminiKeyPoolService: acquired keyId={}", keyId);
        return keyId;
    }

    /**
     * Releases a key back to the pool, decrementing its active count.
     * MUST be called in a finally block after every acquire.
     * No-op if keyId is null (single-key mode).
     */
    public void releaseKeyId(String keyId) {
        if (keyId == null) return;

        List<String> keys = List.of(POOL_ZSET, QUOTA_HASH, ACTIVE_HASH);
        redis.execute(RELEASE_SCRIPT, keys, keyId);
        log.debug("GeminiKeyPoolService: released keyId={}", keyId);
    }

    /**
     * Resolves keyId → raw GCP API key string.
     * Falls back to app.gemini.api-key when keyId is null (single-key mode).
     */
    public String resolveApiKey(String keyId) {
        if (keyId == null) {
            return appProperties.getGemini().getApiKey();
        }
        Object raw = redis.opsForHash().get(SECRET_HASH, keyId);
        if (raw == null) {
            log.warn("GeminiKeyPoolService: keyId={} secret not found — using default key", keyId);
            return appProperties.getGemini().getApiKey();
        }
        return raw.toString();
    }

    public boolean isPoolMode() {
        List<AppProperties.Gemini.KeyEntry> pool = appProperties.getGemini().getKeyPool();
        return pool != null && !pool.isEmpty();
    }
}