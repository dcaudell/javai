package dev.xtrafe.javai.persistence;

import dev.xtrafe.javai.model.EmbeddingConsistencyMode;
import dev.xtrafe.javai.model.JavAIRuntime;
import dev.xtrafe.javai.model.JavAIVectorizable;
import dev.xtrafe.javai.vector.EmbeddingVector;
import dev.xtrafe.javai.vector.VectorMath;
import dev.xtrafe.javai.vector.testsupport.FakeEmbeddingProvider;
import org.hibernate.Hibernate;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A model-scoped {@code vector(modelId)}/{@code summaryVector(modelId)} must keep working after the owning
 * entity has been persisted and reloaded (OMI-303).
 *
 * <p>The reload is the whole test. The in-memory collections override both scoped accessors; the
 * Hibernate-backed {@code PersistentJavAI*} substitutes did not, so they inherited
 * {@code JavAIVectorizable}'s {@code default}, which is {@code absent()}. Nothing threw and nothing warned
 * -- {@code JavAIRuntime.summaryVector} folds any {@code JavAIVectorizable} child unconditionally, so the
 * container simply normalized an absent term into an absent result. An adopter could summarize a gallery in
 * its images' own model right up until it saved the gallery, and never learn why the answer went quiet.
 *
 * <p>Text summaries are unaffected by that gap, which is what made it so easy to miss, so every test here
 * asserts the unqualified vector stays healthy alongside the scoped one.
 */
@Testcontainers
class ScopedCollectionVectorPersistenceTest {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    private static TestGalleryRepository galleries;
    private static JavAIPersistenceConfig config;

    @BeforeAll
    static void configureRepository() {
        JavAIRuntime.configureEmbeddingProvider(new FakeEmbeddingProvider());
        JavAIRuntime.configureConsistencyMode(EmbeddingConsistencyMode.IMMEDIATE_CONSISTENCY);
        config = JavAIPersistenceConfig.builder()
                .backend(JavAIPersistenceConfig.Backend.POSTGRES)
                .postgresUrl(postgres.getJdbcUrl())
                .postgresUsername(postgres.getUsername())
                .postgresPassword(postgres.getPassword())
                .build();
        galleries = JavAIPI.repository(TestGalleryRepository.class, config);
    }

    /** A gallery with one image in each of the three collection shapes, its pixel vectors supplied the way
     *  an out-of-process model would (OMI-290's {@code supplyVector}). */
    private static TestGallery galleryWithImagesInEveryCollection(String title, float seed) {
        TestGallery gallery = new TestGallery(title);
        gallery.getOrdered().add(imageWith(title + "-ordered", seed));
        gallery.getUnique().add(imageWith(title + "-unique", seed + 1));
        gallery.getByCode().put("slot-a", imageWith(title + "-mapped", seed + 2));
        return gallery;
    }

    private static TestImageAsset imageWith(String caption, float seed) {
        TestImageAsset image = new TestImageAsset(caption, "sha256:" + caption);
        assertTrue(JavAIRuntime.supplyVector(image, "pixels", TestImageAsset.imageVector(seed), "sha256:" + caption));
        return image;
    }

    private static TestGallery reload(UUID id) {
        return JavAIPI.inTransaction(config, () -> {
            TestGallery loaded = galleries.findById(id).orElseThrow();
            Hibernate.initialize(loaded.getOrdered());
            Hibernate.initialize(loaded.getUnique());
            Hibernate.initialize(loaded.getByCode());
            return loaded;
        });
    }

    @Test
    void aReloadedGallerySummarizesItsImagesInTheirOwnModel() {
        TestGallery gallery = galleryWithImagesInEveryCollection("reloaded", 0.25f);
        galleries.save(gallery);

        TestGallery reloaded = reload(gallery.getId());
        EmbeddingVector scoped = reloaded.summaryVector(TestImageAsset.IMAGE_MODEL);

        assertFalse(scoped.isAbsent(), "a reloaded container must still summarize in its members' own model");
        assertEquals(TestImageAsset.IMAGE_DIMS, scoped.dims());
        assertEquals(TestImageAsset.IMAGE_MODEL, scoped.modelId());
        // The text summary was never broken; asserting it here is what pins that the fix did not trade one
        // path for the other.
        assertFalse(reloaded.summaryVector().isAbsent(), "the unqualified summary must stay healthy");
        assertEquals(FakeEmbeddingProvider.DIMS, reloaded.summaryVector().dims());
    }

    /** Each collection type separately, since each is its own class with its own copy of the gap -- and the
     *  map especially, which no adopter had reached. */
    @Test
    void everyPersistentCollectionTypeServesTheScopedPairAfterReload() {
        TestGallery gallery = galleryWithImagesInEveryCollection("per-type", 0.5f);
        galleries.save(gallery);
        TestGallery reloaded = reload(gallery.getId());

        record Case(String name, JavAIVectorizable collection) {
        }
        for (Case testCase : new Case[] {
                new Case("PersistentJavAIList", (JavAIVectorizable) reloaded.getOrdered()),
                new Case("PersistentJavAISet", (JavAIVectorizable) reloaded.getUnique()),
                new Case("PersistentJavAIMap", (JavAIVectorizable) reloaded.getByCode()) }) {
            EmbeddingVector centroid = testCase.collection().vector(TestImageAsset.IMAGE_MODEL);
            EmbeddingVector summary = testCase.collection().summaryVector(TestImageAsset.IMAGE_MODEL);
            assertFalse(centroid.isAbsent(), testCase.name() + " must serve a scoped centroid");
            assertFalse(summary.isAbsent(), testCase.name() + " must serve a scoped summary");
            assertEquals(TestImageAsset.IMAGE_DIMS, centroid.dims(), testCase.name());
            assertEquals(TestImageAsset.IMAGE_DIMS, summary.dims(), testCase.name());
        }
    }

    /**
     * The two paths must agree, not merely both be non-absent -- otherwise they are free to drift apart
     * again with nothing noticing. Same members, same supplied pixel vectors, so the in-memory gallery and
     * the reloaded one must produce the identical scoped aggregate.
     */
    @Test
    void inMemoryAndReloadedScopedAggregatesAgreeForTheSameMembers() {
        TestGallery gallery = galleryWithImagesInEveryCollection("parity", 0.75f);
        EmbeddingVector beforeSave = gallery.summaryVector(TestImageAsset.IMAGE_MODEL);
        assertFalse(beforeSave.isAbsent(), "the in-memory path was never broken -- guard against a false pass");

        galleries.save(gallery);
        EmbeddingVector afterReload = reload(gallery.getId()).summaryVector(TestImageAsset.IMAGE_MODEL);

        assertArrayEquals(beforeSave.values(), afterReload.values(),
                "persisting a container must not change what its scoped summary says");
        assertEquals(1.0, VectorMath.cosineSimilarity(beforeSave, afterReload), 1e-6);
    }

    /**
     * The normal shape for a {@code @ManyToMany}: the collection is still uninitialized when the scoped
     * accessor is called, so the call itself has to trigger the load. Deliberately not pre-initialized the
     * way {@link #reload} does -- an assertion that only holds for an eagerly-fetched collection would miss
     * the case every real adopter actually has.
     */
    @Test
    void aLazyUninitializedCollectionStillServesTheScopedSummary() {
        TestGallery gallery = galleryWithImagesInEveryCollection("lazy", 1.25f);
        galleries.save(gallery);

        JavAIPI.inTransaction(config, () -> {
            TestGallery loaded = galleries.findById(gallery.getId()).orElseThrow();
            assertFalse(Hibernate.isInitialized(loaded.getUnique()),
                    "this test is only meaningful while the collection is still a lazy proxy");

            EmbeddingVector scoped = loaded.summaryVector(TestImageAsset.IMAGE_MODEL);
            assertFalse(scoped.isAbsent(), "a lazy collection must load on demand, not answer absent");
            assertEquals(TestImageAsset.IMAGE_DIMS, scoped.dims());
            return null;
        });
    }
}
