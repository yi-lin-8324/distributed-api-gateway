
package com.example.gateway;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.List;
import java.util.Map;


@RestController
public class ProxyController {

    private final RestClient restClient;
    private final ApiKeyService apiKeyService;
    private final RateLimitService rateLimitService;
    private final CircuitBreaker upstreamCircuitBreaker;
    private static final Logger log = LoggerFactory.getLogger(ProxyController.class);

    public ProxyController(
            @Value("${upstream.base-url}") String upstreamBaseUrl,
            ApiKeyService apiKeyService,
            RateLimitService rateLimitService,
            RestClient.Builder restClientBuilder) {

        this.restClient = restClientBuilder.baseUrl(upstreamBaseUrl).build();
        this.apiKeyService = apiKeyService;
        this.rateLimitService = rateLimitService;
        this.upstreamCircuitBreaker = CircuitBreaker.of("upstream", CircuitBreakerConfig.custom()
                .failureRateThreshold(50)
                .slidingWindowSize(2)
                .minimumNumberOfCalls(2)
                .waitDurationInOpenState(Duration.ofSeconds(30))
                .recordResult(value -> value instanceof ResponseEntity<?> response && response.getStatusCode().is5xxServerError())
                .build()
        );

    }

    @GetMapping("/proxy/{*path}")
    public ResponseEntity<byte[]> forward(@PathVariable("path") String path, @RequestHeader(value = "X-API-Key", required = false) String apiKey) {
        // Step 1: Reject missing API keys
        if (apiKey == null || apiKey.isBlank()) {
            log.atInfo()
                    .addKeyValue("event", "authentication_rejected")
                    .addKeyValue("reason", "missing_api_key")
                    .addKeyValue("http_status", 401)
                    .log("Gateway request rejected during authentication");
            return ResponseEntity.status(401).build();
        }

        // Step 2: Hash the provided API key
        String keyHash = ApiKeyHasher.hash(apiKey);
        // Step 3: Authenticate and retrieve identity
        long result;
        try {
            // Step 3: Authenticate API key
            List<Map<String, Object>> matches = apiKeyService.findActiveIdentity(keyHash);
            if (matches.isEmpty()) {
                log.atInfo()
                        .addKeyValue("event", "authentication_rejected")
                        .addKeyValue("reason", "invalid_or_inactive_api_key")
                        .addKeyValue("http_status", 401)
                        .log("Gateway request rejected during authentication");
                return ResponseEntity.status(401).build();
            }
            Map<String, Object> identity = matches.get(0);
            String userId = (String) identity.get("user_id");
            String tenantId = (String) identity.get("tenant_id");

            // Step 4: Enforce user and tenant quotas
            result = rateLimitService.checkAndConsumeWithRetry(userId, tenantId);

            if (result != 1L) {
                log.atInfo()
                        .addKeyValue("event", "rate_limit_rejected")
                        .addKeyValue("user_id", userId)
                        .addKeyValue("tenant_id", tenantId)
                        .addKeyValue("http_status", 429)
                        .log("Gateway request rejected by rate limit");
            }
            if (result == 1L) {
                log.atInfo()
                        .addKeyValue("event", "request_admitted")
                        .addKeyValue("user_id", userId)
                        .addKeyValue("tenant_id", tenantId)
                        .log("Gateway request admitted for upstream forwarding");
            }
        } catch (RedisConnectionFailureException ex) {
            return ResponseEntity.status(503).build();
        }

        if (result != 1L) {
            // Negative result contains the retry delay
            if (result < 0) {
                long retrySeconds = -result;
                return ResponseEntity.status(429).header("Retry-After", Long.toString(retrySeconds)).build();
            }
            // Result 0: no finite automatic retry time
            return ResponseEntity.status(429).build();
        }

        // Step 5: Forward admitted requests

        try {
            return upstreamCircuitBreaker.executeSupplier(() ->
                    restClient.get()
                            .uri(path)
                            .exchange((request, response) -> {

                                ResponseEntity<byte[]> upstreamResponse =
                                        ResponseEntity
                                                .status(response.getStatusCode())
                                                .headers(response.getHeaders())
                                                .body(response.getBody().readAllBytes());

                                log.atInfo()
                                        .addKeyValue("event", "upstream_response")
                                        .addKeyValue("upstream_status", upstreamResponse.getStatusCode().value())
                                        .log("Gateway received upstream response");

                                return upstreamResponse;
                            })
            );

        } catch (CallNotPermittedException ex) {
            // Circuit breaker is OPEN
            return ResponseEntity.status(503).build();
        } catch (ResourceAccessException ex) {
            // Could not contact the upstream
            return ResponseEntity.status(502).build();
        }
    }
}
