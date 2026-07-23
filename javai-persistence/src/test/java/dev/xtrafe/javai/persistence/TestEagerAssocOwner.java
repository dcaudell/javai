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
 * The control for {@link TestLazyAssocOwner}: byte-for-byte identical except {@code FetchType.EAGER}. If
 * this saves cleanly while its lazy twin fails, the trigger is the uninitialized Hibernate proxy, not the
 * association itself, not the target type, and not how the owner was woven (neither fixture is woven at
 * all -- both are hand-written stand-ins, see {@link TestArticle}).
 */
@Entity
class TestEagerAssocOwner implements JavAIVectorizable {

    @SuppressWarnings("unused") // reflectively accessed via JavAIRuntime.STATE_FIELD
    private transient DirtyTrackingSupport $javai$state;

    @Id
    private UUID id;

    @Vectorize
    private String label;

    @ManyToOne(fetch = FetchType.EAGER)
    private TestAssocTarget target;

    TestEagerAssocOwner() {
    }

    TestEagerAssocOwner(String label, TestAssocTarget target) {
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
