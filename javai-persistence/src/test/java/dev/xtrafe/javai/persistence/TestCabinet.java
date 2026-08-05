package dev.xtrafe.javai.persistence;

import dev.xtrafe.javai.annotations.Summary;
import dev.xtrafe.javai.annotations.Vectorize;
import dev.xtrafe.javai.model.JavAIList;
import dev.xtrafe.javai.model.JavAIRuntime;
import dev.xtrafe.javai.model.JavAIVectorizable;
import dev.xtrafe.javai.vector.DirtyTrackingSupport;
import dev.xtrafe.javai.vector.EmbeddingVector;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;

import java.util.UUID;

/**
 * A container whose {@code @Summary} child is a <b>lazy singular</b> reference, which every other fixture in
 * this module lacks.
 *
 * <p>That shape is the one {@code AssociationGraphEmbeddingCostE2ETest} showed re-embedding after OMI-255,
 * and it behaves differently from a collection for a specific reason: a {@code FetchType.LAZY} to-one reads
 * back as an uninitialized Hibernate proxy -- a generated subclass carrying none of the entity's state, so
 * its {@code @Id} is null. Anything keyed on that id silently does nothing, while the object still satisfies
 * {@code instanceof JavAIVectorizable} and answers vector calls perfectly happily, by recomputing them.
 */
@Entity
final class TestCabinet implements JavAIVectorizable {

    @SuppressWarnings("unused") // reflectively accessed via JavAIRuntime.STATE_FIELD
    private transient DirtyTrackingSupport $javai$state;

    @Id
    private UUID id;

    @Vectorize
    private String label;

    @Summary
    @ManyToOne(fetch = FetchType.LAZY, cascade = CascadeType.ALL)
    private TestDossier dossier;

    TestCabinet() {
    }

    TestCabinet(String label, TestDossier dossier) {
        this.id = UUID.randomUUID();
        this.label = label;
        this.dossier = dossier;
    }

    UUID getId() {
        return id;
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
        return JavAIRuntime.summaryVector(this, "dossier", "label");
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
