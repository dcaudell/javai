package dev.xtrafe.javai.persistence;

import dev.xtrafe.javai.model.EmbeddingConsistencyMode;
import dev.xtrafe.javai.model.JavAIRuntime;
import dev.xtrafe.javai.vector.EmbeddingVector;
import dev.xtrafe.javai.vector.testsupport.RecordingEmbeddingProvider;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Neo4jContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ⚠️ <b>A query must never create storage.</b> Reading is not a reason to provision anything.
 *
 * <p>This was not a hypothetical. {@code findNearest} resolved its table by calling
 * {@code ensure*VectorTable(reference.modelId(), reference.dims())}, so a search whose reference named a
 * model nothing had ever been written in <em>created that model's table</em> — two HNSW indexes and all —
 * and then returned no rows, because there were none. The failed query left a permanent, empty, indexed
 * table in the schema, and every repetition of the mistake minted another. Neo4j and MongoDB had the same
 * shape with worse manners: they created a junk vector index <em>and blocked the caller</em> waiting for it
 * to come online.
 *
 * <p>The rule this file pins is deliberately broader than the bug that prompted it, because the next
 * instance will not look like the last one: <b>take a full inventory of the schema, run every shape of read
 * this backend can serve, and require the inventory to be identical afterwards.</b> A future read path that
 * provisions anything at all — a table, an index, a queue — fails here without anyone having to have
 * predicted it.
 */
@Testcontainers
class QueryTimeSchemaCreationTest {

    private static final String NEO4J_PASSWORD = "junk-table-password";

    /** A model nothing in this test ever writes. Every read below is aimed at it. */
    private static final String UNWRITTEN_MODEL = "never-written-model/pp9";
    private static final int UNWRITTEN_DIMS = 12;

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

    private static TestPhotoAlbumRepository albums;
    private static TestImageAssetRepository assets;
    private static TestChapterRepository chapters;
    private static TestImageAssetRepository neo4jAssets;
    private static TestImageAssetRepository mongoAssets;

    @BeforeAll
    static void seedSomethingReal() {
        JavAIRuntime.configureEmbeddingProvider(new RecordingEmbeddingProvider());
        JavAIRuntime.configureConsistencyMode(EmbeddingConsistencyMode.IMMEDIATE_CONSISTENCY);
        JavAIPersistenceConfig config = JavAIPersistenceConfig.builder()
                .backend(JavAIPersistenceConfig.Backend.POSTGRES)
                .postgresUrl(postgres.getJdbcUrl())
                .postgresUsername(postgres.getUsername())
                .postgresPassword(postgres.getPassword())
                .build();
        albums = JavAIPI.repository(TestPhotoAlbumRepository.class, config);
        assets = JavAIPI.repository(TestImageAssetRepository.class, config);
        chapters = JavAIPI.repository(TestChapterRepository.class, config);
        neo4jAssets = JavAIPI.repository(TestImageAssetRepository.class, JavAIPersistenceConfig.builder()
                .backend(JavAIPersistenceConfig.Backend.NEO4J)
                .neo4jUri(neo4j.getBoltUrl())
                .neo4jUsername("neo4j")
                .neo4jPassword(NEO4J_PASSWORD)
                .build());
        mongoAssets = JavAIPI.repository(TestImageAssetRepository.class, JavAIPersistenceConfig.builder()
                .backend(JavAIPersistenceConfig.Backend.MONGODB)
                .mongoUri("mongodb://" + mongo.getHost() + ":" + mongo.getMappedPort(27017)
                        + "/?directConnection=true")
                .mongoDatabase("junktables")
                .build());

        // Write something real first, so the inventory below is a populated schema rather than an empty one
        // -- "created nothing" has to be checked against a database that already has the right things in it.
        TestImageAsset asset = new TestImageAsset("a real photo", "sha256:real");
        JavAIRuntime.supplyVector(asset, "pixels", TestImageAsset.imageVector(0.3f), "sha256:real");
        TestPhotoAlbum album = new TestPhotoAlbum("a real album");
        album.getPhotos().add(asset);
        albums.save(album);
        chapters.save(new TestChapter("a heading", "prose that is really embedded"));
        neo4jAssets.save(new TestImageAsset("a neo4j photo", "sha256:neo"));
        mongoAssets.save(new TestImageAsset("a mongo photo", "sha256:mongo"));
    }

    /** A reference in a model nothing was written in -- the shape that used to mint a table per query. */
    private static EmbeddingVector unwrittenReference() {
        float[] values = new float[UNWRITTEN_DIMS];
        for (int i = 0; i < UNWRITTEN_DIMS; i++) {
            values[i] = (float) Math.cos(i);
        }
        return new EmbeddingVector(values, UNWRITTEN_MODEL, UNWRITTEN_DIMS, Instant.now());
    }

    @Test
    void noReadOfAnyShapeChangesThePostgresSchema() {
        Set<String> before = relations();
        assertFalse(before.isEmpty(), "the seed must have provisioned something, or this proves nothing");
        EmbeddingVector unwritten = unwrittenReference();

        // Every grain the backend can serve, each aimed at a model nothing was ever written in.
        assertTrue(albums.nearestBySummary().to(unwritten).limit(5).results().isEmpty());
        assertTrue(albums.nearestBySummary().inModel(UNWRITTEN_MODEL).to(unwritten).limit(5).results().isEmpty());
        assertTrue(albums.nearest().to(unwritten).limit(5).results().isEmpty());
        assertTrue(albums.nearestBy("title").to(unwritten).limit(5).results().isEmpty());
        assertTrue(assets.nearestBy("pixels").to(unwritten).limit(5).results().isEmpty());
        // Refused rather than answered empty (OMI-458) -- but the point here is that the refusal happens
        // *before* anything is provisioned, so a rejected query leaves no more behind than an accepted one.
        assertThrows(IllegalArgumentException.class,
                () -> chapters.nearestByConcatenatedText().to(unwritten).limit(5).results());
        // Narrowed, paged, and via the method-name idiom too -- each reaches findNearest by its own route.
        assertTrue(albums.nearestBySummary().to(unwritten).where("title").is("a real album")
                .offset(1).limit(5).results().isEmpty());
        assertTrue(albums.findNearestBySummaryVector(unwritten, 5).isEmpty());

        assertCreatedNothing(before, relations());
    }

    @Test
    void aReadInAModelThatWasWrittenStillFindsItsTable() {
        // The other half, and the reason this cannot be fixed by never resolving anything: a real search in
        // a real model must still work, against a table the write path provisioned.
        //
        // The reference is folded from an unsaved stand-in holding the same photo, rather than from the
        // saved album -- a detached container cannot initialize its lazy @Summary collection, which is
        // ordinary JPA and has nothing to do with what this test is about.
        TestImageAsset sameAsset = new TestImageAsset("a real photo", "sha256:real");
        JavAIRuntime.supplyVector(sameAsset, "pixels", TestImageAsset.imageVector(0.3f), "sha256:real");
        TestPhotoAlbum standIn = new TestPhotoAlbum("a real album");
        standIn.getPhotos().add(sameAsset);

        assertFalse(albums.nearestBySummary()
                .to(standIn.summaryVector(TestImageAsset.IMAGE_MODEL)).limit(1).results().isEmpty(),
                "a search in a model the write path did provision must still find its table");
    }

    @Test
    void noReadCreatesANeo4jVectorIndex() {
        Set<String> before = neo4jIndexes();
        assertTrue(neo4jAssets.nearestBy("pixels").to(unwrittenReference()).limit(5).results().isEmpty());
        // Lazy creation on first query is fine here -- it is the only trigger Neo4j has, since its write
        // path sets properties and never creates indexes. What is not fine is creating one for a model no
        // node was ever written in: that index is junk, and the caller blocks while it comes online.
        assertCreatedNothing(before, neo4jIndexes());
    }

    @Test
    void noReadCreatesAMongoSearchIndex() {
        Set<String> before = mongoIndexes();
        assertTrue(mongoAssets.nearestBy("pixels").to(unwrittenReference()).limit(5).results().isEmpty());
        assertCreatedNothing(before, mongoIndexes()); // same rule as Neo4j: create only when there is something to index
    }

    /**
     * Reports <b>what was added</b>, not two full inventories.
     *
     * <p>A bare {@code assertEquals} on the sets prints both in full -- forty-odd relations on each side of
     * an arrow -- and the reader has to diff them by eye to find the two entries that matter. The whole
     * value of this test is naming the thing that got created, so it names it.
     */
    private static void assertCreatedNothing(Set<String> before, Set<String> after) {
        Set<String> created = new LinkedHashSet<>(after);
        created.removeAll(before);
        assertTrue(created.isEmpty(), () -> "a read provisioned " + created.size() + " relation(s): " + created
                + ". Reading is not a reason to create storage -- resolve the table or index name and answer "
                + "empty when it is absent. Provisioning belongs to the write path, which knows a vector "
                + "exists to store.");
        // Removal is not the failure this exists for, but a read that dropped something would be worse still.
        Set<String> lost = new LinkedHashSet<>(before);
        lost.removeAll(after);
        assertTrue(lost.isEmpty(), () -> "a read removed " + lost);
    }

    // ---- schema inventory ---------------------------------------------------------------------------

    /** Every table and index in the schema -- both, because the bug created two indexes per junk table and
     *  a future one may add an index to a table that already exists. */
    private static Set<String> relations() {
        Set<String> found = new LinkedHashSet<>();
        try (Connection connection = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(
                     "SELECT 'table:' || table_name FROM information_schema.tables"
                             + " WHERE table_schema = current_schema()"
                             + " UNION ALL"
                             + " SELECT 'index:' || indexname FROM pg_indexes"
                             + " WHERE schemaname = current_schema()")) {
            while (rows.next()) {
                found.add(rows.getString(1));
            }
        } catch (Exception e) {
            throw new IllegalStateException("Could not inventory the schema", e);
        }
        return found;
    }

    private static Set<String> neo4jIndexes() {
        Set<String> found = new LinkedHashSet<>();
        try (org.neo4j.driver.Driver driver = org.neo4j.driver.GraphDatabase.driver(neo4j.getBoltUrl(),
                org.neo4j.driver.AuthTokens.basic("neo4j", NEO4J_PASSWORD));
             org.neo4j.driver.Session session = driver.session()) {
            for (org.neo4j.driver.Record record : session.run("SHOW INDEXES YIELD name RETURN name").list()) {
                found.add(record.get("name").asString());
            }
        }
        return found;
    }

    private static Set<String> mongoIndexes() {
        Set<String> found = new LinkedHashSet<>();
        try (com.mongodb.client.MongoClient client = com.mongodb.client.MongoClients.create(
                "mongodb://" + mongo.getHost() + ":" + mongo.getMappedPort(27017) + "/?directConnection=true")) {
            com.mongodb.client.MongoDatabase database = client.getDatabase("junktables");
            for (String collection : database.listCollectionNames()) {
                for (org.bson.Document index : database.getCollection(collection).listSearchIndexes()) {
                    found.add(collection + "/" + index.getString("name"));
                }
            }
        }
        return found;
    }
}
