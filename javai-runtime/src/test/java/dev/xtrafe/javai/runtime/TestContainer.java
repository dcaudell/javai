package dev.xtrafe.javai.runtime;

/**
 * Hand-written stand-in for a woven container class with one {@code @Vectorize} field ({@code label})
 * and two {@code @Summary} fields: a single reference ({@code featured}) and a collection
 * ({@code items}) -- proving both containment shapes propagate through {@link JavAIRuntime}'s
 * back-edge mechanism. See {@link TestNode} for why this hand-written mirror exists instead of testing
 * only through the real weaver.
 */
final class TestContainer implements JavAIVectorizable, JavAIDirtyTracking {

    @SuppressWarnings("unused") // reflectively accessed via JavAIRuntime.STATE_FIELD
    private DirtyTrackingSupport $javai$state;

    private String label;
    private TestNode featured;
    private final JavAIArrayList<TestNode> items = new JavAIArrayList<>();

    TestContainer(String label) {
        this.label = label;
        JavAIRuntime.registerDependency(this, items);
    }

    void setLabel(String label) {
        this.label = label;
        JavAIRuntime.markFieldDirty(this);
        JavAIRuntime.propagateDirty(this);
    }

    void setFeatured(TestNode featured) {
        this.featured = featured;
        JavAIRuntime.registerDependency(this, featured);
        JavAIRuntime.propagateDirty(this);
    }

    JavAIArrayList<TestNode> getItems() {
        return items;
    }

    @Override
    public EmbeddingVector vector() {
        return JavAIRuntime.vector(this, "label");
    }

    @Override
    public EmbeddingVector summaryVector() {
        return JavAIRuntime.summaryVector(this, "featured,items", "label");
    }

    @Override
    public double similarityTo(JavAIVectorizable other) {
        return JavAIRuntime.similarityToVectorizable(this, "label", other);
    }

    @Override
    public double similarityTo(EmbeddingVector reference) {
        return JavAIRuntime.similarityToReference(this, "label", reference);
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
