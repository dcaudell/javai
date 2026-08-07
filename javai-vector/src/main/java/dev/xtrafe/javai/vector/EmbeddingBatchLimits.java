package dev.xtrafe.javai.vector;

import java.util.ArrayList;
import java.util.List;

/**
 * How large one batched {@code embedAll} request may get, and the one place a list of texts is split into
 * requests that respect it (OMI-266).
 *
 * <h2>Why a second limit class exists next to {@link EmbeddingInputLimits}</h2>
 *
 * They answer different questions, and the batching work made the second one real. {@link EmbeddingInputLimits}
 * bounds <b>one text</b> against the model's context window, and is explicit that it does so <em>per member,
 * never per batch</em> -- one over-long entry must not shorten its neighbours. That is right, and it leaves
 * the sum completely unbounded: a hundred texts, each individually legal, is a request no provider agreed to
 * accept. Against the local default on macOS ({@code qwen3-embedding:0.6b}, 32,768 tokens, so a 98,304-character
 * budget per input) a hundred of them is roughly 9.4 MiB in one HTTP body.
 *
 * <p>Two separate ceilings, because providers impose two separate ceilings:
 *
 * <ul>
 *   <li><b>Count</b> -- how many inputs one request may carry. Text Embeddings Inference defaults to
 *       <b>32</b> ({@code --max-client-batch-size}), which is <em>below</em> the 100 this library chunked at,
 *       so a default TEI deployment rejects a full batch outright. OpenAI documents 2048.</li>
 *   <li><b>Total size</b> -- how much text in aggregate. TEI defaults to 16,384 tokens
 *       ({@code --max-batch-tokens}); OpenAI documents 300,000 tokens per embeddings request.</li>
 * </ul>
 *
 * <p>Both are needed: a batch of 8 enormous documents breaks a token ceiling while satisfying any count
 * ceiling, and a batch of 2,000 one-word strings does the reverse.
 *
 * <h2>The defaults are a backstop, not a guess at your provider</h2>
 *
 * A provider that knows its own limits overrides {@link JavAIEmbeddingProvider#maxBatchSize()}/
 * {@link JavAIEmbeddingProvider#maxBatchTokens()}, by discovery where its API can answer and by the vendor's
 * published figure where it cannot. These defaults apply only to a provider that does neither -- including
 * any third-party implementation, which is why they exist at all.
 *
 * <p>{@link #DEFAULT_MAX_BATCH_SIZE} deliberately matches the chunk size this library already used, so a
 * provider that declares nothing behaves exactly as it did. {@link #DEFAULT_MAX_BATCH_TOKENS} is the new
 * ceiling, and is set generously rather than tightly: unlike a per-input limit, exceeding it produces a loud
 * rejection rather than a quietly truncated vector, so the failure it guards is the recoverable kind and
 * there is no reason to pay for it in throughput on a provider that would have coped. It exists to make the
 * multi-megabyte request impossible, not to second-guess a working deployment.
 */
public final class EmbeddingBatchLimits {

    /**
     * Inputs per request when a provider declares nothing -- the chunk size {@code precomputeVectors} has
     * always used, so declaring nothing changes nothing.
     */
    public static final int DEFAULT_MAX_BATCH_SIZE = 100;

    /**
     * Total tokens per request when a provider declares nothing. Roughly 300 KB of text at
     * {@code EmbeddingInputLimits}' 3 characters per token -- comfortably above any realistic batch of
     * ordinary field values, and far below the point at which an HTTP body becomes the problem.
     */
    public static final int DEFAULT_MAX_BATCH_TOKENS = 100_000;

    private EmbeddingBatchLimits() {
    }

    /**
     * Splits {@code texts} into batches that respect both ceilings, preserving order.
     *
     * <p><b>A single text that alone exceeds the size budget still gets sent, alone.</b> It is already
     * bounded to the model's own context by {@link EmbeddingInputLimits}, so it is a request the provider has
     * every reason to accept; dropping it would lose a vector the caller asked for, and skipping it forward
     * would loop forever. The batch ceiling is about not stacking texts, and cannot be about refusing one.
     *
     * @param texts        the texts to split, in order
     * @param maxBatchSize maximum inputs per batch; non-positive means "no count limit"
     * @param maxBatchChars maximum total characters per batch; non-positive means "no size limit"
     * @return batches, in order, whose concatenation is {@code texts}
     */
    public static List<List<String>> split(List<String> texts, int maxBatchSize, int maxBatchChars) {
        List<List<String>> batches = new ArrayList<>();
        List<String> current = new ArrayList<>();
        long currentChars = 0;
        for (String text : texts) {
            int length = text == null ? 0 : text.length();
            boolean countFull = maxBatchSize > 0 && current.size() >= maxBatchSize;
            boolean sizeFull = maxBatchChars > 0 && !current.isEmpty() && currentChars + length > maxBatchChars;
            if (countFull || sizeFull) {
                batches.add(current);
                current = new ArrayList<>();
                currentChars = 0;
            }
            current.add(text);
            currentChars += length;
        }
        if (!current.isEmpty()) {
            batches.add(current);
        }
        return batches;
    }

    /** The character budget for one batch, converted from a token ceiling the same way a per-input budget
     *  is -- one conversion ratio for the whole library, not two that could drift. */
    public static int characterBudget(int maxBatchTokens) {
        return EmbeddingInputLimits.characterBudget(maxBatchTokens);
    }
}
