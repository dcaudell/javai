package dev.xtrafe.javai.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PromptContextTargetPercentageTest {

    @Test
    void nestedContextsSplitTheRemainingBudgetProportionally() {
        PromptContext nestedA = PromptContext.builder()
                .targetPercentage(0.75)
                .entry(new PlainTextEntry("A".repeat(10)))
                .build();
        PromptContext nestedB = PromptContext.builder()
                .targetPercentage(0.25)
                .entry(new PlainTextEntry("B".repeat(5)))
                .build();
        PromptContext outer = PromptContext.builder()
                .maxLength(20)
                .entry(nestedA)
                .entry(nestedB)
                .build();

        // nestedA gets floor(20 * 0.75) = 15 chars of budget -- its 10-char entry fits whole.
        // nestedB then gets floor((20 - 10) * 0.25) = 2 chars of budget -- its 5-char entry doesn't fit,
        // so it renders empty (the existing "no partial entries" rule), leaving only the separator behind.
        assertEquals("A".repeat(10) + "\n\n", outer.toString());
    }

    @Test
    void aNestedContextWithItsOwnExplicitMaxLengthIsNeverResizedByPercentage() {
        PromptContext nested = PromptContext.builder()
                .maxLength(5)
                .entry(new PlainTextEntry("B".repeat(5)))
                .build();
        PromptContext outer = PromptContext.builder()
                .maxLength(1000)
                .entry(nested)
                .build();

        assertEquals("B".repeat(5), outer.toString(),
                "an explicit maxLength on the nested context must win regardless of the parent's budget");
    }

    @Test
    void aQualifyingNestedContextMissingTargetPercentageThrows() {
        PromptContext nested = PromptContext.builder()
                .entry(new PlainTextEntry("no percentage set, no maxLength either"))
                .build();
        PromptContext outer = PromptContext.builder()
                .maxLength(100)
                .entry(nested)
                .build();

        IllegalStateException thrown = assertThrows(IllegalStateException.class, outer::toString);
        assertTrue(thrown.getMessage().contains("targetPercentage"));
    }

    @Test
    void percentageBudgetingIsSkippedEntirelyWhenTheParentIsUnbounded() {
        PromptContext nested = PromptContext.builder()
                .entry(new PlainTextEntry("no percentage, no maxLength, but the parent is unbounded too"))
                .build();
        PromptContext outer = PromptContext.builder()
                .entry(nested)
                .build();

        assertEquals("no percentage, no maxLength, but the parent is unbounded too", outer.toString());
    }

    @Test
    void targetPercentageMustBeGreaterThanZero() {
        assertThrows(IllegalArgumentException.class, () -> PromptContext.builder().targetPercentage(0.0).build());
    }

    @Test
    void targetPercentageMustNotExceedOne() {
        assertThrows(IllegalArgumentException.class, () -> PromptContext.builder().targetPercentage(1.5).build());
    }

    @Test
    void targetPercentageOfExactlyOneIsAccepted() {
        PromptContext context = PromptContext.builder().targetPercentage(1.0).build();
        assertEquals(1.0, context.targetPercentage());
    }
}
