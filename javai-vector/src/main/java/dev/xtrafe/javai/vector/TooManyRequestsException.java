package dev.xtrafe.javai.vector;

import java.time.Duration;

/**
 * Signals that an HTTP endpoint responded {@code 429 Too Many Requests}. Thrown by a provider's own
 * 429-detection adapter (each provider knows how to read its own HTTP client's response, this exception is
 * the common shape they all funnel into) and caught only by {@link RetrySupport}, which coordinates the
 * actual backoff via {@link EndpointRateLimiter}. Never expected to reach a caller of {@code Cortex}/
 * {@code JavAIEmbeddingProvider} directly -- each provider wraps final retry exhaustion in its own
 * public-facing exception type.
 */
public final class TooManyRequestsException extends RuntimeException {

    private final Duration retryAfter;

    public TooManyRequestsException(String message, Duration retryAfter) {
        super(message);
        this.retryAfter = retryAfter;
    }

    /** The server's own {@code Retry-After} hint, or {@code null} if the response didn't include one. */
    public Duration retryAfter() {
        return retryAfter;
    }
}
