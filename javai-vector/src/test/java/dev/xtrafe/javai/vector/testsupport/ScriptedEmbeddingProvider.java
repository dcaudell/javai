package dev.xtrafe.javai.vector.testsupport;

import dev.xtrafe.javai.vector.EmbeddingVector;
import dev.xtrafe.javai.vector.JavAIEmbeddingProvider;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A provider whose vectors sit at <em>angles you choose</em>, so a test can assert what a similarity search
 * ranked and by how much -- not merely that it returned something.
 *
 * <p>{@link FakeEmbeddingProvider} is deliberately hash-derived and therefore semantically meaningless: two
 * texts a real model would call similar get unrelated vectors. That is exactly right for lifecycle and
 * embedding-cost tests, and useless for ranking ones, where the whole question is which hit came back first.
 * Over-fetching a hash-ordered result and asserting set membership would pass just as happily against a
 * backend that ranked nothing at all.
 *
 * <p>Every vector here is a unit vector in the plane spanned by the first two dimensions:
 * {@code [cos θ, sin θ, 0, 0, …]}. Cosine similarity between two of them is therefore exactly
 * {@code cos(θ₁ - θ₂)}, which makes both facts a ranking test needs checkable in closed form:
 *
 * <ul>
 *   <li><b>Order</b> -- nearest-first means ascending {@code |θ|} against a {@link #reference()} at 0°.</li>
 *   <li><b>Score</b> -- a hit's {@code similarity} must be {@code cos θ}, which is what pins each backend's
 *       conversion of its own store's score into the common cosine convention. A backend that forgot to
 *       undo Neo4j's or Atlas's {@code (1 + cos) / 2} rescaling still returns a plausible-looking ordering;
 *       only the value catches it.</li>
 * </ul>
 *
 * <p>Unscripted text lands at a hash-derived angle in the far half-plane (90°–270°), so incidental text --
 * a fixture's unrelated field, a value some other test wrote -- can never accidentally outrank a scripted
 * one and make an assertion pass for the wrong reason.
 */
public final class ScriptedEmbeddingProvider implements JavAIEmbeddingProvider {

    public static final String MODEL_ID = "scripted-test-model";
    public static final int DIMS = 8;

    private final Map<String, Double> anglesByText = new ConcurrentHashMap<>();

    /** Places {@code text} at {@code degrees} from the {@link #reference()}. Its similarity to that
     *  reference is then exactly {@code cos(degrees)}, and two scripted texts rank by absolute angle. */
    public ScriptedEmbeddingProvider at(String text, double degrees) {
        anglesByText.put(text, Math.toRadians(degrees));
        return this;
    }

    /** The vector to search with: angle 0, so scripted texts rank by their own declared angle. */
    public EmbeddingVector reference() {
        return vectorAt(0.0);
    }

    /** What {@link #at} implies a hit's {@code Ranked.similarity()} must be, for assertions in closed form. */
    public static double expectedSimilarity(double degrees) {
        return Math.cos(Math.toRadians(degrees));
    }

    @Override
    public EmbeddingVector embed(String text) {
        Double scripted = anglesByText.get(text);
        return vectorAt(scripted != null ? scripted : unscriptedAngle(text));
    }

    @Override
    public String modelId() {
        return MODEL_ID;
    }

    /** 90°–270°: never nearer to the reference than any scripted text placed inside ±90°. */
    private static double unscriptedAngle(String text) {
        double fraction = (text.hashCode() & 0x7fffffff) / (double) Integer.MAX_VALUE;
        return Math.PI / 2 + fraction * Math.PI;
    }

    private static EmbeddingVector vectorAt(double radians) {
        float[] values = new float[DIMS];
        values[0] = (float) Math.cos(radians);
        values[1] = (float) Math.sin(radians);
        return new EmbeddingVector(values, MODEL_ID, DIMS, Instant.now());
    }
}
