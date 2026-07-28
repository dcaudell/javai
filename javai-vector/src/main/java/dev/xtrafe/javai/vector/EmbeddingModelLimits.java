package dev.xtrafe.javai.vector;

import java.util.Map;

/**
 * Best-effort maximum input sizes (in tokens) for embedding models, keyed by the exact model identifier a
 * provider was configured with (e.g. {@code "text-embedding-3-small"}, {@code "qwen3-embedding:0.6b"}).
 *
 * <p><b>Not authoritative</b> -- embedding models change, new ones appear, and this table is not re-verified
 * against live provider documentation on every release. It is the *last* of three tiers: a provider that can
 * ask its own endpoint should do that first (see {@code JavAIEmbeddingProvider.maxInputTokens}), and an
 * explicit per-provider override always wins over both. Reach for the override whenever correctness matters
 * more than this table's convenience.
 *
 * <p>Deliberately the embedding-side sibling of {@code javai-completion}'s {@code ContextWindows}, down to
 * the reasoning behind {@link #DEFAULT_FALLBACK} -- the same problem, solved the same way, so a reader who
 * knows one already knows the other.
 *
 * <p>Why this exists at all (OMI-216): providers do not agree on what an over-long input means. Measured
 * against a real Ollama instance, a 324,000-character input -- roughly 80,000 tokens against
 * {@code qwen3-embedding:0.6b}'s 32,768-token context -- returned HTTP 200 and a normal 1024-dimension
 * vector. It was silently truncated, and nothing in the response said so. A vector standing for the first
 * quarter of its text, indistinguishable from one standing for all of it, is the kind of wrong answer that
 * looks right; knowing the limit is what makes it addressable.
 */
final class EmbeddingModelLimits {

    /**
     * Fallback for any model not in {@link #KNOWN_MODELS} -- deliberately conservative rather than a
     * generous guess, for the same reason {@code ContextWindows.DEFAULT_FALLBACK} is: erring small costs a
     * little of a long input, while erring large means sailing past the real limit and getting back a
     * silently-truncated vector nobody can identify. The smaller wrong answer is the safer one.
     *
     * <p>512 rather than the completion side's 8,192 because embedding models are typically far tighter
     * than chat models -- many sentence-transformer models cap at 512 tokens, and several popular ones at
     * 256. A model with a larger window loses a little text until it is added below or overridden; a model
     * assumed larger than it is produces vectors that are quietly wrong.
     */
    private static final int DEFAULT_FALLBACK = 512;

    private static final Map<String, Integer> KNOWN_MODELS = Map.ofEntries(
            // OpenAI -- documented, not queryable through any API.
            Map.entry("text-embedding-3-small", 8_191),
            Map.entry("text-embedding-3-large", 8_191),
            Map.entry("text-embedding-ada-002", 8_191),
            // Ollama tags. qwen3-embedding:0.6b confirmed at 32,768 by /api/show against a live instance.
            Map.entry("qwen3-embedding:0.6b", 32_768),
            Map.entry("qwen3-embedding:4b", 32_768),
            Map.entry("qwen3-embedding:8b", 32_768),
            Map.entry("nomic-embed-text", 8_192),
            Map.entry("mxbai-embed-large", 512),
            Map.entry("all-minilm", 256),
            // Hugging Face ids, as used by TEI and vLLM.
            Map.entry("Qwen/Qwen3-Embedding-0.6B", 32_768),
            Map.entry("BAAI/bge-large-en-v1.5", 512),
            Map.entry("sentence-transformers/all-MiniLM-L6-v2", 256));

    private EmbeddingModelLimits() {
    }

    static int lookup(String modelId) {
        return modelId == null ? DEFAULT_FALLBACK : KNOWN_MODELS.getOrDefault(modelId, DEFAULT_FALLBACK);
    }

    /** True when {@code modelId} is actually in the table, as against having taken the fallback -- lets a
     *  caller tell "we know this model's limit" from "we guessed conservatively". */
    static boolean isKnown(String modelId) {
        return modelId != null && KNOWN_MODELS.containsKey(modelId);
    }
}
