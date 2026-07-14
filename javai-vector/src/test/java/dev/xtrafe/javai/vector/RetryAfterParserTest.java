package dev.xtrafe.javai.vector;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RetryAfterParserTest {

    @Test
    void parsesASecondsValue() {
        assertEquals(Duration.ofSeconds(30), RetryAfterParser.parse("30"));
    }

    @Test
    void parsesAnHttpDateValueInTheFuture() {
        String futureDate = java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME
                .format(java.time.ZonedDateTime.now(java.time.ZoneOffset.UTC).plusSeconds(60));
        Duration parsed = RetryAfterParser.parse(futureDate);
        assertTrue(parsed.toSeconds() > 0 && parsed.toSeconds() <= 61);
    }

    @Test
    void returnsNullForAbsentOrBlankHeader() {
        assertNull(RetryAfterParser.parse(null));
        assertNull(RetryAfterParser.parse(""));
        assertNull(RetryAfterParser.parse("   "));
    }

    @Test
    void returnsNullForUnparseableValues() {
        assertNull(RetryAfterParser.parse("not-a-valid-value"));
    }

    @Test
    void returnsZeroRatherThanNegativeForAPastHttpDate() {
        String pastDate = java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME
                .format(java.time.ZonedDateTime.now(java.time.ZoneOffset.UTC).minusSeconds(60));
        assertEquals(Duration.ZERO, RetryAfterParser.parse(pastDate));
    }
}
