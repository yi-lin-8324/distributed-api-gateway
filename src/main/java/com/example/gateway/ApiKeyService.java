
package com.example.gateway;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Map;

@Service
public class ApiKeyService {

    private final JdbcTemplate jdbcTemplate;
    private final StringRedisTemplate redisTemplate;

    private final Cache<String, List<Map<String, Object>>> identityCache = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofSeconds(30))
            .maximumSize(1000)
            .build();

    public ApiKeyService(JdbcTemplate jdbcTemplate, StringRedisTemplate redisTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.redisTemplate = redisTemplate;
    }

    public List<Map<String, Object>> findActiveIdentity(String keyHash) {
        // Step 1: Check Caffeine L1
        List<Map<String, Object>> cached = identityCache.getIfPresent(keyHash);
        if (cached != null) {
            return cached;
        }
        String redisKey = "cache:identity:" + keyHash;
        // Step 2: Check Redis L2
        Map<Object, Object> redisIdentity = redisTemplate.opsForHash().entries(redisKey);
        if (redisIdentity.containsKey("user_id") && redisIdentity.containsKey("tenant_id")) {
            List<Map<String, Object>> identity = List.of(Map.of("user_id", redisIdentity.get("user_id").toString(), "tenant_id", redisIdentity.get("tenant_id").toString()));
            // Populate L1 from L2
            identityCache.put(keyHash, identity);
            return identity;
        }
        // Step 3: Both caches missed; query PostgreSQL
        List<Map<String, Object>> matches = jdbcTemplate.queryForList("SELECT user_id, tenant_id FROM api_keys WHERE key_hash = ? AND active = true", keyHash);
        // Step 4: Cache only active identities
        if (!matches.isEmpty()) {
            Map<String, Object> identity = matches.get(0);
            redisTemplate.opsForHash().putAll(redisKey, Map.of("user_id", identity.get("user_id").toString(), "tenant_id", identity.get("tenant_id").toString()));
            redisTemplate.expire(redisKey, Duration.ofSeconds(60));
            identityCache.put(keyHash, matches);
        }
        return matches;
    }
}
