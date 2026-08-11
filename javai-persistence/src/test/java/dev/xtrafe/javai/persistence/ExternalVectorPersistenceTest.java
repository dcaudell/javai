package dev.xtrafe.javai.persistence;

import dev.xtrafe.javai.model.EmbeddingConsistencyMode;
import dev.xtrafe.javai.model.JavAIRuntime;
import dev.xtrafe.javai.vector.EmbeddingVector;
import dev.xtrafe.javai.vector.testsupport.EmbeddingLedger;
import dev.xtrafe.javai.vector.testsupport.RecordingEmbeddingProvider;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Neo4jContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An {@code @ExternalVector} must survive a persistence round trip -- including the content key that decides
 * whether it still applies (OMI-290).
 *
 * <p>The key is the part worth testing hardest, because losing it fails silently in the most expensive
 * direction: the vector would be written, read back, held in the slot, and never served, so every search
 * would simply return nothing and look like a model that had not run yet.
 */
@Testcontainers
class ExternalVectorPersistenceTest {

    private static final String NEO4J_PASSWORD = "external-vector-password";

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    @Container
    static final Neo4jContainer<?> neo4j = new Neo4jContainer<>(DockerImageName.parse("neo4j:5.26-community"))
            .withAdminPassword(NEO4J_PASSWORD);

    @Container
    static final GenericContainer<?> mongo =
            new GenericContainer<>(DockerImageName.parse("mongodb/mongodb-atlas-local:8.2"))
                    .withExposedPorts(27017)
                    .waitingFor(Wait.forHealthcheck())
                    .withStartupTimeout(Duration.ofMinutes(3));

    private static TestImageAssetRepository assets;
    private static TestImageAssetRepository neo4jAssets;
    private static TestImageAssetRepository mongoAssets;
    private static RecordingEmbeddingProvider provider;
    private static EmbeddingLedger ledger;

    @BeforeAll
    static void configureRepository() {
        provider = new RecordingEmbeddingProvider();
        ledger = provider.ledger();
        JavAIRuntime.configureEmbeddingProvider(provider);
        JavAIRuntime.configureConsistencyMode(EmbeddingConsistencyMode.IMMEDIATE_CONSISTENCY);
        assets = JavAIPI.repository(TestImageAssetRepository.class, JavAIPersistenceConfig.builder()
                .backend(JavAIPersistenceConfig.Backend.POSTGRES)
                .postgresUrl(postgres.getJdbcUrl())
                .postgresUsername(postgres.getUsername())
                .postgresPassword(postgres.getPassword())
                .build());
        neo4jAssets = JavAIPI.repository(TestImageAssetRepository.class, JavAIPersistenceConfig.builder()
                .backend(JavAIPersistenceConfig.Backend.NEO4J)
                .neo4jUri(neo4j.getBoltUrl())
                .neo4jUsername("neo4j")
                .neo4jPassword(NEO4J_PASSWORD)
                .build());
        mongoAssets = JavAIPI.repository(TestImageAssetRepository.class, JavAIPersistenceConfig.builder()
                .backend(JavAIPersistenceConfig.Backend.MONGODB)
                .mongoUri("mongodb://" + mongo.getHost() + ":" + mongo.getMappedPort(27017) + "/?directConnection=true")
                .mongoDatabase("externalvectors")
                .build());
    }

    /** Every backend stores these independently, and "this one does it right" is exactly the claim that
     *  quietly stops being true -- the same reasoning {@code AbsentVectorRemovalTest} states for its own
     *  three-backend coverage. */
    static Stream<org.junit.jupiter.params.provider.Arguments> everyBackend() {
        return Stream.of(
                org.junit.jupiter.params.provider.Arguments.of("postgres", assets),
                org.junit.jupiter.params.provider.Arguments.of("neo4j", neo4jAssets),
                org.junit.jupiter.params.provider.Arguments.of("mongodb", mongoAssets));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("everyBackend")
    void everyBackendRoundTripsTheVectorWithItsContentKey(String backend, TestImageAssetRepository repository) {
        TestImageAsset asset = new TestImageAsset("a photo on " + backend, "sha256:" + backend);
        EmbeddingVector supplied = TestImageAsset.imageVector(0.25f);
        assertTrue(JavAIRuntime.supplyVector(asset, "pixels", supplied, "sha256:" + backend));
        repository.save(asset);

        TestImageAsset loaded = repository.findById(asset.getId()).orElseThrow();
        EmbeddingVector served = loaded.externalVector("pixels");

        assertFalse(served.isAbsent(), backend + " must hydrate the content key alongside the vector");
        assertArrayEquals(supplied.values(), served.values());
        assertEquals(TestImageAsset.IMAGE_MODEL, served.modelId());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("everyBackend")
    void everyBackendStopsServingAVectorWhoseContentChanged(String backend, TestImageAssetRepository repository) {
        TestImageAsset asset = new TestImageAsset("a re-uploaded photo on " + backend, "sha256:first-" + backend);
        JavAIRuntime.supplyVector(asset, "pixels", TestImageAsset.imageVector(0.25f), "sha256:first-" + backend);
        repository.save(asset);
        assertFalse(repository.findById(asset.getId()).orElseThrow().externalVector("pixels").isAbsent());

        asset.setContentHash("sha256:second-" + backend);
        repository.save(asset);

        TestImageAsset loaded = repository.findById(asset.getId()).orElseThrow();
        assertTrue(loaded.externalVector("pixels").isAbsent(),
                backend + " must not go on serving a vector for content the asset no longer references");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("everyBackend")
    void everyBackendSavesAnAssetWhoseVectorHasNotArrived(String backend, TestImageAssetRepository repository) {
        // The ordinary state between upload and the pipeline catching up: an unremarkable save.
        TestImageAsset asset = new TestImageAsset("still queued on " + backend, "sha256:pending-" + backend);
        repository.save(asset);

        assertTrue(repository.findById(asset.getId()).orElseThrow().externalVector("pixels").isAbsent());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("everyBackend")
    void everyBackendReplacesRatherThanAccumulatesOnResupply(String backend, TestImageAssetRepository repository) {
        TestImageAsset asset = new TestImageAsset("reprocessed on " + backend, "sha256:before-" + backend);
        JavAIRuntime.supplyVector(asset, "pixels", TestImageAsset.imageVector(0.25f), "sha256:before-" + backend);
        repository.save(asset);

        asset.setContentHash("sha256:after-" + backend);
        EmbeddingVector reprocessed = TestImageAsset.imageVector(0.9f);
        assertTrue(JavAIRuntime.supplyVector(asset, "pixels", reprocessed, "sha256:after-" + backend));
        repository.save(asset);

        TestImageAsset loaded = repository.findById(asset.getId()).orElseThrow();
        assertArrayEquals(reprocessed.values(), loaded.externalVector("pixels").values());
    }

    @BeforeEach
    void resetLedger() {
        ledger.reset();
    }

    @Test
    void aSuppliedVectorSurvivesTheRoundTripWithItsContentKey() {
        TestImageAsset asset = new TestImageAsset("a photo of a cat", "sha256:cat");
        EmbeddingVector supplied = TestImageAsset.imageVector(0.25f);
        assertTrue(JavAIRuntime.supplyVector(asset, "pixels", supplied, "sha256:cat"));
        assets.save(asset);

        TestImageAsset loaded = assets.findById(asset.getId()).orElseThrow();

        // Not merely "a vector came back": it must be *served*, which only happens when the stored content
        // key matches the loaded entity's own. Hydrating the vector without the key would look identical
        // here except that this assertion would fail -- absent, exactly as if nothing had ever been supplied.
        EmbeddingVector served = loaded.externalVector("pixels");
        assertFalse(served.isAbsent(), "the content key must be hydrated alongside the vector");
        assertArrayEquals(supplied.values(), served.values());
        assertEquals(TestImageAsset.IMAGE_MODEL, served.modelId());
        assertEquals(TestImageAsset.IMAGE_DIMS, served.dims());
    }

    @Test
    void theContentKeyIsStoredInItsOwnColumn() throws Exception {
        TestImageAsset asset = new TestImageAsset("a photo of a dog", "sha256:dog");
        JavAIRuntime.supplyVector(asset, "pixels", TestImageAsset.imageVector(0.5f), "sha256:dog");
        assets.save(asset);

        assertEquals("sha256:dog", readComputedFor(asset));
    }

    @Test
    void itLivesInItsOwnModelsTableNotTheConfiguredOnes() throws Exception {
        TestImageAsset asset = new TestImageAsset("a photo of a bird", "sha256:bird");
        JavAIRuntime.supplyVector(asset, "pixels", TestImageAsset.imageVector(0.75f), "sha256:bird");
        assets.save(asset);

        // Storage is partitioned by model, and the model here is declared rather than configured. A row
        // written into the text model's table would be found by nothing and would also be the wrong width.
        assertEquals(1, countRows(imageTable(), asset));
        assertEquals(0, countRows(textTable(), asset),
                "no pixels row may appear in the configured text model's table");
    }

    @Test
    void savingIsNotAnOpportunityToComputeIt() {
        TestImageAsset asset = new TestImageAsset("a uniquely captioned photo", "sha256:unique");
        JavAIRuntime.supplyVector(asset, "pixels", TestImageAsset.imageVector(0.25f), "sha256:unique");
        ledger.reset();

        assets.save(asset);
        ledger.awaitQuiescence();

        // A flush forces every read on its thread to compute accurately, overriding the configured mode.
        // For an external vector there is nothing to compute and no provider that could -- so the only text
        // that may reach the provider is the caption's.
        ledger.assertEmbeddedExactlyOnce("a uniquely captioned photo");
    }

    @Test
    void aVectorForContentTheAssetNoLongerHasIsRemovedOnTheNextSave() throws Exception {
        TestImageAsset asset = new TestImageAsset("a re-uploaded photo", "sha256:first");
        JavAIRuntime.supplyVector(asset, "pixels", TestImageAsset.imageVector(0.25f), "sha256:first");
        assets.save(asset);
        assertEquals(1, countRows(imageTable(), asset));

        // The asset now references different bytes; the stored vector describes the old ones.
        asset.setContentHash("sha256:second");
        assets.save(asset);

        // Deleted rather than left behind. A stale row in an ANN index is worse than a missing one: the
        // asset would go on matching searches for content it no longer has -- the same argument
        // AbsentVectorRemovalTest makes for a @Vectorize field that loses its content.
        assertEquals(0, countRows(imageTable(), asset));
        TestImageAsset loaded = assets.findById(asset.getId()).orElseThrow();
        assertTrue(loaded.externalVector("pixels").isAbsent());
    }

    @Test
    void anAssetWhoseVectorHasNotArrivedYetSavesAndLoadsCleanly() throws Exception {
        // The ordinary state of an asset between upload and the pipeline catching up. It must be an
        // unremarkable save, not an error and not a row.
        TestImageAsset asset = new TestImageAsset("still in the queue", "sha256:pending");
        assets.save(asset);

        TestImageAsset loaded = assets.findById(asset.getId()).orElseThrow();
        assertTrue(loaded.externalVector("pixels").isAbsent());
        assertEquals(0, countRows(imageTable(), asset));
        assertNull(readComputedFor(asset));
    }

    @Test
    void resupplyingUnderANewContentKeyReplacesTheStoredRow() throws Exception {
        TestImageAsset asset = new TestImageAsset("a re-processed photo", "sha256:before");
        JavAIRuntime.supplyVector(asset, "pixels", TestImageAsset.imageVector(0.25f), "sha256:before");
        assets.save(asset);

        asset.setContentHash("sha256:after");
        EmbeddingVector reprocessed = TestImageAsset.imageVector(0.9f);
        assertTrue(JavAIRuntime.supplyVector(asset, "pixels", reprocessed, "sha256:after"));
        assets.save(asset);

        TestImageAsset loaded = assets.findById(asset.getId()).orElseThrow();
        assertArrayEquals(reprocessed.values(), loaded.externalVector("pixels").values());
        assertEquals("sha256:after", readComputedFor(asset));
        assertEquals(1, countRows(imageTable(), asset), "replaced, not accumulated");
    }

    // ---- direct SQL, so the assertions are about what is actually stored ------------------------------

    private static String imageTable() {
        return "javai_vectors__" + ModelIds.sanitize(TestImageAsset.IMAGE_MODEL);
    }

    private static String textTable() {
        return "javai_vectors__" + ModelIds.sanitize(JavAIRuntime.currentModelId());
    }

    private static String readComputedFor(TestImageAsset asset) throws Exception {
        try (Connection connection = connect();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT computed_for FROM " + imageTable()
                             + " WHERE owner_id = ? AND field_name = 'pixels'")) {
            statement.setObject(1, asset.getId());
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? rows.getString(1) : null;
            }
        } catch (org.postgresql.util.PSQLException e) {
            return null; // the table does not exist yet, which is itself "nothing stored"
        }
    }

    private static int countRows(String table, TestImageAsset asset) throws Exception {
        try (Connection connection = connect();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT count(*) FROM " + table + " WHERE owner_id = ? AND field_name = 'pixels'")) {
            statement.setObject(1, asset.getId());
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? rows.getInt(1) : 0;
            }
        } catch (org.postgresql.util.PSQLException e) {
            return 0; // no such table: nothing stored, which is what the caller is asking about
        }
    }

    private static Connection connect() throws Exception {
        return DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }
}
