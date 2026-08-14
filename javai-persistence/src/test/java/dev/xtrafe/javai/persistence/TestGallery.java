package dev.xtrafe.javai.persistence;

import dev.xtrafe.javai.annotations.Summary;
import dev.xtrafe.javai.annotations.Vectorize;
import dev.xtrafe.javai.model.JavAIArrayList;
import dev.xtrafe.javai.model.JavAILinkedHashMap;
import dev.xtrafe.javai.model.JavAILinkedHashSet;
import dev.xtrafe.javai.model.JavAIList;
import dev.xtrafe.javai.model.JavAIMap;
import dev.xtrafe.javai.model.JavAIRuntime;
import dev.xtrafe.javai.model.JavAISet;
import dev.xtrafe.javai.model.JavAIVectorizable;
import dev.xtrafe.javai.vector.DirtyTrackingSupport;
import dev.xtrafe.javai.vector.EmbeddingVector;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.MapKeyColumn;

import java.util.UUID;

/**
 * A container of {@link TestImageAsset}s in all three JavAI collection shapes -- OMI-303's fixture, and
 * deliberately the adopter's own shape: {@code @Summary} collections declared by the JavAI interface,
 * mapped {@code @ManyToMany(fetch = LAZY)}, holding members whose only interesting vector is an
 * {@code @ExternalVector} in a model no text field here was ever embedded under.
 *
 * <p>Its point is what happens <em>after</em> a save. Hibernate substitutes a {@code PersistentJavAI*} into
 * each field on load, and those three types did not override the model-scoped accessors -- so
 * {@code gallery.summaryVector(IMAGE_MODEL)} answered {@code absent()} once reloaded while the identical
 * in-memory gallery answered a real vector. All three collection types are here because all three had the
 * same gap; the map especially, since no adopter had reached it yet.
 *
 * <p>Hand-implements {@link JavAIVectorizable} for the reason every fixture in this module does: no weaver
 * here (see {@link TestArticle}).
 */
@Entity
final class TestGallery implements JavAIVectorizable {

    static final String SUMMARY_FIELDS = "ordered,unique,byCode";

    @SuppressWarnings("unused") // reflectively accessed via JavAIRuntime.STATE_FIELD
    private transient DirtyTrackingSupport $javai$state;

    @Id
    private UUID id;

    @Vectorize
    private String title;

    // Explicit join-table names: all three associations target TestImageAsset, so Hibernate's derived
    // owner+element default would have all three claiming one table (the TestCatalogue precedent).
    @ManyToMany(cascade = { CascadeType.PERSIST, CascadeType.MERGE }, fetch = FetchType.LAZY)
    @JoinTable(name = "test_gallery_ordered")
    @Summary
    private JavAIList<TestImageAsset> ordered = new JavAIArrayList<>();

    @ManyToMany(cascade = { CascadeType.PERSIST, CascadeType.MERGE }, fetch = FetchType.LAZY)
    @JoinTable(name = "test_gallery_unique")
    @Summary
    private JavAISet<TestImageAsset> unique = new JavAILinkedHashSet<>();

    @ManyToMany(cascade = { CascadeType.PERSIST, CascadeType.MERGE }, fetch = FetchType.LAZY)
    @JoinTable(name = "test_gallery_by_code")
    @MapKeyColumn(name = "slot_code")
    @Summary
    private JavAIMap<String, TestImageAsset> byCode = new JavAILinkedHashMap<>();

    TestGallery() {
    }

    TestGallery(String title) {
        this.id = UUID.randomUUID();
        this.title = title;
    }

    UUID getId() {
        return id;
    }

    String getTitle() {
        return title;
    }

    JavAIList<TestImageAsset> getOrdered() {
        return ordered;
    }

    JavAISet<TestImageAsset> getUnique() {
        return unique;
    }

    JavAIMap<String, TestImageAsset> getByCode() {
        return byCode;
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
