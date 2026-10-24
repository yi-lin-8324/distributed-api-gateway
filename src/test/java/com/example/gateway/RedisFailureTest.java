
package com.example.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class RedisFailureTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ApiKeyService apiKeyService;

    @MockitoBean
    private RateLimitService rateLimitService;

    @Test
    void returns503WhenRedisConnectionFails() throws Exception {
        String apiKey = "test-only-redis-failure-key";
        String keyHash = ApiKeyHasher.hash(apiKey);
        String userId = "redis_failure_user";
        String tenantId = "redis_failure_tenant";
        // Simulate a valid API key identity.
        when(apiKeyService.findActiveIdentity(keyHash)).thenReturn(List.of(Map.<String, Object>of("user_id", userId, "tenant_id", tenantId)));
        // Simulate Redis being unavailable.
        when(rateLimitService.checkAndConsumeWithRetry(userId, tenantId)).thenThrow(new RedisConnectionFailureException("Simulated Redis outage"));
        // Verify that the gateway fails closed with HTTP 503.
        mockMvc.perform(get("/proxy/hello").header("X-API-Key", apiKey)).andExpect(status().isServiceUnavailable());
    }
}
