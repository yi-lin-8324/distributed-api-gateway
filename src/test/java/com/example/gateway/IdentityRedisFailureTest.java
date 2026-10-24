
package com.example.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class IdentityRedisFailureTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ApiKeyService apiKeyService;

    @Test
    void returns503WhenIdentityLookupCannotReachRedis() throws Exception {
        String apiKey = "test-only-identity-failure-key";
        String keyHash = ApiKeyHasher.hash(apiKey);
        // Simulate Redis failing during identity lookup
        when(apiKeyService.findActiveIdentity(keyHash)).thenThrow(new RedisConnectionFailureException("Simulated identity-cache outage"));
        // The gateway must fail closed
        mockMvc.perform(get("/proxy/hello").header("X-API-Key", apiKey)).andExpect(status().isServiceUnavailable());
    }
}
