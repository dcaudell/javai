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
import jakarta.persistence.OneToMany;

import java.util.UUID;

/**
 * The grandparent of OMI-255's containment fixture, and the reason the reverse mapping has to come from the
 * database rather than from the saving session's object graph.
 *
 * <p>A caller that loads a {@link TestShelf} through its own repository and adds a {@link TestBook} to it
 * holds no {@code TestLibrary} instance at all -- so an in-memory back-edge walk cannot reach this level, and
 * before OMI-255 this library's summary silently kept whatever value the last caller who <em>happened</em> to
 * load it left behind. Across a multi-pod deployment that is the normal case, not the exotic one.
 */
@Entity
final class TestLibrary implements JavAIVectorizable {

    @SuppressWarnings("unused") // reflectively accessed via JavAIRuntime.STATE_FIELD
    private transient DirtyTrackingSupport $javai$state;

    @Id
    private UUID id;

    @Vectorize
    private String name;

    @Summary
    @OneToMany(cascade = { CascadeType.PERSIST, CascadeType.MERGE })
    private JavAIList<TestShelf> shelves = new JavAIArrayList<>();

    TestLibrary() {
    }

    TestLibrary(String name) {
        this.id = UUID.randomUUID();
        this.name = name;
    }

    UUID getId() {
        return id;
    }

    String getName() {
        return name;
    }

    JavAIList<TestShelf> getShelves() {
        return shelves;
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
        return JavAIRuntime.summaryVector(this, "shelves", "name");
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
