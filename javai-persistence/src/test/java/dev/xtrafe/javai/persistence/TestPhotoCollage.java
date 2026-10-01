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
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;

import java.util.UUID;

/**
 * The same shape as {@link TestPhotoAlbum} with the opt-in left off -- the fallback fixture (OMI-458).
 *
 * <p>Its image-model summary is exactly as real and exactly as computable; nothing stores it. So a
 * {@code nearestBySummary(IMAGE_MODEL)} against this type must return the same ranking the album's indexed
 * search returns, by folding, rather than the empty list an unprovisioned table would give back.
 */
@Entity
final class TestPhotoCollage implements JavAIVectorizable {

    static final String SUMMARY_FIELDS = "photos";

    @SuppressWarnings("unused") // reflectively accessed via JavAIRuntime.STATE_FIELD
    private transient DirtyTrackingSupport $javai$state;

    @Id
    private UUID id;

    @Vectorize
    private String title;

    @ManyToMany(cascade = { CascadeType.PERSIST, CascadeType.MERGE }, fetch = FetchType.LAZY)
    @JoinTable(name = "test_photo_collage_photos")
    @Summary
    private JavAIList<TestImageAsset> photos = new JavAIArrayList<>();

    TestPhotoCollage() {
    }

    TestPhotoCollage(String title) {
        this.id = UUID.randomUUID();
        this.title = title;
    }

    UUID getId() {
        return id;
    }

    String getTitle() {
        return title;
    }

    JavAIList<TestImageAsset> getPhotos() {
        return photos;
    }

    @Override
    public EmbeddingVector vector() {
        return JavAIRuntime.vector(this, "title");
    }

    @Override
    public EmbeddingVector vector(String modelId) {
        return JavAIRuntime.vector(this, "title", modelId);
    }

    @Override
    public EmbeddingVector concatenatedTextVector() {
        return JavAIRuntime.concatenatedTextVector(this, "title");
    }

    @Override
    public EmbeddingVector summaryVector() {
        return JavAIRuntime.summaryVector(this, SUMMARY_FIELDS, "title");
    }

    @Override
    public EmbeddingVector summaryVector(String modelId) {
        return JavAIRuntime.summaryVector(this, SUMMARY_FIELDS, "title", modelId);
    }

    @Override
    public double similarityTo(JavAIVectorizable other) {
        return JavAIRuntime.similarityToVectorizable(this, "title", other);
    }

    @Override
    public double similarityTo(EmbeddingVector reference) {
        return JavAIRuntime.similarityToReference(this, "title", reference);
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
