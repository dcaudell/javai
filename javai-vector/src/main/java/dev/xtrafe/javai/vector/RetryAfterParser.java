package dev.xtrafe.javai.vector;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
 * Parses an HTTP {@code Retry-After} header value (RFC 9110 &sect;10.2.3): either a delay in seconds, or an
 * HTTP-date. Deliberately takes just the raw header string rather than any particular HTTP client's header
 * type, since every provider's 429-detection adapter uses a different client library (JDK
 * {@code HttpClient}, Spring's {@code ResponseErrorHandler}/{@code ExchangeFilterFunction}) but all of them
 * can hand this the same thing: the header's string value.
 */
public final class RetryAfterParser {

    private RetryAfterParser() {
    }

    /** Returns the parsed delay, or {@code null} if {@code headerValue} is absent or unparseable. */
    public static Duration parse(String headerValue) {
        if (headerValue == null || headerValue.isBlank()) {
            return null;
        }
        String trimmed = headerValue.trim();
        try {
            long seconds = Long.parseLong(trimmed);
            return seconds < 0 ? null : Duration.ofSeconds(seconds);
        } catch (NumberFormatException notASecondsValue) {
            try {
                ZonedDateTime target = ZonedDateTime.parse(trimmed, DateTimeFormatter.RFC_1123_DATE_TIME);
                Duration delay = Duration.between(ZonedDateTime.now(target.getZone()), target);
                return delay.isNegative() ? Duration.ZERO : delay;
            } catch (DateTimeParseException notADateEither) {
                return null;
            }
        }
    }
}
