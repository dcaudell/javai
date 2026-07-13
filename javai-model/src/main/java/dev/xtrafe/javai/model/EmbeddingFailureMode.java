package dev.xtrafe.javai.model;

/**
 * Global switch for how a failed {@code embed()} call is surfaced, configured via
 * {@link JavAIRuntime#configureFailureMode(EmbeddingFailureMode)} alongside
 * {@link EmbeddingConsistencyMode}. Applies to both {@link EmbeddingConsistencyMode#IMMEDIATE_CONSISTENCY}'s
 * blocking calls and {@link EmbeddingConsistencyMode#EVENTUAL_CONSISTENCY}'s forced-synchronous first-ever
 * computation, where a caller is genuinely waiting; {@link EmbeddingConsistencyMode#EVENTUAL_CONSISTENCY}'s
 * background (nobody-waiting) recomputes have no caller to throw to, so they follow the paired background
 * behavior noted below instead.
 *
 * <p>A failed attempt never marks a cache clean, regardless of mode -- an object is only ever "clean" when
 * it holds a genuinely accurate vector, so the next read (or, under {@code EVENTUAL_CONSISTENCY}, the next
 * mutation) will retry.
 */
public enum EmbeddingFailureMode {

    /** Propagate the provider's exception directly to the blocked caller. Paired with leaving the last
     *  known-good cached value in place for background failures (never nulling it out). Default. */
    THROW,

    /** Swallow the exception and return {@code null} to the blocked caller instead. Paired with clearing
     *  the cached value to {@code null} for background failures. */
    RETURN_NULL
}
