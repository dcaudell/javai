package dev.xtrafe.javai.model.benchmark;

import dev.xtrafe.javai.model.JavAIList;
import dev.xtrafe.javai.model.JavAIRuntime;
import dev.xtrafe.javai.model.JavAIVectorizable;
import dev.xtrafe.javai.vector.DirtyTrackingSupport;
import dev.xtrafe.javai.vector.EmbeddingVector;
import dev.xtrafe.javai.vector.JavAIDirtyTracking;

/**
 * Minimal hand-woven {@code @JavAIVectorizable} fixture (one {@code @Vectorize}-equivalent field, "text"),
 * self-contained within this benchmark package rather than reusing {@code dev.xtrafe.javai.model}'s own
 * correctness-test fixtures (e.g. {@code TestNode}): those are package-private, and Java grants a
 * subpackage no special access to a parent package's package-private members. Duplicating a fixture this
 * small keeps every benchmark suite decoupled from whatever the correctness tests happen to use -- the
 * same reasoning this project already applies to duplicating {@code FakeEmbeddingProvider} per module
 * rather than sharing test code across module boundaries via a test-jar dependency.
 *
 * <p>The {@code $javai$state} field name must match {@link JavAIRuntime#STATE_FIELD} exactly, same
 * requirement as every other hand-written stand-in for woven bytecode in this project.
 */
final class BenchmarkVectorizableNode implements JavAIVectorizable, JavAIDirtyTracking {

    @SuppressWarnings("unused") // reflectively accessed via JavAIRuntime.STATE_FIELD
    private DirtyTrackingSupport $javai$state;

    private String text;

    BenchmarkVectorizableNode(String text) {
        this.text = text;
    }

    void setText(String text) {
        String oldValue = this.text;
        this.text = text;
        JavAIRuntime.vectorizeFieldMutated(this, "text", oldValue, text);
    }

    @Override
    public EmbeddingVector vector() {
        return JavAIRuntime.vector(this, "text");
    }

    @Override
    public EmbeddingVector concatenatedTextVector() {
        return JavAIRuntime.concatenatedTextVector(this, "text");
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
