package dev.xtrafe.javai.vector;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RetrySupportTest {

    @Test
    void succeedsOnFirstTryWithNoRetry() {
        String result = RetrySupport.withRetry("https://first-try.test", () -> "ok");
        assertEquals("ok", result);
    }

    @Test
    void retriesAfterATooManyRequestsFailureThenSucceeds() {
        AtomicInteger attempts = new AtomicInteger();
        String result = RetrySupport.withRetry("https://retry-once.test", () -> {
            if (attempts.getAndIncrement() == 0) {
                throw new TooManyRequestsException("rate limited", Duration.ofMillis(10));
            }
            return "ok after retry";
        });
        assertEquals("ok after retry", result);
        assertEquals(2, attempts.get());
    }

    @Test
    void rethrowsTheLastFailureAfterExhaustingAllAttempts() {
        AtomicInteger attempts = new AtomicInteger();
        TooManyRequestsException thrown = assertThrows(TooManyRequestsException.class, () ->
                RetrySupport.withRetry("https://always-fails.test", () -> {
                    attempts.incrementAndGet();
                    throw new TooManyRequestsException("still limited", Duration.ofMillis(1));
                }));
        assertEquals("still limited", thrown.getMessage());
        assertEquals(5, attempts.get(), "must attempt exactly MAX_ATTEMPTS times before giving up");
    }

    @Test
    void nonRateLimitExceptionsPropagateImmediatelyWithoutRetrying() {
        AtomicInteger attempts = new AtomicInteger();
        assertThrows(IllegalStateException.class, () ->
                RetrySupport.withRetry("https://non-rate-limit-failure.test", () -> {
                    attempts.incrementAndGet();
                    throw new IllegalStateException("some other failure");
                }));
        assertEquals(1, attempts.get(), "a non-429 failure must not be retried at all");
    }
}
