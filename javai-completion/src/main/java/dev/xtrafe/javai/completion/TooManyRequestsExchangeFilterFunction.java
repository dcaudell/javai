package dev.xtrafe.javai.completion;

import dev.xtrafe.javai.vector.RetryAfterParser;
import dev.xtrafe.javai.vector.TooManyRequestsException;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import reactor.core.publisher.Mono;

import java.time.Duration;

/**
 * Reactive-path counterpart to {@link TooManyRequestsResponseErrorHandler}: detects HTTP 429 on the
 * {@code WebClient}-backed (streaming) call path -- both Spring AI's own internal {@code WebClient} (via
 * {@code .webClientBuilder(...).filter(...)}) and {@link CortexOllama}'s hand-rolled one -- converting it
 * into {@link TooManyRequestsException} the same way {@link TooManyRequestsResponseErrorHandler} does for
 * the synchronous path.
 */
final class TooManyRequestsExchangeFilterFunction {

    private TooManyRequestsExchangeFilterFunction() {
    }

    static ExchangeFilterFunction create() {
        return ExchangeFilterFunction.ofResponseProcessor(response -> {
            if (response.statusCode().value() == 429) {
                Duration retryAfter =
                        RetryAfterParser.parse(response.headers().header("Retry-After").stream().findFirst().orElse(null));
                return Mono.error(new TooManyRequestsException(
                        "Rate limited: HTTP 429 from " + response.statusCode(), retryAfter));
            }
            return Mono.just(response);
        });
    }
}
