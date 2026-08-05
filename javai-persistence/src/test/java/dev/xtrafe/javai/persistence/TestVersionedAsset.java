package dev.xtrafe.javai.persistence;

import dev.xtrafe.javai.annotations.Vectorize;
import dev.xtrafe.javai.model.JavAIList;
import dev.xtrafe.javai.model.JavAIRuntime;
import dev.xtrafe.javai.model.JavAIVectorizable;
import dev.xtrafe.javai.vector.DirtyTrackingSupport;
import dev.xtrafe.javai.vector.EmbeddingVector;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Version;

import java.util.UUID;

/** The cascaded, independently-versioned member of OMI-254's fixture -- see {@link TestVersionedAlbum}. */
@Entity
final class TestVersionedAsset implements JavAIVectorizable {

    @SuppressWarnings("unused") // reflectively accessed via JavAIRuntime.STATE_FIELD
    private transient DirtyTrackingSupport $javai$state;

    @Id
    private UUID id;

    @Version
    private long version;

    @Vectorize
    private String caption;

    TestVersionedAsset() {
    }

    TestVersionedAsset(String caption) {
        this.id = UUID.randomUUID();
        this.caption = caption;
    }

    UUID getId() {
        return id;
    }

    long getVersion() {
        return version;
    }

    String getCaption() {
        return caption;
    }

    void setCaption(String caption) {
        String oldValue = this.caption;
        this.caption = caption;
        JavAIRuntime.vectorizeFieldMutated(this, "caption", oldValue, caption);
    }

    @Override
    public EmbeddingVector vector() {
        return JavAIRuntime.vector(this, "caption");
    }

    @Override
    public EmbeddingVector concatenatedTextVector() {
        return JavAIRuntime.concatenatedTextVector(this, "caption");
    }

    @Override
    public EmbeddingVector summaryVector() {
        return JavAIRuntime.summaryVector(this, "", "caption");
    }

    @Override
    public double similarityTo(JavAIVectorizable other) {
        return JavAIRuntime.similarityToVectorizable(this, "caption", other);
    }

    @Override
    public double similarityTo(EmbeddingVector reference) {
        return JavAIRuntime.similarityToReference(this, "caption", reference);
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
