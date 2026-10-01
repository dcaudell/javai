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
 * A container that <b>opts into persisted per-model summaries</b> (OMI-458) -- the middle tier of the
 * nesting fixture, holding {@link TestImageAsset}s whose only vector in the image model is an
 * {@code @ExternalVector} nothing in this process could compute.
 *
 * <p>Its members carry an ordinary {@code @Vectorize} caption <em>as well</em>, deliberately: an entity
 * that has an external vector very often has a text one too, so this album has two coherent summaries and
 * every assertion about one has to hold without disturbing the other.
 *
 * <p>Hand-implements {@link JavAIVectorizable} for the reason every fixture in this module does: no weaver
 * here (see {@link TestArticle}). {@link TestPhotoExhibition} is the tier above it.
 */
@Entity
@Summary(persistModelSummaries = true)
final class TestPhotoAlbum implements JavAIVectorizable {

    static final String SUMMARY_FIELDS = "photos";

    @SuppressWarnings("unused") // reflectively accessed via JavAIRuntime.STATE_FIELD
    private transient DirtyTrackingSupport $javai$state;

    @Id
    private UUID id;

    @Vectorize
    private String title;

    @ManyToMany(cascade = { CascadeType.PERSIST, CascadeType.MERGE }, fetch = FetchType.LAZY)
    @JoinTable(name = "test_photo_album_photos")
    @Summary
    private JavAIList<TestImageAsset> photos = new JavAIArrayList<>();

    TestPhotoAlbum() {
    }

    TestPhotoAlbum(String title) {
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
