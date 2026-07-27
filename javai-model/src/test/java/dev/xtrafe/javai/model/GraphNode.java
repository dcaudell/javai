package dev.xtrafe.javai.model;

import dev.xtrafe.javai.annotations.Vectorize;
import dev.xtrafe.javai.vector.DirtyTrackingSupport;
import dev.xtrafe.javai.vector.EmbeddingVector;
import dev.xtrafe.javai.vector.JavAIDirtyTracking;

/**
 * A general-purpose hand-woven {@code @JavAIVectorizable} fixture for building <em>arbitrary</em> object
 * graphs: one {@code @Vectorize} field ({@code label}) and four {@code @Summary} containment slots covering
 * every shape the runtime has to walk -- a singular reference plus all three JavAI collection types.
 *
 * <p>{@link TestNode}/{@link TestContainer} exist for the lifecycle tests and are deliberately minimal (one
 * reference, one list). OMI-187 needed something that could express deep chains, wide fan-out, diamonds,
 * cycles, and collections nested inside collections without inventing a fixture per shape -- so the
 * collection element type is {@link JavAIVectorizable} rather than {@code GraphNode}, which is what lets a
 * {@link JavAIArrayList} (itself a {@code JavAIVectorizable}) be an element of another collection.
 *
 * <p>Mirrors what the weaver generates, same as its siblings: the {@code $javai$state} field name must
 * match {@link JavAIRuntime#STATE_FIELD}, and every method delegates straight to {@link JavAIRuntime}.
 */
final class GraphNode implements JavAIVectorizable, JavAIDirtyTracking {

    /** The {@code @Summary} field list, in the order the runtime is told to walk them. */
    static final String SUMMARY_FIELDS = "singular,list,set,map";

    /** The {@code @Vectorize} field list. */
    static final String VECTORIZE_FIELDS = "label";

    @SuppressWarnings("unused") // reflectively accessed via JavAIRuntime.STATE_FIELD
    private DirtyTrackingSupport $javai$state;

    private final String name;
    @Vectorize
    private String label;

    private GraphNode singular;
    private final JavAIArrayList<JavAIVectorizable> list = new JavAIArrayList<>();
    private final JavAILinkedHashSet<JavAIVectorizable> set = new JavAILinkedHashSet<>();
    private final JavAILinkedHashMap<String, JavAIVectorizable> map = new JavAILinkedHashMap<>();

    GraphNode(String name, String label) {
        this.name = name;
        this.label = label;
        // What a woven constructor's registerFieldDependencies does for inline-initialized @Summary
        // collections -- without it, adding through getList() would never mark this node SummaryDirty.
        JavAIRuntime.registerDependency(this, list);
        JavAIRuntime.registerDependency(this, set);
        JavAIRuntime.registerDependency(this, map);
    }

    String label() {
        return label;
    }

    void setLabel(String label) {
        String oldValue = this.label;
        this.label = label;
        JavAIRuntime.vectorizeFieldMutated(this, "label", oldValue, label);
    }

    void setSingular(GraphNode singular) {
        this.singular = singular;
        JavAIRuntime.registerDependency(this, singular);
        JavAIRuntime.propagateDirty(this);
    }

    JavAIArrayList<JavAIVectorizable> getList() {
        return list;
    }

    JavAILinkedHashSet<JavAIVectorizable> getSet() {
        return set;
    }

    JavAILinkedHashMap<String, JavAIVectorizable> getMap() {
        return map;
    }

    @Override
    public String toString() {
        return name;
    }

    @Override
    public EmbeddingVector vector() {
        return JavAIRuntime.vector(this, VECTORIZE_FIELDS);
    }

    @Override
    public EmbeddingVector concatenatedTextVector() {
        return JavAIRuntime.concatenatedTextVector(this, VECTORIZE_FIELDS);
    }

    @Override
    public EmbeddingVector summaryVector() {
        return JavAIRuntime.summaryVector(this, SUMMARY_FIELDS, VECTORIZE_FIELDS);
    }

    @Override
    public double similarityTo(JavAIVectorizable other) {
        return JavAIRuntime.similarityToVectorizable(this, VECTORIZE_FIELDS, other);
    }

    @Override
    public double similarityTo(EmbeddingVector reference) {
        return JavAIRuntime.similarityToReference(this, VECTORIZE_FIELDS, reference);
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
