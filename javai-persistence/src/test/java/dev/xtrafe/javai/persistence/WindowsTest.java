package dev.xtrafe.javai.persistence;

import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The offset-based {@link Pageable} (OMI-460), on its own -- {@code DeclaredQueryTest} proves it against a
 * real query.
 */
class WindowsTest {

    @Test
    void offsetIsIndependentOfLimitWhereAPageRequestsIsNot() {
        Pageable window = Windows.of(60, 21);

        assertEquals(60, window.getOffset());
        assertEquals(21, window.getPageSize());
        // The ticket's own example: the standard "ask for one row more than the page holds" idiom wants
        // offset 60 with a limit of 21, and no page number produces it.
        assertNotEquals(window.getOffset(), PageRequest.of(60 / 21, 21).getOffset());
        assertEquals(42, PageRequest.of(60 / 21, 21).getOffset(), "42, not 60 -- quietly the wrong rows");
    }

    @Test
    void isPagedAndCarriesItsSort() {
        Sort sort = Sort.by("title");
        Pageable window = Windows.of(5, 10, sort);

        assertTrue(window.isPaged());
        assertEquals(sort, window.getSort());
        assertEquals(Sort.unsorted(), Windows.of(5, 10).getSort());
    }

    @Test
    void pageNumberDescribesWhereTheWindowSitsAndDoesNotRoundTrip() {
        Pageable window = Windows.of(60, 21);

        assertEquals(2, window.getPageNumber(), "floor(60 / 21)");
        assertEquals(42, window.withPage(window.getPageNumber()).getOffset(),
                "withPage is page-aligned by definition, so it cannot return this window -- documented, "
                        + "because Pageable has no other meaning for 'page n'");
    }

    @Test
    void nextAndPreviousMoveByTheLimitFromWhereThisWindowActuallyStarts() {
        Pageable window = Windows.of(60, 21);

        assertEquals(81, window.next().getOffset());
        assertEquals(39, window.previousOrFirst().getOffset());
        assertEquals(0, window.first().getOffset());
        assertTrue(window.hasPrevious());
        assertEquals(21, window.next().getPageSize(), "the size travels with the window");
    }

    @Test
    void theFirstWindowHasNoPrevious() {
        Pageable first = Windows.of(0, 10);

        assertFalse(first.hasPrevious());
        assertEquals(0, first.previousOrFirst().getOffset());
    }

    @Test
    void previousOrFirstClampsRatherThanGoingNegative() {
        Pageable window = Windows.of(5, 10);

        assertEquals(0, window.previousOrFirst().getOffset());
    }

    @Test
    void refusesAWindowThatCouldNotDescribeRows() {
        assertTrue(assertThrows(IllegalArgumentException.class, () -> Windows.of(-1, 10))
                .getMessage().contains("offset"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> Windows.of(0, 0))
                .getMessage().contains("limit"));
    }

    @Test
    void equalWindowsAreEqual() {
        assertEquals(Windows.of(60, 21, Sort.by("title")), Windows.of(60, 21, Sort.by("title")));
        assertEquals(Windows.of(60, 21).hashCode(), Windows.of(60, 21).hashCode());
        assertNotEquals(Windows.of(60, 21), Windows.of(61, 21));
    }
}
