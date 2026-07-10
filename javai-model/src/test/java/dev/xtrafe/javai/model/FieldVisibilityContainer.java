package dev.xtrafe.javai.model;

import dev.xtrafe.javai.vector.DirtyTrackingSupport;
import dev.xtrafe.javai.vector.EmbeddingVector;
import dev.xtrafe.javai.vector.JavAIDirtyTracking;

import dev.xtrafe.javai.annotations.SearchVisibility;

/** Proves field-level {@code @SearchVisibility(PRIVATE)} blocks {@code query()} traversal entirely. */
final class FieldVisibilityContainer implements JavAIVectorizable, JavAIDirtyTracking {

    @SuppressWarnings("unused") // reflectively accessed via JavAIRuntime.STATE_FIELD
    private DirtyTrackingSupport $javai$state;

    @SearchVisibility(SearchVisibility.Visibility.PRIVATE)
    TestNode hiddenChild;

    TestNode visibleChild;

    void setHiddenChild(TestNode hiddenChild) {
        this.hiddenChild = hiddenChild;
        JavAIRuntime.registerDependency(this, hiddenChild);
        JavAIRuntime.propagateDirty(this);
    }

    void setVisibleChild(TestNode visibleChild) {
        this.visibleChild = visibleChild;
        JavAIRuntime.registerDependency(this, visibleChild);
        JavAIRuntime.propagateDirty(this);
    }

    @Override
    public EmbeddingVector vector() {
        return JavAIRuntime.vector(this, "");
    }

    @Override
    public EmbeddingVector summaryVector() {
        return JavAIRuntime.summaryVector(this, "hiddenChild,visibleChild", "");
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
