
package com.example.gateway;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CircuitBreakerTest {

    @Test
    void opensAfterTwoFailures() {

        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .failureRateThreshold(50)
                .slidingWindowSize(2)
                .minimumNumberOfCalls(2)
                .waitDurationInOpenState(Duration.ofMinutes(1))
                .build();

        CircuitBreaker breaker = CircuitBreaker.of("test-upstream", config);
        // Initially, requests are permitted
        assertEquals(CircuitBreaker.State.CLOSED, breaker.getState());
        // First upstream failure
        assertThrows(IllegalStateException.class, () -> breaker.executeSupplier(() -> {
            throw new IllegalStateException("Upstream failed");
        }));
        // Not enough recorded calls to open yet
        assertEquals(CircuitBreaker.State.CLOSED, breaker.getState());
        // Second upstream failure
        assertThrows(IllegalStateException.class, () -> breaker.executeSupplier(() -> {
            throw new IllegalStateException("Upstream failed");
        }));
        // Failure threshold reached
        assertEquals(CircuitBreaker.State.OPEN, breaker.getState());
        // Third request is blocked before reaching upstream
        assertThrows(CallNotPermittedException.class, () -> breaker.executeSupplier(() -> "Upstream response"));
    }
}
