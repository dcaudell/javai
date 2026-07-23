package dev.xtrafe.javai.persistence;

import dev.xtrafe.javai.annotations.Vectorize;
import dev.xtrafe.javai.vector.DirtyTrackingSupport;
import dev.xtrafe.javai.vector.EmbeddingVector;
import dev.xtrafe.javai.model.JavAIList;
import dev.xtrafe.javai.model.JavAIRuntime;
import dev.xtrafe.javai.model.JavAIVectorizable;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;

import java.util.UUID;

/**
 * OMI-161's failing shape: a {@code @JavAIVectorizable} entity holding a <b>{@code FetchType.LAZY}</b>
 * singular association to <em>another</em> {@code @JavAIVectorizable} entity ({@link TestAssocTarget}).
 *
 * <p>Lazy is the whole point. {@code save()} merges the caller's detached instance, and Hibernate's merged
 * copy holds an <em>uninitialized proxy</em> for a lazy singular association rather than loading the target
 * -- so the object this backend's own {@code writeVectorsForRelatedEntities} walk finds in this field is a
 * {@code TestAssocTarget$HibernateProxy$...}, not a {@code TestAssocTarget}. It still passes
 * {@code instanceof JavAIVectorizable} (the proxy subclasses the real entity), but its {@code @Id} field is
 * never populated -- the proxy delegates through an interceptor -- so reading that field reflectively
 * yields {@code null} and the vector INSERT violates {@code owner_id}'s NOT NULL constraint.
 *
 * <p>Contrast {@link TestEagerAssocOwner}, identical but for {@code FetchType.EAGER}, which is why this went
 * unnoticed: every pre-existing singular association in this project's own fixtures (and JavAI's shipped
 * {@code Tag} -> {@code TagSet}) is eager, so the walk always saw a real, initialized instance.
 */
@Entity
class TestLazyAssocOwner implements JavAIVectorizable {

    @SuppressWarnings("unused") // reflectively accessed via JavAIRuntime.STATE_FIELD
    private transient DirtyTrackingSupport $javai$state;

    @Id
    private UUID id;

    @Vectorize
    private String label;

    @ManyToOne(fetch = FetchType.LAZY)
    private TestAssocTarget target;

    TestLazyAssocOwner() {
    }

    TestLazyAssocOwner(String label, TestAssocTarget target) {
        this.label = label;
        this.target = target;
    }

    UUID getId() {
        return id;
    }

    String getLabel() {
        return label;
    }

    TestAssocTarget getTarget() {
        return target;
    }

    void setTarget(TestAssocTarget target) {
        this.target = target;
    }

    void setLabel(String label) {
        String oldValue = this.label;
        this.label = label;
        JavAIRuntime.vectorizeFieldMutated(this, "label", oldValue, label);
    }

    @Override
    public EmbeddingVector vector() {
        return JavAIRuntime.vector(this, "label");
    }

    @Override
    public EmbeddingVector concatenatedTextVector() {
        return JavAIRuntime.concatenatedTextVector(this, "label");
    }

    @Override
    public EmbeddingVector summaryVector() {
        return JavAIRuntime.summaryVector(this, "", "label");
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
}
