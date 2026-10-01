package dev.xtrafe.javai.persistence;

import dev.xtrafe.javai.model.EmbeddingConsistencyMode;
import dev.xtrafe.javai.model.JavAIRuntime;
import dev.xtrafe.javai.vector.EmbeddingVector;
import dev.xtrafe.javai.vector.Ranked;
import dev.xtrafe.javai.vector.testsupport.RecordingEmbeddingProvider;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A container's per-model summary vector must reach the <b>table</b>, not merely be computable (OMI-458).
 *
 * <p>⚠️ <b>Every assertion here reads the row.</b> That is the ticket's own warning, and it is the whole
 * design of this file: {@code summaryVector(modelId)} has computed the right answer since OMI-290, so a
 * test asserting only that the value comes out right passes against the code as it stood before this
 * ticket and proves nothing. What did not exist was a writer, an invalidation trigger, and an index to rank
 * against -- so the assertions are on {@code javai_summary_vectors__fake_image_model_pp1}'s contents and on
 * what a search returns, never on an in-memory accessor.
 */
@Testcontainers
class PerModelSummaryPersistenceTest {

    /** {@link ModelIds#sanitize}'s rendering of {@link TestImageAsset#IMAGE_MODEL}. */
    private static final String IMAGE_SUMMARY_TABLE = "javai_summary_vectors__fake_image_model_pp1";

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    /** ⚠️ One config instance, shared by all four repositories. {@code JavAIPersistenceConfig} has no
     *  {@code equals}, and {@code JavAIPI} keys its backend cache on the config object -- so building a
     *  fresh one per repository would give each its own backend, each with its own view of which entity
     *  types exist. Containment is exactly the thing that would then be wrong: the asset's backend would
     *  not know the album holds it. */
    private static JavAIPersistenceConfig config;

    private static TestImageAssetRepository assets;
    private static TestPhotoAlbumRepository albums;
    private static TestPhotoExhibitionRepository exhibitions;
    private static TestPhotoCollageRepository collages;

    @BeforeAll
    static void configureRepositories() {
        JavAIRuntime.configureEmbeddingProvider(new RecordingEmbeddingProvider());
        JavAIRuntime.configureConsistencyMode(EmbeddingConsistencyMode.IMMEDIATE_CONSISTENCY);
        config = JavAIPersistenceConfig.builder()
                .backend(JavAIPersistenceConfig.Backend.POSTGRES)
                .postgresUrl(postgres.getJdbcUrl())
                .postgresUsername(postgres.getUsername())
                .postgresPassword(postgres.getPassword())
                .build();
        assets = JavAIPI.repository(TestImageAssetRepository.class, config);
        albums = JavAIPI.repository(TestPhotoAlbumRepository.class, config);
        exhibitions = JavAIPI.repository(TestPhotoExhibitionRepository.class, config);
        collages = JavAIPI.repository(TestPhotoCollageRepository.class, config);
    }

    // ---- the writer ---------------------------------------------------------------------------------

    @Test
    void anOptedInContainerGetsARowInItsMembersExternalModelsTable() {
        TestPhotoAlbum album = albumOf("harbour at dawn", 0.10f, 0.20f);
        albums.save(album);

        StoredRow row = readSummaryRow(IMAGE_SUMMARY_TABLE, TestPhotoAlbum.class, album.getId());
        assertNotNull(row, "the album owes a summary row in the image model's own table, and it is the row "
                + "-- not the computed value, which has worked since OMI-290 -- that did not exist");
        assertEquals(TestImageAsset.IMAGE_MODEL, row.modelId());
        assertEquals(TestImageAsset.IMAGE_DIMS, row.dims());
        assertNotNull(row.vector());
    }

    @Test
    void theConcatenatedColumnsAreWrittenAbsentRatherThanCarriedAcrossFromTheTextModel() {
        TestPhotoAlbum album = albumOf("a titled album", 0.30f);
        albums.save(album);

        StoredRow row = readSummaryRow(IMAGE_SUMMARY_TABLE, TestPhotoAlbum.class, album.getId());
        assertNotNull(row);
        // Concatenated text is one real embedding of one assembled string, so it exists in the configured
        // provider's model and in no other. Carrying it here would file a text embedding under an image
        // model -- and the columns are assigned rather than skipped, so a previous configuration's value
        // could not survive either.
        assertNull(row.concatenatedText(), "concatenated text has no meaning in a pixel model");
        assertNull(row.concatenatedVector());
    }

    @Test
    void theAmbientSummaryRowIsUntouchedByAnyOfThis() {
        TestPhotoAlbum album = albumOf("still an ordinary container", 0.40f);
        albums.save(album);

        String ambientTable = "javai_summary_vectors__" + ModelIds.sanitize(JavAIRuntime.currentModelId());
        StoredRow ambient = readSummaryRow(ambientTable, TestPhotoAlbum.class, album.getId());
        assertNotNull(ambient, "the text-model summary is written by the path it always was");
        assertEquals(JavAIRuntime.currentModelId(), ambient.modelId());
        // The album's own @Vectorize title and its members' captions are in this model, so this row is a
        // genuinely different vector from the image one -- two coherent summaries on one container.
        assertNotNull(ambient.vector());
    }

    @Test
    void aContainerThatDidNotOptInGetsNoRowAtAll() {
        TestPhotoCollage collage = collageOf("not opted in", 0.50f);
        collages.save(collage);

        assertNull(readSummaryRow(IMAGE_SUMMARY_TABLE, TestPhotoCollage.class, collage.getId()),
                "the flag is the whole opt-in: without it nothing extra is written");
    }

    // ---- summaries of summaries ---------------------------------------------------------------------

    @Test
    void aContainerOfContainersGetsARowTwoHopsAboveTheSuppliedVector() {
        TestPhotoAlbum album = albumOf("inner album", 0.60f, 0.65f);
        TestPhotoExhibition exhibition = new TestPhotoExhibition("outer exhibition");
        exhibition.getAlbums().add(album);
        exhibitions.save(exhibition);

        StoredRow albumRow = readSummaryRow(IMAGE_SUMMARY_TABLE, TestPhotoAlbum.class, album.getId());
        StoredRow exhibitionRow =
                readSummaryRow(IMAGE_SUMMARY_TABLE, TestPhotoExhibition.class, exhibition.getId());

        assertNotNull(albumRow, "the middle tier still gets its own row");
        assertNotNull(exhibitionRow, "⚠️ the model is declared two hops down, on the leaf -- a discovery walk "
                + "that stopped at the first @Summary hop would leave this null and nothing else would fail");
        assertEquals(TestImageAsset.IMAGE_DIMS, exhibitionRow.dims());
    }

    // ---- invalidation: the vector arrives after the save, always ------------------------------------

    @Test
    void supplyingAMembersVectorAfterTheSaveWritesTheContainersRow() {
        // ⚠️ The ordinary lifecycle, not an edge case: an @ExternalVector arrives from a queue seconds or
        // minutes after the entity was saved, so at save time there was nothing to summarise. If the row
        // only ever tracked save(), it would be correct exactly when it is empty.
        TestImageAsset asset = new TestImageAsset("awaiting the embedder", "sha256:late");
        TestPhotoAlbum album = new TestPhotoAlbum("album whose photo is still queued");
        album.getPhotos().add(asset);
        albums.save(album);
        assertNull(readSummaryRow(IMAGE_SUMMARY_TABLE, TestPhotoAlbum.class, album.getId()),
                "nothing is in the image model yet, so there is nothing to store");

        assertTrue(assets.supplyVector(asset.getId(), "pixels",
                TestImageAsset.imageVector(0.70f), "sha256:late"));
        JavAIPI.drainPendingSummaries(config);

        assertNotNull(readSummaryRow(IMAGE_SUMMARY_TABLE, TestPhotoAlbum.class, album.getId()),
                "supplyVector is where a container's per-model summary goes stale -- save() has already "
                        + "happened and will not happen again");
    }

    @Test
    void supplyingAMembersVectorReachesTwoTiersOfContainer() {
        TestImageAsset asset = new TestImageAsset("queued, two tiers down", "sha256:late-nested");
        TestPhotoAlbum album = new TestPhotoAlbum("inner");
        album.getPhotos().add(asset);
        TestPhotoExhibition exhibition = new TestPhotoExhibition("outer");
        exhibition.getAlbums().add(album);
        exhibitions.save(exhibition);

        assets.supplyVector(asset.getId(), "pixels", TestImageAsset.imageVector(0.75f), "sha256:late-nested");
        JavAIPI.drainPendingSummaries(config);

        assertNotNull(readSummaryRow(IMAGE_SUMMARY_TABLE, TestPhotoAlbum.class, album.getId()));
        assertNotNull(readSummaryRow(IMAGE_SUMMARY_TABLE, TestPhotoExhibition.class, exhibition.getId()),
                "the drain walks upward one hop at a time, so the tier above the container must be reached too");
    }

    @Test
    void theRowGoesWhenTheLastMemberCarryingThatModelDoes() {
        TestPhotoAlbum album = albumOf("emptied later", 0.80f);
        albums.save(album);
        assertNotNull(readSummaryRow(IMAGE_SUMMARY_TABLE, TestPhotoAlbum.class, album.getId()));

        // Inside a transaction, so the lazy @Summary collection can be initialized before it is emptied --
        // a detached container cannot be edited, which is ordinary JPA and nothing to do with this ticket.
        JavAIPI.inTransaction(config, () -> {
            TestPhotoAlbum loaded = albums.findById(album.getId()).orElseThrow();
            loaded.getPhotos().clear();
            albums.save(loaded);
        });
        JavAIPI.drainPendingSummaries(config);

        assertNull(readSummaryRow(IMAGE_SUMMARY_TABLE, TestPhotoAlbum.class, album.getId()),
                "a stale row in an ANN index goes on matching searches forever -- the same rule an absent "
                        + "@Vectorize field already follows");
    }

    // ---- the query: what the reference decides, and what inModel(...) adds -------------------------

    @Test
    void theReferenceAloneAlreadySelectsTheStorage() {
        // ⚠️ **The pin under the whole design of inModel(...).** Every backend resolves which table answers
        // from reference.modelId(), so a summary search in a model that only ever arrives through
        // @ExternalVector works with no model named anywhere. If this ever stops being true, inModel() has
        // silently become a selector, and the "it is only an assertion" reasoning in its javadoc is void.
        TestPhotoAlbum wanted = albumOf("found with nothing stated", 0.90f);
        TestPhotoAlbum other = albumOf("something else entirely", 0.15f);
        albums.save(wanted);
        albums.save(other);

        List<Ranked<TestPhotoAlbum>> hits = albums.nearestBySummary()
                .to(wanted.summaryVector(TestImageAsset.IMAGE_MODEL)).limit(1).ranked();

        assertEquals(1, hits.size());
        assertEquals(wanted.getId(), hits.get(0).entity().getId());
    }

    @Test
    void assertingTheModelChangesNothingAboutTheAnswer() {
        TestPhotoAlbum wanted = albumOf("stated and unstated agree", 0.91f);
        albums.save(wanted);
        EmbeddingVector reference = wanted.summaryVector(TestImageAsset.IMAGE_MODEL);

        List<Ranked<TestPhotoAlbum>> stated = albums.nearestBySummary()
                .inModel(TestImageAsset.IMAGE_MODEL).to(reference).limit(1).ranked();
        List<Ranked<TestPhotoAlbum>> unstated = albums.nearestBySummary()
                .to(reference).limit(1).ranked();

        assertEquals(unstated.get(0).entity().getId(), stated.get(0).entity().getId());
        assertEquals(unstated.get(0).similarity(), stated.get(0).similarity(), 0.0,
                "inModel() buys legibility and a check, never a different search");
    }

    @Test
    void aTypeThatDidNotOptInAnswersTheSameQuestionByFolding() {
        // ⚠️ The failure this pins is not slowness. Without the fold, ensureSummaryVectorTable provisions an
        // empty table for this type and the search returns no hits -- which reads as "nothing is similar"
        // rather than "nothing is stored", and a caller has no way to tell those apart.
        TestPhotoCollage wanted = collageOf("the collage being looked for", 0.95f);
        TestPhotoCollage other = collageOf("an unrelated collage", 0.05f);
        collages.save(wanted);
        collages.save(other);

        EmbeddingVector reference = wanted.summaryVector(TestImageAsset.IMAGE_MODEL);
        List<Ranked<TestPhotoCollage>> hits = collages.nearestBySummary()
                .inModel(TestImageAsset.IMAGE_MODEL).to(reference).limit(1).ranked();

        assertEquals(1, hits.size(), "an unindexed model-scoped summary search must still answer");
        assertEquals(wanted.getId(), hits.get(0).entity().getId());
        assertNull(readSummaryRow(IMAGE_SUMMARY_TABLE, TestPhotoCollage.class, wanted.getId()),
                "and it must answer without having quietly started storing rows");
    }

    @Test
    void theIndexedAndFoldedPathsAgreeOnTheRanking() {
        // The same three assets in both container types, so any disagreement is the path and not the data.
        float[] seeds = { 0.11f, 0.22f, 0.33f };
        TestPhotoAlbum album = albumOf("agreement album", seeds);
        TestPhotoCollage collage = collageOf("agreement collage", seeds);
        albums.save(album);
        collages.save(collage);

        EmbeddingVector reference = TestImageAsset.imageVector(0.22f);
        double indexed = albums.nearestBySummary().inModel(TestImageAsset.IMAGE_MODEL)
                .to(reference).limit(1).ranked().get(0).similarity();
        double folded = collages.nearestBySummary().inModel(TestImageAsset.IMAGE_MODEL)
                .to(reference).limit(1).ranked().get(0).similarity();

        // pgvector's cosine distance and VectorMath's cosine similarity are the same quantity computed two
        // ways, so they agree to float round-tripping rather than exactly.
        assertEquals(indexed, folded, 1e-5,
                "the fallback is the same answer more slowly, not a different one");
    }

    @Test
    void theFoldHonoursNarrowingBeforeTheLimit() {
        // The ordering contract NearestSpec states, on the path that has no SQL to enforce it: resolve the
        // predicate first, then take the nearest N of what survives. Ranking then filtering would return
        // fewer than N whenever the predicate is selective, which is the over-fetch callers escape by asking.
        TestPhotoCollage nearest = collageOf("excluded by title", 0.60f);
        TestPhotoCollage admitted = collageOf("admitted", 0.61f);
        collages.save(nearest);
        collages.save(admitted);

        List<Ranked<TestPhotoCollage>> hits = collages.nearestBySummary()
                .inModel(TestImageAsset.IMAGE_MODEL)
                .to(TestImageAsset.imageVector(0.60f))
                .where("title").is("admitted")
                .limit(1).ranked();

        assertEquals(1, hits.size(), "one match exists and the limit is one, so one comes back");
        assertEquals(admitted.getId(), hits.get(0).entity().getId(),
                "the nearer collage is excluded by the predicate, not ranked ahead of it and then dropped");
    }

    @Test
    void theFoldReturnsNothingWhenThePredicateAdmitsNothing() {
        collages.save(collageOf("some collage", 0.62f));

        assertTrue(collages.nearestBySummary().inModel(TestImageAsset.IMAGE_MODEL)
                .to(TestImageAsset.imageVector(0.62f))
                .where("title").is("no collage has this title")
                .limit(5).ranked().isEmpty());
    }

    @Test
    void theFoldPagesWithOffsetExactly() {
        // Ranking is total, so skipping the head of it is well-defined -- the same claim the indexed path
        // makes, asserted here against the same three-candidate corpus it is easy to get off by one on.
        TestPhotoCollage a = collageOf("page a", 0.70f);
        TestPhotoCollage b = collageOf("page b", 0.71f);
        TestPhotoCollage c = collageOf("page c", 0.72f);
        collages.save(a);
        collages.save(b);
        collages.save(c);
        EmbeddingVector reference = TestImageAsset.imageVector(0.70f);

        List<UUID> firstTwo = idsOf(collages.nearestBySummary().inModel(TestImageAsset.IMAGE_MODEL)
                .to(reference).limit(2).ranked());
        List<UUID> secondOnward = idsOf(collages.nearestBySummary().inModel(TestImageAsset.IMAGE_MODEL)
                .to(reference).offset(1).limit(2).ranked());

        assertEquals(2, firstTwo.size());
        assertEquals(firstTwo.get(1), secondOnward.get(0), "offset 1 must start where the first page's second hit was");
    }

    @Test
    void theFoldSkipsACandidateWithNothingInThatModel() {
        // An empty collage's summary in the image model is absent, and an absent vector has no direction --
        // it must not occupy a slot in someone's top N. The indexed path expresses this as
        // `vector IS NOT NULL`; the fold has to mean the same thing.
        TestPhotoCollage empty = new TestPhotoCollage("no photographs at all");
        TestPhotoCollage real = collageOf("has photographs", 0.63f);
        collages.save(empty);
        collages.save(real);

        List<UUID> hits = idsOf(collages.nearestBySummary().inModel(TestImageAsset.IMAGE_MODEL)
                .to(TestImageAsset.imageVector(0.63f)).limit(50).ranked());

        assertTrue(hits.contains(real.getId()));
        assertFalse(hits.contains(empty.getId()), "a fold over nothing is not a weak match, it is not a match");
    }

    // ---- what inModel(...) refuses ------------------------------------------------------------------

    @Test
    void aReferenceFromAnotherModelIsRefusedRatherThanRankedAgainstTheWrongSummary() {
        // ⚠️ Note what this is and is not. It is NOT protection against ranking across two embedding spaces
        // -- that cannot happen, since the table is derived from the reference. It is protection against
        // asking for one of the container's two summaries and passing the other, which otherwise answers the
        // wrong question with nothing to notice.
        TestPhotoAlbum album = albumOf("two coherent summaries", 0.44f);
        albums.save(album);
        EmbeddingVector textReference = album.summaryVector();

        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> albums.nearestBySummary().inModel(TestImageAsset.IMAGE_MODEL)
                        .to(textReference).limit(5).ranked());

        assertTrue(refused.getMessage().contains(TestImageAsset.IMAGE_MODEL));
        assertTrue(refused.getMessage().contains(JavAIRuntime.currentModelId()),
                "the message must name both models -- which one is wrong is the caller's to decide");
    }

    @Test
    void theRefusalDoesNotDependOnCallOrder() {
        // A builder's setters are order-free, so the check belongs at build time rather than on whichever
        // of the two calls happens to come second.
        TestPhotoAlbum album = albumOf("order free", 0.45f);
        albums.save(album);
        EmbeddingVector textReference = album.summaryVector();

        assertThrows(IllegalArgumentException.class, () -> albums.nearestBySummary()
                .to(textReference).inModel(TestImageAsset.IMAGE_MODEL).limit(5).ranked());
    }

    @Test
    void assertingTheModelWorksWhenStatedBeforeOrAfterTheReference() {
        TestPhotoAlbum album = albumOf("either order", 0.46f);
        albums.save(album);
        EmbeddingVector reference = album.summaryVector(TestImageAsset.IMAGE_MODEL);

        assertEquals(album.getId(), albums.nearestBySummary().inModel(TestImageAsset.IMAGE_MODEL)
                .to(reference).limit(1).ranked().get(0).entity().getId());
        assertEquals(album.getId(), albums.nearestBySummary().to(reference)
                .inModel(TestImageAsset.IMAGE_MODEL).limit(1).ranked().get(0).entity().getId());
    }

    @Test
    void assertingTheConfiguredModelIsLegalAndStillIndexed() {
        // Nothing about inModel() is reserved for external models -- naming the ambient one is an ordinary,
        // true assertion, and must not divert the search onto the fold.
        TestPhotoAlbum album = albumOf("asserting the ambient model", 0.47f);
        albums.save(album);

        List<Ranked<TestPhotoAlbum>> hits = albums.nearestBySummary()
                .inModel(JavAIRuntime.currentModelId()).to(album.summaryVector()).limit(1).ranked();

        assertEquals(album.getId(), hits.get(0).entity().getId());
    }

    @Test
    void aBlankModelIdIsRefusedOnTheCallThatPassedIt() {
        assertThrows(IllegalArgumentException.class, () -> albums.nearestBySummary().inModel("  "));
        assertThrows(IllegalArgumentException.class, () -> albums.nearestBySummary().inModel(null));
    }

    @Test
    void inModelIsRefusedOnEveryGrainWhereItWouldMeanNothing() {
        // Refused rather than ignored: a qualifier that is accepted and does nothing invites the belief that
        // it does something, which is the more expensive of the two mistakes.
        assertThrows(IllegalArgumentException.class,
                () -> albums.nearestBy("title").inModel(TestImageAsset.IMAGE_MODEL),
                "a @Vectorize field is in the configured provider's model; the name already fixes it");
        assertThrows(IllegalArgumentException.class,
                () -> assets.nearestBy("pixels").inModel(TestImageAsset.IMAGE_MODEL),
                "an @ExternalVector's model is fixed by its own declaration -- there is nothing to choose");
        assertThrows(IllegalArgumentException.class,
                () -> albums.nearest().inModel(TestImageAsset.IMAGE_MODEL));
    }

    @Test
    void theRefusalSaysWhichGrainItWasCalledOn() {
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> assets.nearestBy("pixels").inModel(TestImageAsset.IMAGE_MODEL));
        assertTrue(refused.getMessage().contains("pixels"),
                "naming the grain is what makes the message a fix rather than a puzzle: " + refused.getMessage());
    }

    @Test
    void theDerivedFinderNameNeedsNoModelEither() {
        // The findNearestBy...Vector convention has no room for a model id -- model ids are not Java
        // identifiers -- and needs none, for exactly the reason inModel() is optional.
        TestPhotoAlbum album = albumOf("found by the method-name idiom", 0.48f);
        albums.save(album);

        List<TestPhotoAlbum> hits = albums.findNearestBySummaryVector(
                album.summaryVector(TestImageAsset.IMAGE_MODEL), 1);

        assertEquals(1, hits.size());
        assertEquals(album.getId(), hits.get(0).getId());
    }

    @Test
    void theNoArgumentFormStillMeansWhicheverModelTheReferenceCameFrom() {
        TestPhotoAlbum album = albumOf("found by its words", 0.55f);
        albums.save(album);

        List<Ranked<TestPhotoAlbum>> hits = albums.nearestBySummary()
                .to(album.summaryVector()).limit(1).ranked();

        assertEquals(1, hits.size());
        assertEquals(album.getId(), hits.get(0).entity().getId());
    }

    // ---- fixtures and row reading -------------------------------------------------------------------

    private static TestPhotoAlbum albumOf(String title, float... seeds) {
        TestPhotoAlbum album = new TestPhotoAlbum(title);
        for (float seed : seeds) {
            album.getPhotos().add(embeddedAsset(title, seed));
        }
        return album;
    }

    private static TestPhotoCollage collageOf(String title, float... seeds) {
        TestPhotoCollage collage = new TestPhotoCollage(title);
        for (float seed : seeds) {
            collage.getPhotos().add(embeddedAsset(title, seed));
        }
        return collage;
    }

    /** An asset with both vectors an adopter's really has: a {@code @Vectorize} caption the configured
     *  provider embeds, and a supplied image vector it never could. */
    private static TestImageAsset embeddedAsset(String context, float seed) {
        String contentHash = "sha256:" + context.hashCode() + "-" + seed;
        TestImageAsset asset = new TestImageAsset("a photo in " + context, contentHash);
        JavAIRuntime.supplyVector(asset, "pixels", TestImageAsset.imageVector(seed), contentHash);
        return asset;
    }

    private static List<UUID> idsOf(List<? extends Ranked<?>> hits) {
        List<UUID> ids = new ArrayList<>(hits.size());
        for (Ranked<?> hit : hits) {
            ids.add(EntityReflection.readId(hit.entity()));
        }
        return ids;
    }

    private record StoredRow(String modelId, int dims, String vector, String concatenatedText,
            String concatenatedVector) {
    }

    /** Read with plain JDBC on a connection of its own, deliberately: the claim is about what is in the
     *  database, and reading it back through the same repository that wrote it could not distinguish a
     *  stored row from a cached value. */
    private static StoredRow readSummaryRow(String table, Class<?> ownerType, UUID ownerId) {
        try (Connection connection = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
            try (ResultSet tables = connection.getMetaData().getTables(null, null, table, null)) {
                if (!tables.next()) {
                    return null; // never provisioned, which is itself an answer some tests assert
                }
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT model_id, dims, vector::text, concatenated_text, concatenated_text_vector::text"
                            + " FROM " + table + " WHERE owner_type = ? AND owner_id = ?")) {
                statement.setString(1, ownerType.getName());
                statement.setObject(2, ownerId);
                try (ResultSet rows = statement.executeQuery()) {
                    if (!rows.next()) {
                        return null;
                    }
                    return new StoredRow(rows.getString(1), rows.getInt(2), rows.getString(3),
                            rows.getString(4), rows.getString(5));
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("Could not read " + table, e);
        }
    }
}
