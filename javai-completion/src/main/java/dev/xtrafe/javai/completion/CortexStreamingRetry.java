package dev.xtrafe.javai.completion;

import dev.xtrafe.javai.vector.EndpointRateLimiter;
import dev.xtrafe.javai.vector.TooManyRequestsException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

/**
 * Reactor-aware counterpart to {@code javai-vector}'s {@code RetrySupport}, for the streaming/{@code Flux}
 * call path. A 429 there surfaces as a reactive {@code onError} signal (thanks to
 * {@link TooManyRequestsExchangeFilterFunction}) once the {@code Flux} is subscribed, not as a thrown
 * exception when it's built -- {@code RetrySupport}'s synchronous retry loop doesn't fit that, and
 * {@code reactor-core} isn't a dependency {@code javai-vector} takes on, so this stays local to
 * {@code javai-completion} instead, coordinating through the same, shared {@link EndpointRateLimiter}.
 */
final class CortexStreamingRetry {

    private static final int MAX_ATTEMPTS = 5;

    private CortexStreamingRetry() {
    }

    static <T> Flux<T> withRetry(String endpointKey, Flux<T> source) {
        EndpointRateLimiter limiter = EndpointRateLimiter.forEndpoint(endpointKey);
        return source
                .doOnNext(ignored -> limiter.recordSuccess())
                .retryWhen(Retry.from(signals -> signals.flatMap(signal -> {
                    Throwable failure = signal.failure();
                    if (!(failure instanceof TooManyRequestsException e) || signal.totalRetriesInARow() >= MAX_ATTEMPTS - 1) {
                        return Flux.error(failure);
                    }
                    return Mono.delay(limiter.recordTooManyRequests(e.retryAfter()));
                })));
    }
}
