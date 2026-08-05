package dev.xtrafe.javai.persistence;

import dev.xtrafe.javai.annotations.Summary;
import dev.xtrafe.javai.annotations.Vectorize;
import dev.xtrafe.javai.model.JavAIArrayList;
import dev.xtrafe.javai.model.JavAIList;
import dev.xtrafe.javai.model.JavAIRuntime;
import dev.xtrafe.javai.model.JavAIVectorizable;
import dev.xtrafe.javai.vector.DirtyTrackingSupport;
import dev.xtrafe.javai.vector.EmbeddingVector;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToMany;

import java.util.UUID;

/**
 * The middle level of OMI-255's containment fixture: a {@code @Summary} container that is itself contained
 * by a {@code @Summary} container ({@link TestLibrary}).
 *
 * <p>{@code @ManyToMany} rather than {@code @OneToMany}, mirroring the reported shape: a book may sit on
 * several shelves, so membership is a join row and adding one touches nothing the other adder touches. That
 * is what makes the summary row the only thing two concurrent adders share.
 */
@Entity
final class TestShelf implements JavAIVectorizable {

    @SuppressWarnings("unused") // reflectively accessed via JavAIRuntime.STATE_FIELD
    private transient DirtyTrackingSupport $javai$state;

    @Id
    private UUID id;

    @Vectorize
    private String label;

    /** Interface-typed and non-final, per the native-mapping rule -- Hibernate substitutes its own instance. */
    @Summary
    @ManyToMany(cascade = { CascadeType.PERSIST, CascadeType.MERGE })
    private JavAIList<TestBook> books = new JavAIArrayList<>();

    TestShelf() {
    }

    TestShelf(String label) {
        this.id = UUID.randomUUID();
        this.label = label;
    }

    UUID getId() {
        return id;
    }

    String getLabel() {
        return label;
    }

    void setLabel(String label) {
        String oldValue = this.label;
        this.label = label;
        JavAIRuntime.vectorizeFieldMutated(this, "label", oldValue, label);
    }

    JavAIList<TestBook> getBooks() {
        return books;
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
        return JavAIRuntime.summaryVector(this, "books", "label");
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
