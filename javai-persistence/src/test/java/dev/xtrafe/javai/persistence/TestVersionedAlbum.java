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
import jakarta.persistence.Version;

import java.util.UUID;

/**
 * OMI-254's optimistic-locking fixture: a {@code @Version}-bearing container holding {@code @Version}-bearing
 * members, deliberately shaped like the {@code gallery.Album} the ticket was reported against -- a container
 * that is loaded, mutated and saved repeatedly, which is the pattern that made the defect unavoidable.
 *
 * <p>The members are {@code @Version}ed too, and cascaded, so a save writes more than one versioned row. That
 * is what distinguishes "the root's version is refreshed" from "every written entity's version is refreshed";
 * refreshing only the root would leave the identical defect one hop down.
 *
 * <p>Same hand-written {@code JavAIVectorizable} stand-in pattern as every other fixture in this module --
 * see {@link TestArticle}'s javadoc for why there is no weaver here.
 */
@Entity
final class TestVersionedAlbum implements JavAIVectorizable {

    @SuppressWarnings("unused") // reflectively accessed via JavAIRuntime.STATE_FIELD
    private transient DirtyTrackingSupport $javai$state;

    @Id
    private UUID id;

    @Version
    private long version;

    @Vectorize
    private String description;

    /** Interface-typed and non-final, per the native-mapping rule -- Hibernate substitutes its own instance. */
    @Summary
    @OneToMany(cascade = { CascadeType.PERSIST, CascadeType.MERGE })
    private JavAIList<TestVersionedAsset> assets = new JavAIArrayList<>();

    TestVersionedAlbum() {
    }

    TestVersionedAlbum(String description) {
        this.id = UUID.randomUUID();
        this.description = description;
    }

    UUID getId() {
        return id;
    }

    long getVersion() {
        return version;
    }

    String getDescription() {
        return description;
    }

    void setDescription(String description) {
        String oldValue = this.description;
        this.description = description;
        JavAIRuntime.vectorizeFieldMutated(this, "description", oldValue, description);
    }

    JavAIList<TestVersionedAsset> getAssets() {
        return assets;
    }

    @Override
    public EmbeddingVector vector() {
        return JavAIRuntime.vector(this, "description");
    }

    @Override
    public EmbeddingVector concatenatedTextVector() {
        return JavAIRuntime.concatenatedTextVector(this, "description");
    }

    @Override
    public EmbeddingVector summaryVector() {
        return JavAIRuntime.summaryVector(this, "assets", "description");
    }

    @Override
    public double similarityTo(JavAIVectorizable other) {
        return JavAIRuntime.similarityToVectorizable(this, "description", other);
    }

    @Override
    public double similarityTo(EmbeddingVector reference) {
        return JavAIRuntime.similarityToReference(this, "description", reference);
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
