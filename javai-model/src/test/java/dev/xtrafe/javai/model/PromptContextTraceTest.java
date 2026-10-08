package dev.xtrafe.javai.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PromptContextTraceTest {

    @Test
    void theTraceRendersWhatToStringRenders() {
        PromptContext outer = twoRegions();

        assertEquals(outer.toString(), outer.trace().text());
    }

    @Test
    void eachNestedContextReportsItsBudgetTextAndWhetherItFit() {
        PromptContext nestedA = region(0.75, "A".repeat(10));
        PromptContext nestedB = region(0.25, "B".repeat(5));
        PromptContext outer = PromptContext.builder().maxLength(20).entry(nestedA).entry(nestedB).build();

        PromptContextTrace trace = outer.trace();

        assertSame(outer, trace.context());
        assertEquals(20, trace.budget());
        assertEquals(2, trace.nested().size());
        PromptContextTrace a = trace.nested().get(0);
        // The very instance that was nested, not the budget-sized copy.
        assertSame(nestedA, a.context());
        assertEquals(15, a.budget());
        assertEquals("A".repeat(10), a.text());
        assertTrue(a.included());
        PromptContextTrace b = trace.nested().get(1);
        assertSame(nestedB, b.context());
        // floor((20 - 10) * 0.25): too small for its entry, so it renders empty, and the empty text fits.
        assertEquals(2, b.budget());
        assertEquals("", b.text());
        assertTrue(b.included());
    }

    @Test
    void aNestedContextThatOverflowsItsParentIsReportedAsNotIncluded() {
        PromptContext fixed = PromptContext.builder().maxLength(50).entry(new PlainTextEntry("C".repeat(30))).build();
        PromptContext outer = PromptContext.builder().maxLength(20).entry(fixed).build();

        PromptContextTrace trace = outer.trace();

        assertEquals("", trace.text());
        PromptContextTrace c = trace.nested().get(0);
        assertEquals(50, c.budget());
        assertEquals("C".repeat(30), c.text());
        assertFalse(c.included());
    }

    @Test
    void grandchildrenAreTracedToo() {
        PromptContext inner = region(1.0, "inner");
        PromptContext middle = PromptContext.builder().targetPercentage(1.0).entry(inner).build();
        PromptContext outer = PromptContext.builder().maxLength(100).entry(middle).build();

        PromptContextTrace trace = outer.trace();

        PromptContextTrace m = trace.nested().get(0);
        assertSame(middle, m.context());
        assertEquals(100, m.budget());
        PromptContextTrace i = m.nested().get(0);
        assertSame(inner, i.context());
        assertEquals(100, i.budget());
        assertEquals("inner", i.text());
    }

    @Test
    void anUnboundedContextReportsNoBudgets() {
        PromptContext nested = region(0.5, "x");
        PromptContext outer = PromptContext.builder().entry(nested).build();

        PromptContextTrace trace = outer.trace();

        assertNull(trace.budget());
        assertNull(trace.nested().get(0).budget());
    }

    private static PromptContext twoRegions() {
        return PromptContext.builder().maxLength(40).entry(region(0.5, "first")).entry(new PlainTextEntry("plain"))
                .entry(region(0.5, "second")).build();
    }

    private static PromptContext region(double share, String text) {
        return PromptContext.builder().targetPercentage(share).entry(new PlainTextEntry(text)).build();
    }
}
