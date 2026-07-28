package dev.xtrafe.javai.vector;

/**
 * Converts a model's token limit into a character budget, and applies it -- the one place every provider
 * enforces {@code JavAIEmbeddingProvider.maxInputTokens()}, so behaviour is uniform and a new provider
 * inherits it rather than reinventing it (OMI-216).
 *
 * <h2>This is an estimate, and deliberately a pessimistic one</h2>
 *
 * JavAI has no tokenizer. Limits are expressed in tokens; JavAI holds characters. Converting between them
 * exactly would mean a real per-model tokenizer dependency, correct only for the models it knew and wrong
 * for everything else -- so this uses a fixed, conservative characters-per-token ratio instead.
 *
 * <p>{@link #CHARS_PER_TOKEN} is set <em>below</em> the usual English average (~4) on purpose. Erring small
 * gives up a little of a very long input; erring large sails past the real limit and gets back a vector that
 * silently stands for only part of its text, which is indistinguishable from a correct one. The whole point
 * of this class is to avoid producing that, so it takes the smaller wrong answer every time -- the same
 * reasoning {@code javai-completion}'s {@code ContextWindows} applies to its own fallback.
 *
 * <p>Consequence worth stating plainly: for a text near the boundary, this will sometimes shorten something
 * the model would have accepted whole. That is the accepted cost of not shipping a tokenizer, and it is why
 * an exact limit should come from {@code maxInputTokens()} being overridden or discovered rather than from
 * tuning this ratio.
 */
public final class EmbeddingInputLimits {

    /**
     * Characters assumed per token. Below the ~4 typical of English prose, because underestimating the
     * budget is recoverable and overestimating it is not -- see this class's own javadoc.
     */
    private static final int CHARS_PER_TOKEN = 3;

    private EmbeddingInputLimits() {
    }

    /** The character budget corresponding to {@code maxInputTokens}, or {@link Integer#MAX_VALUE} when the
     *  provider declares no limit (a non-positive value, meaning "don't enforce"). */
    public static int characterBudget(int maxInputTokens) {
        if (maxInputTokens <= 0) {
            return Integer.MAX_VALUE;
        }
        long budget = (long) maxInputTokens * CHARS_PER_TOKEN;
        return budget > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) budget;
    }

    /** Whether {@code text} is estimated to exceed the budget for {@code maxInputTokens}. */
    public static boolean exceedsBudget(String text, int maxInputTokens) {
        return text != null && text.length() > characterBudget(maxInputTokens);
    }

    /**
     * {@code text} shortened to the character budget, cutting at a whitespace boundary where one is
     * reasonably close to the end rather than mid-word.
     *
     * <p>Not because a model cannot handle a severed word -- it can -- but because the truncated text is
     * stored and surfaced (OMI-191 persists concatenated text; failure messages quote it), and a clean cut
     * is easier for a human to recognise as deliberate rather than as corruption.
     */
    public static String truncateToBudget(String text, int maxInputTokens) {
        int budget = characterBudget(maxInputTokens);
        if (text == null || text.length() <= budget) {
            return text;
        }
        String cut = text.substring(0, budget);
        int lastBreak = cut.lastIndexOf(' ');
        // Only honour a word boundary in the last tenth; otherwise a text with no spaces near the end
        // (JSON, a URL, a long identifier) would lose far more than the budget required.
        return lastBreak > budget - budget / 10 ? cut.substring(0, lastBreak) : cut;
    }

    /**
     * Every text shortened to the same budget -- the batched counterpart of {@link #truncateToBudget}.
     *
     * <p>Bounded <b>per member, never per batch</b>: one over-long entry must not shorten its neighbours, and
     * must not fail the whole request. The limit is resolved once by the caller and passed in, so a batch
     * costs one limit lookup rather than one per text.
     */
    public static java.util.List<String> truncateEach(java.util.List<String> texts, int maxInputTokens) {
        java.util.List<String> truncated = new java.util.ArrayList<>(texts.size());
        for (String text : texts) {
            truncated.add(truncateToBudget(text, maxInputTokens));
        }
        return truncated;
    }
}
