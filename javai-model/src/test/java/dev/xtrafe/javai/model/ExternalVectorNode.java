package dev.xtrafe.javai.model;

import dev.xtrafe.javai.annotations.ExternalVector;
import dev.xtrafe.javai.annotations.Vectorize;
import dev.xtrafe.javai.vector.DirtyTrackingSupport;
import dev.xtrafe.javai.vector.EmbeddingVector;
import dev.xtrafe.javai.vector.JavAIDirtyTracking;

/**
 * A node carrying both kinds of vector at once: a {@code @Vectorize} caption JavAI computes, and an
 * {@code @ExternalVector} over content it never sees, supplied from outside in a different model and a
 * different dimensionality.
 *
 * <p>That combination is the point -- it is the shape that made OMI-290 necessary, and the shape every
 * aggregate has to survive. Hand-written for the same reason {@link TestNode} is: this module cannot
 * depend on {@code javai-substrate}, so it stands in for what the weaver would generate.
 *
 * <p>{@link #contentKey} is deliberately mutable through both a setter <em>and</em> direct assignment
 * ({@link #assignContentKeyBypassingTheSetter}), because an external vector's staleness must not depend on
 * the mutation having been observed -- unlike a {@code @Vectorize} field's.
 */
@ExternalVector(name = "pixels", keyField = "contentKey", model = ExternalVectorNode.IMAGE_MODEL)
final class ExternalVectorNode implements JavAIVectorizable, JavAIDirtyTracking {

    static final String IMAGE_MODEL = "fake-image-model/pp1";
    /** Deliberately unequal to {@code FakeEmbeddingProvider.DIMS}, so mixing the two is a real mismatch. */
    static final int IMAGE_DIMS = 16;

    @SuppressWarnings("unused") // reflectively accessed via JavAIRuntime.STATE_FIELD
    private DirtyTrackingSupport $javai$state;

    @Vectorize
    private String caption;

    /** Identifies the content the external vector describes -- never the content itself. */
    private String contentKey;

    ExternalVectorNode(String caption, String contentKey) {
        this.caption = caption;
        this.contentKey = contentKey;
    }

    String getContentKey() {
        return contentKey;
    }

    void setCaption(String caption) {
        String oldValue = this.caption;
        this.caption = caption;
        JavAIRuntime.vectorizeFieldMutated(this, "caption", oldValue, caption);
    }

    /** An ordinary setter for the key field -- note it fires no JavAI hook, because there is none to fire. */
    void setContentKey(String contentKey) {
        this.contentKey = contentKey;
    }

    /** Writes the field the way a framework populating it reflectively would: no accessor, no interception. */
    void assignContentKeyBypassingTheSetter(String contentKey) {
        this.contentKey = contentKey;
    }

    /**
     * A vector shaped like the external model's output -- deterministic in {@code seed}, and pointing in a
     * genuinely different <em>direction</em> for each one.
     *
     * <p>⚠️ It has to vary by direction rather than magnitude, and this is worth stating because getting it
     * wrong makes a test quietly measure nothing: every aggregate here ends in {@code VectorMath.normalize},
     * so a fixture filling every component with the seed produces vectors that differ only in length and
     * therefore normalize to the identical unit vector. A test asserting two different subtrees summarize
     * differently then compares two copies of the same number and fails -- or, worse in the other
     * direction, an assertion of equality would have passed for no reason at all.
     */
    static EmbeddingVector imageVector(float seed) {
        float[] values = new float[IMAGE_DIMS];
        for (int i = 0; i < IMAGE_DIMS; i++) {
            values[i] = (float) Math.sin(seed * 7.0 + i);
        }
        return new EmbeddingVector(values, IMAGE_MODEL, IMAGE_DIMS, java.time.Instant.now());
    }

    @Override
    public EmbeddingVector vector() {
        return JavAIRuntime.vector(this, "caption");
    }

    @Override
    public EmbeddingVector vector(String modelId) {
        return JavAIRuntime.vector(this, "caption", modelId);
    }

    @Override
    public EmbeddingVector summaryVector(String modelId) {
        return JavAIRuntime.summaryVector(this, "", "caption", modelId);
    }

    @Override
    public EmbeddingVector concatenatedTextVector() {
        return JavAIRuntime.concatenatedTextVector(this, "caption");
    }

    @Override
    public EmbeddingVector summaryVector() {
        return JavAIRuntime.summaryVector(this, "", "caption");
    }

    @Override
    public double similarityTo(JavAIVectorizable other) {
        return JavAIRuntime.similarityToVectorizable(this, "caption", other);
    }

    @Override
    public double similarityTo(EmbeddingVector reference) {
        return JavAIRuntime.similarityToReference(this, "caption", reference);
    }

    @Override
    public <T> JavAIList<T> query(EmbeddingVector reference, Class<T> type) {
        return JavAIRuntime.query(this, reference, type, Integer.MAX_VALUE);
    }

    @Override
    public <T> JavAIList<T> query(EmbeddingVector reference, Class<T> type, int maxDepth) {
        return JavAIRuntime.query(this, reference, type, maxDepth);
    }

    @Override
    public EmbeddingVector fieldVector(String fieldName) {
        return JavAIRuntime.fieldVector(this, fieldName);
    }

    @Override
    public EmbeddingVector externalVector(String vectorName) {
        return JavAIRuntime.externalVector(this, vectorName);
    }

    @Override
    public void addDependent(Object dependent) {
        JavAIRuntime.addDependent(this, dependent);
    }

    @Override
    public Iterable<Object> dependents() {
        return JavAIRuntime.dependents(this);
    }

    @Override
    public boolean isFieldDirty() {
        return JavAIRuntime.isFieldDirty(this);
    }

    @Override
    public void markFieldDirty() {
        JavAIRuntime.markFieldDirty(this);
    }

    @Override
    public void clearFieldDirty() {
        JavAIRuntime.clearFieldDirty(this);
    }

    @Override
    public boolean isSummaryDirty() {
        return JavAIRuntime.isSummaryDirty(this);
    }

    @Override
    public void markSummaryDirty() {
        JavAIRuntime.markSummaryDirty(this);
    }

    @Override
    public void clearSummaryDirty() {
        JavAIRuntime.clearSummaryDirty(this);
    }
}
