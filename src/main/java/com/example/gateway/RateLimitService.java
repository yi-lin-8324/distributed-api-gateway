
package com.example.gateway;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Map;

@Service
public class RateLimitService {

    private final JdbcTemplate jdbcTemplate;
    private final StringRedisTemplate redisTemplate;
    private final RedisScript<Long> script = RedisScript.of(new ClassPathResource("token_bucket.lua"), Long.class);
    private final Cache<String, Map<String, Object>> policyCache = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofSeconds(30))
            .maximumSize(1000)
            .build();


    private Map<String, Object> getPolicy(String policyKey) {

        return policyCache.get(policyKey, key -> {
            String redisKey = "cache:policy:" + key;
            // Step 1: Check Redis L2
            Map<Object, Object> cached = redisTemplate.opsForHash().entries(redisKey);
            if (cached.containsKey("capacity") && cached.containsKey("refill_rate")) {
                return Map.of("capacity", Integer.parseInt(cached.get("capacity").toString()), "refill_rate", Double.parseDouble(cached.get("refill_rate").toString()));
            }
            // Step 2: Redis miss, load from PostgreSQL
            Map<String, Object> policy = jdbcTemplate.queryForMap("SELECT capacity, refill_rate FROM quota_policies WHERE policy_key = ?", key);
            // Step 3: Store policy in Redis L2
            redisTemplate.opsForHash().putAll(redisKey, Map.of("capacity", policy.get("capacity").toString(), "refill_rate", policy.get("refill_rate").toString()));
            // Step 4: Expire Redis entry after 60 seconds
            redisTemplate.expire(redisKey, Duration.ofSeconds(60));
            return policy;
        });
    }


    public RateLimitService(JdbcTemplate jdbcTemplate, StringRedisTemplate redisTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.redisTemplate = redisTemplate;
    }


    public boolean checkAndConsume(String userId, String tenantId) {

        return checkAndConsumeWithRetry(userId, tenantId) == 1L;
    }

    public long checkAndConsumeWithRetry(String userId, String tenantId) {


        // Read user policy through Caffeine
        Map<String, Object> userPolicy = getPolicy("user:" + tenantId + ":" + userId);
        // Read tenant policy through Caffeine
        Map<String, Object> tenantPolicy = getPolicy("tenant:" + tenantId);
        // Identify shared Redis buckets
        List<String> keys = List.of("bucket:user:" + tenantId + ":" + userId, "bucket:tenant:" + tenantId);
        // Execute atomic Redis Lua script
        Long result = redisTemplate.execute(
                script,
                keys,
                userPolicy.get("capacity").toString(),
                userPolicy.get("refill_rate").toString(),
                tenantPolicy.get("capacity").toString(),
                tenantPolicy.get("refill_rate").toString()
        );

        return result;
    }

}
