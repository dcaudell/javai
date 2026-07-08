package dev.xtrafe.javai.collections;

import dev.xtrafe.javai.runtime.DirtyTrackingSupport;
import dev.xtrafe.javai.runtime.EmbeddingVector;
import dev.xtrafe.javai.runtime.JavAIDirtyTracking;
import dev.xtrafe.javai.runtime.JavAIList;
import dev.xtrafe.javai.runtime.JavAIRuntime;
import dev.xtrafe.javai.runtime.JavAIVectorizable;

/**
 * Hand-written stand-in for what a woven {@code @JavAIVectorizable} class would look like -- same
 * pattern as {@code javai-runtime}'s own {@code TestNode}, reimplemented here since that one is
 * package-private in a different module. Delegates everything to {@link JavAIRuntime}, exactly like real
 * woven bytecode would.
 */
final class TestVectorNode implements JavAIVectorizable, JavAIDirtyTracking {

    @SuppressWarnings("unused") // reflectively accessed via JavAIRuntime.STATE_FIELD
    private DirtyTrackingSupport $javai$state;

    private String text;

    TestVectorNode(String text) {
        this.text = text;
    }

    void setText(String text) {
        this.text = text;
        JavAIRuntime.markFieldDirty(this);
        JavAIRuntime.propagateDirty(this);
    }

    @Override
    public EmbeddingVector vector() {
        return JavAIRuntime.vector(this, "text");
    }

    @Override
    public EmbeddingVector summaryVector() {
        return JavAIRuntime.summaryVector(this, "", "text");
    }

    @Override
    public double similarityTo(JavAIVectorizable other) {
        return JavAIRuntime.similarityToVectorizable(this, "text", other);
    }

    @Override
    public double similarityTo(EmbeddingVector reference) {
        return JavAIRuntime.similarityToReference(this, "text", reference);
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
