package dev.xtrafe.javai.persistence;

import dev.xtrafe.javai.annotations.ExternalVector;
import dev.xtrafe.javai.annotations.Vectorize;
import dev.xtrafe.javai.model.JavAIList;
import dev.xtrafe.javai.model.JavAIRuntime;
import dev.xtrafe.javai.model.JavAIVectorizable;
import dev.xtrafe.javai.vector.DirtyTrackingSupport;
import dev.xtrafe.javai.vector.EmbeddingVector;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;

import java.time.Instant;
import java.util.UUID;

/**
 * An asset carrying a text vector JavAI computes and an image vector it never could -- the OMI-290 shape.
 *
 * <p>Hand-written for the same reason {@link TestArticle} is: this module does not depend on
 * {@code javai-substrate}, so there is no weaver in these tests and the fixture stands in for one.
 */
@Entity
@ExternalVector(name = "pixels", keyField = "contentHash", model = TestImageAsset.IMAGE_MODEL)
final class TestImageAsset implements JavAIVectorizable {

    static final String IMAGE_MODEL = "fake-image-model/pp1";
    static final int IMAGE_DIMS = 16;

    @SuppressWarnings("unused") // reflectively accessed via JavAIRuntime.STATE_FIELD
    private transient DirtyTrackingSupport $javai$state;

    @Id
    private UUID id;

    @Vectorize
    private String caption;

    /** Identifies the bytes the external vector describes. JavAI never reads them. */
    private String contentHash;

    TestImageAsset() {
    }

    TestImageAsset(String caption, String contentHash) {
        this.id = UUID.randomUUID();
        this.caption = caption;
        this.contentHash = contentHash;
    }

    static EmbeddingVector imageVector(float seed) {
        float[] values = new float[IMAGE_DIMS];
        for (int i = 0; i < IMAGE_DIMS; i++) {
            values[i] = (float) Math.sin(seed * 7.0 + i);
        }
        return new EmbeddingVector(values, IMAGE_MODEL, IMAGE_DIMS, Instant.now());
    }

    UUID getId() {
        return id;
    }

    String getCaption() {
        return caption;
    }

    String getContentHash() {
        return contentHash;
    }

    void setContentHash(String contentHash) {
        this.contentHash = contentHash;
    }

    @Override
    public EmbeddingVector vector() {
        return JavAIRuntime.vector(this, "caption");
    }

    @Override
    public EmbeddingVector vector(String modelId) {
        return JavAIRuntime.vector(this, "caption", modelId);
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
    public EmbeddingVector summaryVector(String modelId) {
        return JavAIRuntime.summaryVector(this, "", "caption", modelId);
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

    @Override
    public EmbeddingVector externalVector(String vectorName) {
        return JavAIRuntime.externalVector(this, vectorName);
    }
}
