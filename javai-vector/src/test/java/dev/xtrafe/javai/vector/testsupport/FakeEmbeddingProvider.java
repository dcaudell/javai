package dev.xtrafe.javai.vector.testsupport;

import dev.xtrafe.javai.vector.EmbeddingVector;
import dev.xtrafe.javai.vector.JavAIEmbeddingProvider;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Deterministic, network-free stand-in for a real {@link JavAIEmbeddingProvider}, so tests stay fast and
 * hermetic. Same text always yields the same vector; different text (almost always) yields a different
 * one -- exactly what the lifecycle/propagation tests need to observe, without a Docker dependency. Real
 * embeddings against a live provider are exercised separately, by the standalone {@code e2e-client-test}
 * project.
 *
 * <p>This is the single copy. Until OMI-187 there were six byte-identical private copies of this class,
 * one per module test tree, each carrying a javadoc note explaining that sharing test code across module
 * boundaries "isn't worth a test-jar dependency for a class this small." That reasoning held only while
 * the class stayed small and inert. It stopped holding the moment we needed a *shared instrumented*
 * provider ({@link RecordingEmbeddingProvider}) usable from every module and from {@code e2e-client-test}
 * -- six drifting copies of a measurement instrument is how you get six different answers. The test-jar
 * exists now; put shared test fixtures here rather than copying them again.
 *
 * <p>The hash-derived values are deliberately not semantically meaningful: two texts that a real model
 * would consider similar get unrelated vectors here. Tests that need semantic continuity use a purpose-built
 * provider instead (see {@code javai-tagging}'s {@code NearDuplicateEmbeddingProvider}).
 */
public class FakeEmbeddingProvider implements JavAIEmbeddingProvider {

    public static final String MODEL_ID = "fake-test-model";
    public static final int DIMS = 8;

    private final AtomicInteger callCount = new AtomicInteger();

    @Override
    public EmbeddingVector embed(String text) {
        callCount.incrementAndGet();
        int hash = text.hashCode();
        float[] values = new float[DIMS];
        for (int i = 0; i < DIMS; i++) {
            values[i] = ((hash >>> (i * 4)) & 0xF) / 15f;
        }
        return new EmbeddingVector(values, MODEL_ID, DIMS, Instant.now());
    }

    @Override
    public String modelId() {
        return MODEL_ID;
    }

    /** Total {@link #embed} calls seen by this instance. For per-call detail use {@link RecordingEmbeddingProvider}. */
    public int callCount() {
        return callCount.get();
    }
}
