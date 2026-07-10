package dev.xtrafe.javai.vector;

import java.util.function.Supplier;

/**
 * Shared retry loop for any HTTP-calling provider that detects a 429 by throwing
 * {@link TooManyRequestsException}. Coordinates with {@link EndpointRateLimiter} so repeated 429s from the
 * same endpoint back off exponentially (or per the server's {@code Retry-After} hint) regardless of which
 * provider instance -- or which reactor module -- is calling. Stays exception-type agnostic: on final
 * exhaustion it rethrows the last {@link TooManyRequestsException} as-is, leaving each caller to wrap that
 * into its own public-facing exception type (e.g. {@code CompletionException}, {@code
 * EmbeddingProviderException}).
 */
public final class RetrySupport {

    private static final int MAX_ATTEMPTS = 5;

    private RetrySupport() {
    }

    public static <T> T withRetry(String endpointKey, Supplier<T> action) {
        EndpointRateLimiter limiter = EndpointRateLimiter.forEndpoint(endpointKey);
        TooManyRequestsException lastFailure = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            limiter.awaitPermission();
            try {
                T result = action.get();
                limiter.recordSuccess();
                return result;
            } catch (TooManyRequestsException e) {
                lastFailure = e;
                limiter.recordTooManyRequests(e.retryAfter());
            }
        }
        throw lastFailure;
    }
}
