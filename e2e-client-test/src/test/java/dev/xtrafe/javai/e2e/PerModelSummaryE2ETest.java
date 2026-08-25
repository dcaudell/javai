package dev.xtrafe.javai.e2e;

import dev.xtrafe.javai.e2e.domain.PhotoAlbum;
import dev.xtrafe.javai.e2e.domain.PhotoAsset;
import dev.xtrafe.javai.e2e.domain.PhotoCollage;
import dev.xtrafe.javai.e2e.domain.PhotoExhibition;
import dev.xtrafe.javai.e2e.environment.JavAIEnvironment;
import dev.xtrafe.javai.e2e.environment.MonolithicContainer;
import dev.xtrafe.javai.model.JavAIVectorizable;
import dev.xtrafe.javai.persistence.JavAIPI;
import dev.xtrafe.javai.persistence.Ranked;
import dev.xtrafe.javai.vector.EmbeddingVector;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Persisted per-model summary vectors, end to end and from outside the library (OMI-458): woven client
 * entities, a real Postgres, real embeddings for the text half, and a supplied vector for the half nothing
 * in this process can compute.
 *
 * <p><b>What this covers that the unit tests cannot.</b> The persistence module's own fixtures hand-write
 * {@code JavAIVectorizable}, because that module has no weaver. Here {@code summaryVector(String)},
 * {@code pixelsVector()} and the model-scoped collection fold are all the <em>weaver's</em>, reached
 * through Hibernate-owned lazy associations on entities a client wrote with nothing but annotations. If
 * the woven model-scoped accessor and the writer disagreed about anything, this is where it would show.
 *
 * <p>⚠️ <b>The assertions are on the row and on what a search returns</b>, never on an in-memory accessor.
 * {@code summaryVector(modelId)} has computed the right value since OMI-290 -- a test asserting only that
 * would have passed before this ticket existed.
 */
class PerModelSummaryE2ETest {

    /** {@code ModelIds.sanitize(PhotoAsset.PIXEL_MODEL)}. */
    private static final String PIXEL_SUMMARY_TABLE = "javai_summary_vectors__e2e_fake_pixels_pp1";

    @BeforeAll
    static void start() {
        JavAIEnvironment.ensureRunning();
    }

    // ---- the writer ---------------------------------------------------------------------------------

    @Test
    void anOptedInAlbumGetsARowInItsPhotosPixelModel() {
        PhotoAlbum album = albumOf("harbour at dawn", 0.10f, 0.20f);
        JavAIEnvironment.postgresPhotoAlbumRepository().save(album);

        StoredRow row = summaryRow(PhotoAlbum.class, album.getId());
        assertNotNull(row, "the album owes a row in the pixel model's own table -- the value has been "
                + "computable since OMI-290; nothing wrote it down");
        assertEquals(PhotoAsset.PIXEL_MODEL, row.modelId());
        assertEquals(PhotoAsset.PIXEL_DIMS, row.dims());
        assertNotNull(row.vector());
        // Concatenated text is one embedding of one assembled string, so it lives in the configured
        // provider's model and in no other: writing it here would file text under an image model.
        assertNull(row.concatenatedText());
    }

    @Test
    void theTextSummaryIsStillWrittenAndIsADifferentVector() {
        // ⚠️ The point of the leaf carrying both kinds of vector. An album has two coherent summaries, in
        // models of different dimensionality that VectorMath refuses to combine, and adding one must not
        // have disturbed the other.
        PhotoAlbum album = albumOf("two coherent summaries", 0.30f);
        JavAIEnvironment.postgresPhotoAlbumRepository().save(album);

        EmbeddingVector text = ((JavAIVectorizable) album).summaryVector();
        EmbeddingVector pixels = ((JavAIVectorizable) album).summaryVector(PhotoAsset.PIXEL_MODEL);

        assertFalse(text.isAbsent(), "the caption and title are real text and were really embedded");
        assertFalse(pixels.isAbsent());
        assertEquals(PhotoAsset.PIXEL_DIMS, pixels.dims());
        assertTrue(text.dims() != pixels.dims(), "two models, two dimensionalities -- not one summary");

        List<Ranked<PhotoAlbum>> byText = JavAIEnvironment.postgresPhotoAlbumRepository()
                .nearestBySummary().to(text).limit(1).ranked();
        assertEquals(album.getId(), byText.get(0).entity().getId(),
                "the ordinary text-model summary search is untouched by any of this");
    }

    // ---- summaries of summaries ---------------------------------------------------------------------

    @Test
    void anExhibitionTwoTiersAboveTheSuppliedVectorGetsARowToo() {
        PhotoAlbum album = albumOf("inner album", 0.60f, 0.65f);
        PhotoExhibition exhibition = new PhotoExhibition("outer exhibition");
        exhibition.getAlbums().add(album);
        JavAIEnvironment.postgresPhotoExhibitionRepository().save(exhibition);

        assertNotNull(summaryRow(PhotoAlbum.class, album.getId()), "the middle tier gets its own row");
        assertNotNull(summaryRow(PhotoExhibition.class, exhibition.getId()),
                "⚠️ the pixel model is declared two hops down, on the leaf, and neither the exhibition nor "
                        + "the album contributes anything of its own to it -- a discovery walk that stopped "
                        + "at the first @Summary hop would leave this null and nothing else would fail");

        List<Ranked<PhotoExhibition>> hits = JavAIEnvironment.postgresPhotoExhibitionRepository()
                .nearestBySummary()
                .inModel(PhotoAsset.PIXEL_MODEL)
                .to(((JavAIVectorizable) exhibition).summaryVector(PhotoAsset.PIXEL_MODEL))
                .limit(1).ranked();
        assertEquals(exhibition.getId(), hits.get(0).entity().getId(),
                "and the row has to be rankable, not merely present");
    }

    // ---- invalidation: the vector always arrives after the save --------------------------------------

    @Test
    void supplyingAPhotosVectorAfterTheSaveUpdatesEveryContainerAboveIt() {
        // The real lifecycle of an @ExternalVector: the model runs elsewhere and answers later, so at save
        // time there was nothing in the pixel model to summarise at any tier.
        PhotoAsset asset = new PhotoAsset("still with the embedder", "sha256:e2e-late");
        PhotoAlbum album = new PhotoAlbum("album whose photo is queued");
        album.getPhotos().add(asset);
        PhotoExhibition exhibition = new PhotoExhibition("exhibition whose photo is queued");
        exhibition.getAlbums().add(album);
        JavAIEnvironment.postgresPhotoExhibitionRepository().save(exhibition);

        assertNull(summaryRow(PhotoAlbum.class, album.getId()), "nothing is in the pixel model yet");
        assertNull(summaryRow(PhotoExhibition.class, exhibition.getId()));

        assertTrue(JavAIEnvironment.postgresPhotoAssetRepository().supplyVector(
                asset.getId(), "pixels", PhotoAsset.pixelVector(0.70f), "sha256:e2e-late"));
        JavAIPI.drainPendingSummaries(JavAIEnvironment.postgresConfig());

        assertNotNull(summaryRow(PhotoAlbum.class, album.getId()),
                "supplyVector is the moment a container's per-model summary goes stale -- save() has "
                        + "already happened and is not going to happen again");
        assertNotNull(summaryRow(PhotoExhibition.class, exhibition.getId()),
                "and the drain walks upward one hop at a time, so the tier above the container is reached");
    }

    @Test
    void aVectorSuppliedForContentTheAssetHasMovedOnFromChangesNothing() {
        PhotoAsset asset = new PhotoAsset("re-uploaded meanwhile", "sha256:e2e-first");
        PhotoAlbum album = new PhotoAlbum("album with a re-uploaded photo");
        album.getPhotos().add(asset);
        JavAIEnvironment.postgresPhotoAlbumRepository().save(album);

        assertFalse(JavAIEnvironment.postgresPhotoAssetRepository().supplyVector(
                        asset.getId(), "pixels", PhotoAsset.pixelVector(0.5f), "sha256:e2e-stale"),
                "a slow producer racing an edit is an ordinary outcome, not an error");
        JavAIPI.drainPendingSummaries(JavAIEnvironment.postgresConfig());

        assertNull(summaryRow(PhotoAlbum.class, album.getId()),
                "a refused vector must not leave a summary behind describing content nothing references");
    }

    // ---- the query, indexed and folded --------------------------------------------------------------

    @Test
    void theReferenceAloneRanksAlbumsByWhatTheirPicturesLookLike() {
        // ⚠️ No model named anywhere. The backend resolves which table answers from reference.modelId(), so
        // a summary search in a model that only ever arrives through @ExternalVector works with nothing
        // extra -- which is why inModel(...) is an assertion rather than a selector.
        PhotoAlbum wanted = albumOf("the album being looked for", 0.90f);
        PhotoAlbum other = albumOf("an unrelated album", 0.15f);
        JavAIEnvironment.postgresPhotoAlbumRepository().save(wanted);
        JavAIEnvironment.postgresPhotoAlbumRepository().save(other);

        List<Ranked<PhotoAlbum>> hits = JavAIEnvironment.postgresPhotoAlbumRepository()
                .nearestBySummary().to(PhotoAsset.pixelVector(0.90f)).limit(1).ranked();

        assertEquals(wanted.getId(), hits.get(0).entity().getId());
    }

    @Test
    void assertingTheModelFindsTheSameAlbumWithTheSameScore() {
        PhotoAlbum album = albumOf("stated and unstated agree", 0.91f);
        JavAIEnvironment.postgresPhotoAlbumRepository().save(album);
        EmbeddingVector reference = PhotoAsset.pixelVector(0.91f);

        Ranked<PhotoAlbum> stated = JavAIEnvironment.postgresPhotoAlbumRepository()
                .nearestBySummary().inModel(PhotoAsset.PIXEL_MODEL).to(reference).limit(1).ranked().get(0);
        Ranked<PhotoAlbum> unstated = JavAIEnvironment.postgresPhotoAlbumRepository()
                .nearestBySummary().to(reference).limit(1).ranked().get(0);

        assertEquals(unstated.entity().getId(), stated.entity().getId());
        assertEquals(unstated.similarity(), stated.similarity(), 0.0,
                "inModel() buys legibility and a check, never a different search");
    }

    @Test
    void inModelIsRefusedWhereItWouldMeanNothing() {
        // A woven client entity, through the real proxy: an @ExternalVector's model is fixed by its own
        // declaration, so there is no second answer for a qualifier to give.
        assertThrows(IllegalArgumentException.class, () ->
                JavAIEnvironment.postgresPhotoAssetRepository().nearestBy("pixels")
                        .inModel(PhotoAsset.PIXEL_MODEL));
    }

    @Test
    void theSameSearchAnswersOnATypeThatNeverOptedIn() {
        PhotoCollage wanted = collageOf("the collage being looked for", 0.95f);
        PhotoCollage other = collageOf("an unrelated collage", 0.05f);
        JavAIEnvironment.postgresPhotoCollageRepository().save(wanted);
        JavAIEnvironment.postgresPhotoCollageRepository().save(other);

        List<Ranked<PhotoCollage>> hits = JavAIEnvironment.postgresPhotoCollageRepository()
                .nearestBySummary().inModel(PhotoAsset.PIXEL_MODEL)
                .to(PhotoAsset.pixelVector(0.95f)).limit(1).ranked();

        assertEquals(wanted.getId(), hits.get(0).entity().getId(),
                "without the fold this returns nothing, which reads as 'nothing is similar' rather than "
                        + "'nothing is stored' -- the one failure a caller cannot tell from a correct answer");
        assertNull(summaryRow(PhotoCollage.class, wanted.getId()),
                "and it answers without having quietly started storing rows");
    }

    @Test
    void aReferenceFromTheWrongModelIsRefusedRatherThanRankedAcrossTwoSpaces() {
        PhotoAlbum album = albumOf("refusal", 0.44f);
        JavAIEnvironment.postgresPhotoAlbumRepository().save(album);
        EmbeddingVector textReference = ((JavAIVectorizable) album).summaryVector();

        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> JavAIEnvironment.postgresPhotoAlbumRepository()
                        .nearestBySummary().inModel(PhotoAsset.PIXEL_MODEL)
                        .to(textReference).limit(5).ranked());
        assertTrue(refused.getMessage().contains(PhotoAsset.PIXEL_MODEL));
    }

    // ---- fixtures and row reading -------------------------------------------------------------------

    private static PhotoAlbum albumOf(String title, float... seeds) {
        PhotoAlbum album = new PhotoAlbum(title);
        for (float seed : seeds) {
            album.getPhotos().add(embeddedPhoto(title, seed));
        }
        return album;
    }

    private static PhotoCollage collageOf(String title, float... seeds) {
        PhotoCollage collage = new PhotoCollage(title);
        for (float seed : seeds) {
            collage.getPhotos().add(embeddedPhoto(title, seed));
        }
        return collage;
    }

    /** A photo whose caption is really embedded and whose pixels are supplied, as a pipeline would. */
    private static PhotoAsset embeddedPhoto(String context, float seed) {
        String contentHash = "sha256:" + Integer.toHexString(context.hashCode()) + "-" + seed;
        PhotoAsset asset = new PhotoAsset("a photograph in " + context, contentHash);
        dev.xtrafe.javai.model.JavAIRuntime.supplyVector(
                asset, "pixels", PhotoAsset.pixelVector(seed), contentHash);
        return asset;
    }

    private record StoredRow(String modelId, int dims, String vector, String concatenatedText) {
    }

    /** Plain JDBC on a connection of its own: the claim is about what is in the database, and reading it
     *  back through the repository that wrote it could not tell a stored row from a cached value. */
    private static StoredRow summaryRow(Class<?> ownerType, UUID ownerId) {
        try (Connection connection = DriverManager.getConnection(MonolithicContainer.postgresUrl(),
                MonolithicContainer.POSTGRES_USERNAME, MonolithicContainer.POSTGRES_PASSWORD)) {
            try (ResultSet tables =
                         connection.getMetaData().getTables(null, null, PIXEL_SUMMARY_TABLE, null)) {
                if (!tables.next()) {
                    return null; // never provisioned, which some tests assert is the right answer
                }
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT model_id, dims, vector::text, concatenated_text FROM " + PIXEL_SUMMARY_TABLE
                            + " WHERE owner_type = ? AND owner_id = ?")) {
                statement.setString(1, ownerType.getName());
                statement.setObject(2, ownerId);
                try (ResultSet rows = statement.executeQuery()) {
                    return rows.next() ? new StoredRow(rows.getString(1), rows.getInt(2),
                            rows.getString(3), rows.getString(4)) : null;
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("Could not read " + PIXEL_SUMMARY_TABLE, e);
        }
    }
}
