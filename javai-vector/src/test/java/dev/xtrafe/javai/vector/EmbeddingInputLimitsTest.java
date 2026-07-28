package dev.xtrafe.javai.vector;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The token-to-character estimate and the limits table (OMI-216).
 *
 * <h2>Why an estimate at all</h2>
 *
 * Model limits are in tokens; JavAI holds characters and has no tokenizer. Converting exactly would need a
 * per-model tokenizer dependency, correct only for models it knew. So the conversion is a fixed conservative
 * ratio, and these tests pin the properties that make a pessimistic estimate safe rather than pinning the
 * ratio itself -- which is a tuning knob, not a contract.
 */
class EmbeddingInputLimitsTest {

    @Test
    void theCharacterBudgetIsPessimisticRatherThanGenerous() {
        // Below the ~4 chars/token typical of English prose: giving up a little of a long input is
        // recoverable, sailing past the real limit and getting a silently-partial vector is not.
        int budget = EmbeddingInputLimits.characterBudget(1_000);

        assertTrue(budget < 4_000,
                "the estimate must sit under the usual English average, not on or above it: " + budget);
        assertTrue(budget > 0);
    }

    @Test
    void aNonPositiveLimitMeansDoNotEnforce() {
        assertEquals(Integer.MAX_VALUE, EmbeddingInputLimits.characterBudget(0));
        assertEquals(Integer.MAX_VALUE, EmbeddingInputLimits.characterBudget(-1));
    }

    /** A large limit must not overflow into a negative budget and disable enforcement by accident. */
    @Test
    void anEnormousLimitSaturatesRatherThanOverflowing() {
        assertEquals(Integer.MAX_VALUE, EmbeddingInputLimits.characterBudget(Integer.MAX_VALUE));
    }

    @Test
    void textWithinBudgetIsLeftExactlyAlone() {
        String text = "short enough";

        assertFalse(EmbeddingInputLimits.exceedsBudget(text, 1_000));
        assertEquals(text, EmbeddingInputLimits.truncateToBudget(text, 1_000));
    }

    @Test
    void textOverBudgetIsShortenedToFit() {
        String text = "word ".repeat(10_000);
        int budget = EmbeddingInputLimits.characterBudget(100);

        assertTrue(EmbeddingInputLimits.exceedsBudget(text, 100));
        assertTrue(EmbeddingInputLimits.truncateToBudget(text, 100).length() <= budget);
    }

    @Test
    void truncationPrefersAWordBoundary() {
        String text = "alpha bravo charlie delta echo foxtrot golf hotel india juliet ".repeat(200);

        String truncated = EmbeddingInputLimits.truncateToBudget(text, 20);

        assertFalse(truncated.endsWith(" "), "a trailing space would be a sloppy cut");
        assertTrue(text.startsWith(truncated), "truncation only removes from the end, never rewrites");
    }

    /**
     * Text with no whitespace near the cut -- JSON, a URL, a long identifier -- must lose only what the
     * budget requires. Honouring a distant word boundary would discard far more than necessary.
     */
    @Test
    void truncationDoesNotHonourADistantWordBoundary() {
        String text = "prefix " + "x".repeat(10_000);
        int budget = EmbeddingInputLimits.characterBudget(50);

        String truncated = EmbeddingInputLimits.truncateToBudget(text, 50);

        assertEquals(budget, truncated.length(),
                "with no break near the end, cut at the budget rather than back at 'prefix '");
    }

    // ---- the limits table ----

    @Test
    void knownModelsResolveToTheirDocumentedLimits() {
        assertEquals(8_191, EmbeddingModelLimits.lookup("text-embedding-3-small"));
        // Confirmed against a live Ollama instance via /api/show: qwen3.context_length = 32768.
        assertEquals(32_768, EmbeddingModelLimits.lookup("qwen3-embedding:0.6b"));
        assertTrue(EmbeddingModelLimits.isKnown("text-embedding-3-small"));
    }

    /**
     * An unknown model gets a small answer, not a generous one. Embedding models are typically far tighter
     * than chat models -- many sentence-transformer models cap at 512 tokens and several at 256 -- so a
     * generous default would quietly produce partial vectors for exactly the models most likely to be
     * missing from the table.
     */
    @Test
    void anUnknownModelFallsBackConservatively() {
        int fallback = EmbeddingModelLimits.lookup("some-model-nobody-has-heard-of");

        assertFalse(EmbeddingModelLimits.isKnown("some-model-nobody-has-heard-of"));
        assertTrue(fallback <= 512, "the fallback must be small enough to be safe for a tight model: " + fallback);
        assertTrue(fallback > 0);
    }

    @Test
    void aNullModelIdIsTreatedAsUnknownRatherThanFailing() {
        assertEquals(EmbeddingModelLimits.lookup("unknown"), EmbeddingModelLimits.lookup(null));
        assertFalse(EmbeddingModelLimits.isKnown(null));
    }
}
