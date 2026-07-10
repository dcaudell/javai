package dev.xtrafe.javai.vector;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EndpointRateLimiterTest {

    @Test
    void forEndpointReturnsTheSameInstanceForTheSameNormalizedEndpoint() {
        EndpointRateLimiter a = EndpointRateLimiter.forEndpoint("https://api.example.com/v1/chat");
        EndpointRateLimiter b = EndpointRateLimiter.forEndpoint("https://api.example.com/v1/embed");
        assertSame(a, b, "same scheme+authority, different path -- must share one limiter");
    }

    @Test
    void forEndpointReturnsDistinctInstancesForDifferentEndpoints() {
        EndpointRateLimiter a = EndpointRateLimiter.forEndpoint("https://one.example.com");
        EndpointRateLimiter b = EndpointRateLimiter.forEndpoint("https://two.example.com");
        assertTrue(a != b, "different hosts must not share a limiter");
    }

    @Test
    void normalizeIsCaseInsensitiveOnSchemeAndHost() {
        assertEquals(EndpointRateLimiter.normalize("HTTPS://API.Example.com/foo"),
                EndpointRateLimiter.normalize("https://api.example.com/bar"));
    }

    @Test
    void recordTooManyRequestsPrefersTheServersRetryAfterHint() {
        EndpointRateLimiter limiter = EndpointRateLimiter.forEndpoint("https://retry-after-hint.test");
        Duration applied = limiter.recordTooManyRequests(Duration.ofMillis(150));
        assertEquals(Duration.ofMillis(150), applied);
    }

    @Test
    void recordTooManyRequestsFallsBackToExponentialBackoffWithoutAHint() {
        EndpointRateLimiter limiter = EndpointRateLimiter.forEndpoint("https://exponential-backoff.test");
        Duration first = limiter.recordTooManyRequests(null);
        Duration second = limiter.recordTooManyRequests(null);
        assertEquals(Duration.ofSeconds(2), first);
        assertEquals(Duration.ofSeconds(4), second, "consecutive failures must double the backoff");
    }

    @Test
    void recordSuccessResetsTheConsecutiveFailureCount() {
        EndpointRateLimiter limiter = EndpointRateLimiter.forEndpoint("https://reset-after-success.test");
        limiter.recordTooManyRequests(null);
        limiter.recordTooManyRequests(null);
        limiter.recordSuccess();
        Duration afterReset = limiter.recordTooManyRequests(null);
        assertEquals(Duration.ofSeconds(2), afterReset, "the backoff counter must restart from attempt 1");
    }

    @Test
    void awaitPermissionBlocksUntilTheBackoffWindowElapses() {
        EndpointRateLimiter limiter = EndpointRateLimiter.forEndpoint("https://await-permission.test");
        limiter.recordTooManyRequests(Duration.ofMillis(300));

        Instant before = Instant.now();
        limiter.awaitPermission();
        Duration waited = Duration.between(before, Instant.now());

        assertTrue(waited.toMillis() >= 250, "must actually block for roughly the recorded backoff window");
    }

    @Test
    void awaitPermissionReturnsImmediatelyWhenNoBackoffIsActive() {
        EndpointRateLimiter limiter = EndpointRateLimiter.forEndpoint("https://no-backoff.test");
        Instant before = Instant.now();
        limiter.awaitPermission();
        assertTrue(Duration.between(before, Instant.now()).toMillis() < 100);
    }
}
