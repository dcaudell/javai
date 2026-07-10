package dev.xtrafe.javai.vector;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Cross-instance, cross-module rate-limit coordination for any HTTP-calling provider (a {@code Cortex} in
 * {@code javai-completion}, or a {@link JavAIEmbeddingProvider} here) pointed at a given endpoint. Keyed by
 * normalized base URL (scheme + authority, not full path) in a static registry, so two different provider
 * instances -- even different provider types, even living in different reactor modules -- sharing an
 * endpoint share the same backoff state. This is what makes "several providers pointed at the same
 * endpoint" actually coordinate, rather than each independently hammering a rate-limited server.
 */
public final class EndpointRateLimiter {

    private static final Map<String, EndpointRateLimiter> REGISTRY = new ConcurrentHashMap<>();
    private static final Duration MAX_BACKOFF = Duration.ofSeconds(60);

    private final AtomicReference<Instant> blockedUntil = new AtomicReference<>(Instant.EPOCH);
    private final AtomicInteger consecutiveFailures = new AtomicInteger(0);

    private EndpointRateLimiter() {
    }

    /** Returns the shared limiter for the endpoint {@code baseUrlOrEndpoint} resolves to. */
    public static EndpointRateLimiter forEndpoint(String baseUrlOrEndpoint) {
        return REGISTRY.computeIfAbsent(normalize(baseUrlOrEndpoint), key -> new EndpointRateLimiter());
    }

    static String normalize(String baseUrlOrEndpoint) {
        URI uri = URI.create(baseUrlOrEndpoint);
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
        String authority = uri.getAuthority() == null ? uri.getPath() : uri.getAuthority().toLowerCase();
        return scheme + "://" + authority;
    }

    /** Blocks the calling thread if this endpoint is still inside a backoff window from a prior 429. */
    public void awaitPermission() {
        Duration wait = Duration.between(Instant.now(), blockedUntil.get());
        if (wait.isNegative() || wait.isZero()) {
            return;
        }
        try {
            Thread.sleep(wait.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting on rate-limited endpoint", e);
        }
    }

    /**
     * Records a 429, extending the backoff window, and returns the delay applied. Prefers the server's own
     * {@code Retry-After} hint; falls back to exponential backoff (base 2 seconds, capped at
     * {@link #MAX_BACKOFF}) keyed off a per-endpoint consecutive-failure counter that
     * {@link #recordSuccess()} resets. The returned {@link Duration} is redundant with
     * {@link #awaitPermission()} for a synchronous caller ({@code RetrySupport} ignores it, relying on
     * {@code awaitPermission()} on the next attempt instead) but is what a reactive caller (a {@code Flux}
     * that can't block a thread to wait) needs directly, e.g. to feed {@code Mono.delay(...)}.
     */
    public Duration recordTooManyRequests(Duration retryAfterHint) {
        int attempt = consecutiveFailures.incrementAndGet();
        Duration backoff = retryAfterHint != null ? retryAfterHint : exponentialBackoff(attempt);
        blockedUntil.set(Instant.now().plus(backoff));
        return backoff;
    }

    /** Resets the consecutive-failure count -- called after any successful call to this endpoint. */
    public void recordSuccess() {
        consecutiveFailures.set(0);
    }

    private static Duration exponentialBackoff(int attempt) {
        long seconds = Math.min(MAX_BACKOFF.toSeconds(), 1L << Math.min(attempt, 32));
        return Duration.ofSeconds(seconds);
    }
}
