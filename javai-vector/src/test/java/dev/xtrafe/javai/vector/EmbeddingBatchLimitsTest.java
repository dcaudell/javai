package dev.xtrafe.javai.vector;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * How a list of texts is split into requests a provider will actually accept (OMI-266).
 *
 * <p>The gap this closes: {@link EmbeddingInputLimits} bounds each text against the model's context window
 * and is deliberately <em>per member, never per batch</em>, so nothing bounded the sum. A hundred
 * individually-legal texts against a 32,768-token model is roughly 9.4 MiB in one HTTP body — a request no
 * provider agreed to accept, and one that only became reachable when the library started batching its own
 * persistence flows.
 */
class EmbeddingBatchLimitsTest {

    @Test
    @DisplayName("splits on the count ceiling")
    void splitsOnCount() {
        List<String> texts = texts(10, "a");

        List<List<String>> batches = EmbeddingBatchLimits.split(texts, 4, 0);

        assertEquals(List.of(4, 4, 2), sizes(batches));
        assertEquals(texts, flatten(batches), "order and content must survive the split");
    }

    @Test
    @DisplayName("splits on the total-size ceiling, which the count ceiling cannot see")
    void splitsOnTotalSize() {
        // Eight texts of 100 chars each: any count ceiling of 8 or more is satisfied, and the batch is still
        // eight times the size budget. This is the failure mode per-input truncation structurally cannot catch.
        List<String> texts = texts(8, "x".repeat(100));

        List<List<String>> batches = EmbeddingBatchLimits.split(texts, 100, 250);

        assertEquals(List.of(2, 2, 2, 2), sizes(batches));
        for (List<String> batch : batches) {
            assertTrue(totalChars(batch) <= 250, "no batch may exceed the size budget: " + totalChars(batch));
        }
    }

    @Test
    @DisplayName("whichever ceiling binds first is the one that applies")
    void appliesWhicheverCeilingBindsFirst() {
        List<String> texts = texts(9, "x".repeat(10));

        assertEquals(List.of(3, 3, 3), sizes(EmbeddingBatchLimits.split(texts, 3, 10_000)),
                "count binds when the texts are small");
        assertEquals(List.of(2, 2, 2, 2, 1), sizes(EmbeddingBatchLimits.split(texts, 100, 25)),
                "size binds when the texts are large");
    }

    /**
     * The edge case that decides whether this is a safety net or a bug: one text longer than the whole batch
     * budget. It is already bounded to the model's own context by {@link EmbeddingInputLimits}, so it is a
     * request the provider has every reason to accept — dropping it would lose a vector the caller asked
     * for, and refusing to place it would loop forever.
     */
    @Test
    @DisplayName("a single over-budget text is sent alone, never dropped and never looped on")
    void oversizedSingleTextIsSentAlone() {
        List<String> texts = List.of("small", "x".repeat(1_000), "also small");

        List<List<String>> batches = EmbeddingBatchLimits.split(texts, 100, 50);

        assertEquals(texts, flatten(batches), "nothing may be dropped");
        assertEquals(List.of("x".repeat(1_000)), batches.get(1),
                "the over-budget text gets a batch to itself rather than stacking onto its neighbours");
    }

    @Test
    @DisplayName("non-positive ceilings mean no limit")
    void nonPositiveMeansNoLimit() {
        List<String> texts = texts(50, "x".repeat(100));

        assertEquals(List.of(50), sizes(EmbeddingBatchLimits.split(texts, 0, 0)));
    }

    @Test
    @DisplayName("an empty input produces no batches, so no empty request is ever sent")
    void emptyInputProducesNoBatches() {
        assertEquals(List.of(), EmbeddingBatchLimits.split(List.of(), 10, 100));
    }

    /**
     * The defaults are a backstop for a provider that declares nothing, so the one property that matters is
     * that they change nothing for such a provider while still making the pathological request impossible.
     */
    @Test
    @DisplayName("the default count ceiling matches the chunk size already in use")
    void defaultCountCeilingMatchesTheExistingChunkSize() {
        assertEquals(100, EmbeddingBatchLimits.DEFAULT_MAX_BATCH_SIZE);
        assertTrue(EmbeddingBatchLimits.characterBudget(EmbeddingBatchLimits.DEFAULT_MAX_BATCH_TOKENS)
                        < 100 * EmbeddingInputLimits.characterBudget(32_768),
                "the default size ceiling must rule out the 100-inputs-at-full-context request that motivated it");
    }

    private static List<String> texts(int count, String value) {
        List<String> texts = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            texts.add(value + i);
        }
        return texts;
    }

    private static List<Integer> sizes(List<List<String>> batches) {
        return batches.stream().map(List::size).toList();
    }

    private static List<String> flatten(List<List<String>> batches) {
        return batches.stream().flatMap(List::stream).toList();
    }

    private static int totalChars(List<String> batch) {
        return batch.stream().mapToInt(String::length).sum();
    }
}
