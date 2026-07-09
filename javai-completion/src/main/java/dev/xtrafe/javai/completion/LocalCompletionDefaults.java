package dev.xtrafe.javai.completion;

import java.net.URI;

/**
 * The one place this repo decides which local chat model {@link OllamaCortex} defaults to for
 * development and testing -- mirrors {@code javai-runtime}'s {@code LocalEmbeddingDefaults} in spirit
 * (both {@code e2e-client-test}'s Docker image and any local-dev caller read this one class, so the model
 * baked into the container and the model this code asks for can't drift apart), but simpler: unlike the
 * embeddings case, there's no per-platform branching here. Ollama's Docker image is natively multi-arch and
 * this project has hit no platform-specific bug running chat models through it (unlike TEI's confirmed
 * Candle-backend bug forcing a Mac-specific embeddings fallback), so one default suffices everywhere.
 *
 * <p><b>Model choice: {@code qwen3:8b}</b> (5.2 GB, 40K context) -- chosen deliberately more powerful than
 * the smallest workable option: current (2026) local-LLM benchmarking consistently ranks it the strongest
 * dense model under 8B parameters (highest HumanEval score in its class, strongest multilingual support),
 * and it runs acceptably under Ollama's Metal acceleration on Apple Silicon. {@code qwen3:4b} (2.5 GB) is
 * the documented one-tier-down fallback if {@code 8b} proves impractically slow in a given environment --
 * swapping is a one-line change here, not a design change.
 */
public final class LocalCompletionDefaults {

    /** System property to override the model without touching code. */
    public static final String MODEL_OVERRIDE_PROPERTY = "javai.completion.local.model";

    private static final String DEFAULT_MODEL = "qwen3:8b";
    private static final String FALLBACK_MODEL = "qwen3:4b";

    private LocalCompletionDefaults() {
    }

    /** The Ollama model tag this repo's local dev/test setup uses by default, honoring
     *  {@value #MODEL_OVERRIDE_PROPERTY} first. */
    public static String model() {
        String override = System.getProperty(MODEL_OVERRIDE_PROPERTY);
        return override != null && !override.isBlank() ? override.strip() : DEFAULT_MODEL;
    }

    /** The documented one-tier-down fallback, for reference by anything that wants to try a lighter model
     *  without guessing a tag -- not selected automatically. */
    public static String fallbackModel() {
        return FALLBACK_MODEL;
    }

    /** Builds an {@link OllamaCortex} against the given (already-running) Ollama endpoint, using
     *  {@link #model()}. */
    public static OllamaCortex create(URI endpoint) {
        return OllamaCortex.builder().endpoint(endpoint).model(model()).build();
    }
}
