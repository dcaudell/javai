package dev.xtrafe.javai.completion;

import dev.xtrafe.javai.vector.RetryAfterParser;
import dev.xtrafe.javai.vector.TooManyRequestsException;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.web.client.DefaultResponseErrorHandler;
import org.springframework.web.client.ResponseErrorHandler;

import java.io.IOException;
import java.time.Duration;

/**
 * Detects HTTP 429 on the {@code RestClient}-backed (synchronous) call path shared by every
 * Spring-AI-backed Cortex, converting it into {@link TooManyRequestsException} -- caught only by
 * {@code RetrySupport} -- before Spring AI wraps the response into its own opaque runtime exception. Every
 * other status is delegated to Spring's own {@link DefaultResponseErrorHandler}, so non-429 error behavior
 * (including what exception type callers see) is unchanged from today.
 */
final class TooManyRequestsResponseErrorHandler implements ResponseErrorHandler {

    private final ResponseErrorHandler delegate = new DefaultResponseErrorHandler();

    @Override
    public boolean hasError(ClientHttpResponse response) throws IOException {
        return delegate.hasError(response);
    }

    @Override
    public void handleError(ClientHttpResponse response) throws IOException {
        if (response.getStatusCode().value() == 429) {
            Duration retryAfter = RetryAfterParser.parse(response.getHeaders().getFirst("Retry-After"));
            throw new TooManyRequestsException(
                    "Rate limited: HTTP 429 from " + response.getStatusText(), retryAfter);
        }
        delegate.handleError(response);
    }
}
