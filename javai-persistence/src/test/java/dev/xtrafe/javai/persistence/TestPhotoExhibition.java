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
 * ⚠️ <b>The summaries-of-summaries fixture</b> (OMI-458): a container of {@link TestPhotoAlbum}s, which are
 * themselves containers of {@link TestImageAsset}s.
 *
 * <p>Nothing in this exhibition's own fields is in the image model, and neither is anything in an album's
 * -- only the leaves carry a supplied vector. So its per-model summary exists only if the recursion is real
 * at every tier: {@code summaryVector(modelId)} folding through a container whose <em>own</em> contribution
 * is absent, the discovery walk reaching a model two hops down, and the drain walking two hops back up when
 * a leaf's vector arrives. Each of those has an independent way to be wrong, and a one-tier fixture would
 * pass while all three were.
 *
 * <p>Hand-implements {@link JavAIVectorizable} for the reason every fixture in this module does: no weaver
 * here (see {@link TestArticle}).
 */
@Entity
@Summary(persistModelSummaries = true)
final class TestPhotoExhibition implements JavAIVectorizable {

    static final String SUMMARY_FIELDS = "albums";

    @SuppressWarnings("unused") // reflectively accessed via JavAIRuntime.STATE_FIELD
    private transient DirtyTrackingSupport $javai$state;

    @Id
    private UUID id;

    @Vectorize
    private String title;

    @ManyToMany(cascade = { CascadeType.PERSIST, CascadeType.MERGE }, fetch = FetchType.LAZY)
    @JoinTable(name = "test_photo_exhibition_albums")
    @Summary
    private JavAIList<TestPhotoAlbum> albums = new JavAIArrayList<>();

    TestPhotoExhibition() {
    }

    TestPhotoExhibition(String title) {
        this.id = UUID.randomUUID();
        this.title = title;
    }

    UUID getId() {
        return id;
    }

    String getTitle() {
        return title;
    }

    JavAIList<TestPhotoAlbum> getAlbums() {
        return albums;
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
