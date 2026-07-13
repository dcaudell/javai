package dev.xtrafe.javai.model;

import dev.xtrafe.javai.vector.DirtyTrackingSupport;
import dev.xtrafe.javai.vector.EmbeddingVector;
import dev.xtrafe.javai.vector.JavAIDirtyTracking;

import dev.xtrafe.javai.annotations.SearchVisibility;

/**
 * Proves type-level {@code @SearchVisibility(PRIVATE)} blocks {@code query()} *matching* only --
 * instances of this type are never returned as a hit, but traversal continues through them so their own
 * descendants stay reachable (a deliberate pass-through node).
 */
@SearchVisibility(SearchVisibility.Visibility.PRIVATE)
final class InvisibleTypeNode implements JavAIVectorizable, JavAIDirtyTracking {

    @SuppressWarnings("unused") // reflectively accessed via JavAIRuntime.STATE_FIELD
    private DirtyTrackingSupport $javai$state;

    TestNode child;

    void setChild(TestNode child) {
        this.child = child;
        JavAIRuntime.registerDependency(this, child);
        JavAIRuntime.propagateDirty(this);
    }

    @Override
    public EmbeddingVector vector() {
        return JavAIRuntime.vector(this, "");
    }

    @Override
    public EmbeddingVector concatenatedTextVector() {
        return JavAIRuntime.concatenatedTextVector(this, "");
    }

    @Override
    public EmbeddingVector summaryVector() {
        return JavAIRuntime.summaryVector(this, "child", "");
    }

    @Override
    public double similarityTo(JavAIVectorizable other) {
        return JavAIRuntime.similarityToVectorizable(this, "", other);
    }

    @Override
    public double similarityTo(EmbeddingVector reference) {
        return JavAIRuntime.similarityToReference(this, "", reference);
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
