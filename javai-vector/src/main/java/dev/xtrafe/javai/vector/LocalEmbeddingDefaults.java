package dev.xtrafe.javai.vector;

import java.net.URI;
import java.util.Locale;

/**
 * The one place this repo decides, per platform, which real {@link JavAIEmbeddingProvider} to default to
 * for local development and testing -- see doc/spec/vector-core.md's "Provider selection across
 * platforms" section for the full story. Both {@code e2e-client-test} (which container to start) and any
 * other local-dev caller (which provider class to construct) read this one class, so the two decisions
 * can't drift apart.
 *
 * <p>Short version: the whitepaper's Phase 0 default -- {@link EmbeddingProviderTextEmbeddingsInference} against
 * TEI's {@code cpu-1.9} image -- has a confirmed, unresolved upstream bug running
 * {@code Qwen/Qwen3-Embedding-0.6B} on CPU (TEI's Candle backend, "Intel MKL ERROR: Parameter 8 was
 * incorrect on entry to SGEMM"). It's reported on native x86_64/AMD hardware, not just under emulation, but
 * it reliably reproduces on macOS specifically (Apple Silicon needs x86_64 emulation for TEI's image at
 * all, which multiplies the same underlying failure). On macOS, this class defaults to
 * {@link EmbeddingProviderOllama} instead -- a different stack (GGUF via llama.cpp) unaffected by the bug,
 * on an image that's natively arm64. Linux and Windows default to TEI, matching the whitepaper -- both
 * typically run on genuine x86_64 hardware, where TEI's own reports of this bug don't apply (not verified
 * by this repo; see the class javadoc caveat below).
 */
public final class LocalEmbeddingDefaults {

    /** System property to force a choice instead of detecting the host OS: {@code ollama} or {@code tei}. */
    public static final String OVERRIDE_PROPERTY = "javai.embedding.provider";

    private static final String OLLAMA_MODEL = "qwen3-embedding:0.6b";
    private static final String TEI_HUGGING_FACE_MODEL_ID = "Qwen/Qwen3-Embedding-0.6B";
    private static final String FRIENDLY_MODEL_LABEL = "qwen3-embedding-0.6b";

    public enum Platform {
        MACOS,
        LINUX,
        WINDOWS,
        OTHER
    }

    private LocalEmbeddingDefaults() {
    }

    /** The actual host OS, ignoring {@link #OVERRIDE_PROPERTY} -- ordinary {@code os.name} sniffing. */
    public static Platform detectPlatform() {
        String osName = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (osName.contains("mac") || osName.contains("darwin")) {
            return Platform.MACOS;
        }
        if (osName.contains("win")) {
            return Platform.WINDOWS;
        }
        if (osName.contains("nux") || osName.contains("nix") || osName.contains("aix")) {
            return Platform.LINUX;
        }
        return Platform.OTHER;
    }

    /**
     * {@code true} to default to Ollama, {@code false} to default to TEI. Honors
     * {@value #OVERRIDE_PROPERTY} first (set it to {@code ollama} or {@code tei} to force a choice --
     * e.g. an Intel Mac that wants to try TEI, or a Linux box that wants Ollama for consistency with a
     * Mac dev fleet); falls back to {@link #detectPlatform()} otherwise. {@link Platform#OTHER} falls back
     * to TEI, matching the whitepaper's Phase 0 default.
     */
    public static boolean preferOllama() {
        String override = System.getProperty(OVERRIDE_PROPERTY);
        if (override != null && !override.isBlank()) {
            return "ollama".equalsIgnoreCase(override.strip());
        }
        return detectPlatform() == Platform.MACOS;
    }

    /** The sidecar image to run: Ollama's (natively multi-arch) or TEI's {@code cpu-1.9}. */
    public static String dockerImage() {
        return preferOllama() ? "ollama/ollama:latest" : "ghcr.io/huggingface/text-embeddings-inference:cpu-1.9";
    }

    /** The port the chosen image listens on inside its container. */
    public static int containerPort() {
        return preferOllama() ? 11434 : 80;
    }

    /** Path to poll to know the chosen image is ready to serve requests. */
    public static String healthCheckPath() {
        return preferOllama() ? "/" : "/health";
    }

    /**
     * The identifier used to fetch/select the model on whichever backend was chosen -- Ollama's
     * tag-qualified library name (for {@code ollama pull} or the API's {@code model} field) or TEI's
     * Hugging Face repo id (for its {@code --model-id} startup flag). Both name the same model
     * (Qwen3-Embedding-0.6B); the two backends just spell it differently.
     */
    public static String modelIdentifierForContainerStartup() {
        return preferOllama() ? OLLAMA_MODEL : TEI_HUGGING_FACE_MODEL_ID;
    }

    /** Builds the provider matching {@link #preferOllama()}, against the given (already-running) endpoint. */
    public static JavAIEmbeddingProvider create(URI endpoint) {
        return preferOllama()
                ? new EmbeddingProviderOllama(endpoint, OLLAMA_MODEL)
                : new EmbeddingProviderTextEmbeddingsInference(endpoint, FRIENDLY_MODEL_LABEL);
    }

    /** The {@code EmbeddingVector.modelId()} label whatever {@link #create} returns will produce. */
    public static String modelLabel() {
        return preferOllama() ? OLLAMA_MODEL : FRIENDLY_MODEL_LABEL;
    }
}
