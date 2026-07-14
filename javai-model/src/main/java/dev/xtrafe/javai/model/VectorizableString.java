package dev.xtrafe.javai.model;

import dev.xtrafe.javai.vector.EmbeddingVector;
import dev.xtrafe.javai.vector.VectorCacheSlot;
import dev.xtrafe.javai.vector.VectorMath;

/**
 * An immutable box around a {@code String} leaf value -- the "recursive purity" primitive every
 * {@code @Vectorize} field is internally backed by (see {@code DirtyTrackingSupport}'s per-field box map),
 * kept entirely invisible to JPA: a declared {@code private String title;} field never changes type in
 * user source, this box only ever exists as internal bookkeeping the weaver/runtime maintain alongside it.
 *
 * <p>Because the wrapped value can never change after construction (a field mutation replaces the box
 * wholesale with a brand new instance -- the same shape this project already uses for a
 * {@code @Summary} field being reassigned to a different child object, not new machinery), this box's own
 * {@link VectorCacheSlot} generation never advances past its first bump: there is exactly one computation
 * ever performed for a given instance, and once it lands, the box is clean forever. That's why
 * {@link #vector()} always computes via {@link JavAIRuntime#computeBlocking} regardless of the globally
 * configured {@link EmbeddingConsistencyMode} -- there's no "subsequent" recomputation to make eager or
 * lazy; only ever the one, and per both modes' own rules, a slot's very first computation always blocks
 * (there's no prior real value to serve in the meantime).
 *
 * <p>{@link #query} intentionally does not delegate to {@link JavAIRuntime#query} -- reflecting over this
 * class's own fields would walk into its internal {@link VectorCacheSlot}, which is bookkeeping, not
 * domain data. A leaf has nothing reachable beneath it, so an empty result is always correct directly.
 */
public final class VectorizableString implements JavAIVectorizable {

    private final String value;
    private final VectorCacheSlot slot = new VectorCacheSlot();

    public VectorizableString(String value) {
        this.value = value == null ? "" : value;
    }

    public String value() {
        return value;
    }

    @Override
    public EmbeddingVector vector() {
        if (slot.isDirty()) {
            return JavAIRuntime.computeBlocking(slot, slot.currentGeneration(), value);
        }
        return slot.cachedValue();
    }

    /** Identical to {@link #vector()} for a leaf -- there are no sibling fields to concatenate text from. */
    @Override
    public EmbeddingVector concatenatedTextVector() {
        return vector();
    }

    /** Identical to {@link #vector()} for a leaf -- there are no children to fold a decayed contribution
     *  from. */
    @Override
    public EmbeddingVector summaryVector() {
        return vector();
    }

    @Override
    public double similarityTo(JavAIVectorizable other) {
        return VectorMath.cosineSimilarity(vector(), other.vector());
    }

    @Override
    public double similarityTo(EmbeddingVector reference) {
        return VectorMath.cosineSimilarity(vector(), reference);
    }

    @Override
    public <T> JavAIList<T> query(EmbeddingVector reference, Class<T> type) {
        return new JavAIArrayList<>();
    }

    @Override
    public <T> JavAIList<T> query(EmbeddingVector reference, Class<T> type, int maxDepth) {
        return new JavAIArrayList<>();
    }

    @Override
    public EmbeddingVector fieldVector(String fieldName) {
        throw new UnsupportedOperationException("VectorizableString is a leaf -- it has no @Vectorize fields of its own");
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof VectorizableString that && value.equals(that.value);
    }

    @Override
    public int hashCode() {
        return value.hashCode();
    }

    @Override
    public String toString() {
        return value;
    }
}
