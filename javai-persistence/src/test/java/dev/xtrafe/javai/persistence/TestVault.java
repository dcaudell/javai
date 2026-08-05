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
 * A {@code @Summary} container whose {@code @Id} field is <b>not called {@code id}</b>.
 *
 * <p>Identity is located by annotation everywhere else in this module, but the reverse containment lookup
 * OMI-255 added builds HQL naming the key field, and the first version of it simply wrote {@code p.id}. That
 * works for every other fixture here and for the entity conventions of the consuming project that reported
 * the ticket -- which is exactly why it needed a fixture of its own: the failure would have reached a
 * consumer, not a test, and would have arrived as a query-parsing error a long way from its cause.
 */
@Entity
final class TestVault implements JavAIVectorizable {

    @SuppressWarnings("unused") // reflectively accessed via JavAIRuntime.STATE_FIELD
    private transient DirtyTrackingSupport $javai$state;

    /** Deliberately not {@code id}. */
    @Id
    private UUID vaultKey;

    @Vectorize
    private String name;

    @Summary
    @ManyToMany(cascade = { CascadeType.PERSIST, CascadeType.MERGE })
    private JavAIList<TestBook> books = new JavAIArrayList<>();

    TestVault() {
    }

    TestVault(String name) {
        this.vaultKey = UUID.randomUUID();
        this.name = name;
    }

    UUID getVaultKey() {
        return vaultKey;
    }

    JavAIList<TestBook> getBooks() {
        return books;
    }

    @Override
    public EmbeddingVector vector() {
        return JavAIRuntime.vector(this, "name");
    }

    @Override
    public EmbeddingVector concatenatedTextVector() {
        return JavAIRuntime.concatenatedTextVector(this, "name");
    }

    @Override
    public EmbeddingVector summaryVector() {
        return JavAIRuntime.summaryVector(this, "books", "name");
    }

    @Override
    public double similarityTo(JavAIVectorizable other) {
        return JavAIRuntime.similarityToVectorizable(this, "name", other);
    }

    @Override
    public double similarityTo(EmbeddingVector reference) {
        return JavAIRuntime.similarityToReference(this, "name", reference);
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
