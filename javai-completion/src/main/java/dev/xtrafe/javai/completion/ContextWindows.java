package dev.xtrafe.javai.completion;

import java.util.Map;

/**
 * Best-effort context-window sizes (in tokens), keyed by the exact model identifier a Cortex was
 * configured with (e.g. {@code "gpt-4.1"}, {@code "qwen3:8b"}). <b>Not authoritative</b> -- provider context
 * windows change over time and this table isn't re-verified against live provider documentation on every
 * release. Every Cortex's {@code Builder} accepts an explicit {@code .contextWindowTokens(int)} override
 * that always wins over this table (see each Builder's own javadoc); reach for that whenever correctness
 * matters more than this table's convenience.
 */
final class ContextWindows {

    /**
     * Fallback for any model not in {@link #KNOWN_MODELS} below -- deliberately conservative rather than a
     * generous guess: a request rejected by the real provider for exceeding its true (larger) window is a
     * loud, immediate failure; a context silently over-budgeted and truncated too aggressively because this
     * table guessed too high is a subtler, worse one. Errs toward the smaller, safer wrong answer.
     */
    private static final int DEFAULT_FALLBACK = 8_192;

    private static final Map<String, Integer> KNOWN_MODELS = Map.ofEntries(
            Map.entry("gpt-4.1", 1_047_576),
            Map.entry("gpt-4.1-mini", 1_047_576),
            Map.entry("gpt-4o", 128_000),
            Map.entry("o3", 200_000),
            Map.entry("claude-sonnet-5", 200_000),
            Map.entry("claude-opus-4-8", 200_000),
            Map.entry("llama-3.3-70b-versatile", 128_000),
            Map.entry("qwen3:8b", 40_000),
            Map.entry("qwen3:4b", 40_000),
            Map.entry("meta-llama/Llama-3.2-3B-Instruct", 128_000));

    private ContextWindows() {
    }

    static int lookup(String modelId) {
        return KNOWN_MODELS.getOrDefault(modelId, DEFAULT_FALLBACK);
    }
}
