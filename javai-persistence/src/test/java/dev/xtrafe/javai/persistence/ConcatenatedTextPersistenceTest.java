package dev.xtrafe.javai.persistence;

import dev.xtrafe.javai.model.EmbeddingConsistencyMode;
import dev.xtrafe.javai.model.JavAIRuntime;
import dev.xtrafe.javai.vector.testsupport.FakeEmbeddingProvider;
import dev.xtrafe.javai.vector.testsupport.RecordingEmbeddingProvider;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
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
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Concatenated text and its vector, stored and read back on every backend (OMI-191).
 *
 * <h2>What is being pinned, and why here rather than in a unit test</h2>
 *
 * The in-memory semantics -- assembly order, colouring, opt-in -- are covered by {@code javai-model}'s own
 * {@code ConcatenatedTextAssemblyTest}. What only a real datastore can answer is the storage shape: that the
 * text lands on the <b>entity-grain</b> table rather than the per-field one, that a non-participant stores
 * nothing at all, and that a loaded entity does not pay a fresh embedding for text it already has on disk.
 *
 * <p>Storing the text itself (not only its vector) is what makes re-embedding under a different model a pure
 * re-embed rather than a fresh walk of the object graph -- text is model-independent. That is the reason the
 * text column exists, so it gets an assertion rather than a comment.
 *
 * <p>All three backends, because all three store this independently and "this backend does it right" is
 * exactly the claim that quietly stops being true.
 */
@Testcontainers
class ConcatenatedTextPersistenceTest {

    private static final String NEO4J_PASSWORD = "concat-text-password";
    private static final String DATABASE = "concattext";

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

    private static TestChapterRepository postgresChapters;
    private static TestChapterRepository neo4jChapters;
    private static TestChapterRepository mongoChapters;
    private static TestArticleRepository postgresArticles;

    @BeforeAll
    static void configureRepositories() {
        postgresChapters = JavAIPI.repository(TestChapterRepository.class, postgresConfig());
        postgresArticles = JavAIPI.repository(TestArticleRepository.class, postgresConfig());
        neo4jChapters = JavAIPI.repository(TestChapterRepository.class, JavAIPersistenceConfig.builder()
                .backend(JavAIPersistenceConfig.Backend.NEO4J)
                .neo4jUri(neo4j.getBoltUrl())
                .neo4jUsername("neo4j")
                .neo4jPassword(NEO4J_PASSWORD)
                .build());
        mongoChapters = JavAIPI.repository(TestChapterRepository.class, JavAIPersistenceConfig.builder()
                .backend(JavAIPersistenceConfig.Backend.MONGODB)
                .mongoUri("mongodb://" + mongo.getHost() + ":" + mongo.getMappedPort(27017)
                        + "/?directConnection=true")
                .mongoDatabase(DATABASE)
                .build());
    }

    private static JavAIPersistenceConfig postgresConfig() {
        return JavAIPersistenceConfig.builder()
                .backend(JavAIPersistenceConfig.Backend.POSTGRES)
                .postgresUrl(postgres.getJdbcUrl())
                .postgresUsername(postgres.getUsername())
                .postgresPassword(postgres.getPassword())
                .build();
    }

    @BeforeEach
    void configureProvider() {
        JavAIRuntime.configureConsistencyMode(EmbeddingConsistencyMode.IMMEDIATE_CONSISTENCY);
        JavAIRuntime.configureEmbeddingProvider(new FakeEmbeddingProvider());
    }

    // ---- the storage shape ----

    @Test
    void postgresStoresTheTextAndItsVectorOnTheEntityGrainRow() throws Exception {
        TestChapter chapter = new TestChapter("Chapter One", "It was a dark and stormy night.");
        chapter.setContinuation(new TestChapter("Chapter Two", "The rain continued."));
        TestChapter saved = postgresChapters.save(chapter);

        String storedText = postgresConcatenatedText(saved.getId());

        assertNotNull(storedText, "the assembled text must be stored, not only its vector");
        assertTrue(storedText.contains("It was a dark and stormy night."), storedText);
        assertTrue(storedText.contains("The rain continued."),
                "the absorbed continuation must be part of the stored text: " + storedText);
        assertTrue(postgresConcatenatedVectorExists(saved.getId()));
    }

    /** The text is stored so a model switch re-embeds a string rather than re-walking the object graph. */
    @Test
    void postgresStoresTextThatMatchesWhatTheObjectWouldAssemble() throws Exception {
        TestChapter chapter = new TestChapter("Standalone", "No continuation here.");
        TestChapter saved = postgresChapters.save(chapter);

        assertEquals(saved.concatenatedText(), postgresConcatenatedText(saved.getId()));
    }

    /** Declining must store nothing -- the columns exist for everyone, the data does not. */
    @Test
    void postgresStoresNothingForAnEntityThatDidNotOptIn() throws Exception {
        TestArticle article = postgresArticles.save(new TestArticle("a title", "a body"));

        assertNull(postgresConcatenatedText(article.getId()),
                "a non-participating entity must not carry concatenated text");
    }

    @Test
    void neo4jStoresTheTextAndItsVectorOnTheNode() {
        TestChapter saved = neo4jChapters.save(new TestChapter("Neo Chapter", "Graph-shaped prose."));

        assertTrue(neo4jHasConcatenatedText(saved.getId()),
                "the assembled text must be stored as a per-entity node property");
    }

    @Test
    void mongoStoresTheTextAndItsVectorOnTheDocument() {
        TestChapter saved = mongoChapters.save(new TestChapter("Mongo Chapter", "Document-shaped prose."));

        assertTrue(mongoHasConcatenatedText(saved.getId()),
                "the assembled text must be stored as a per-entity document field");
    }

    // ---- round trip ----

    @Test
    void aLoadedEntityDoesNotReEmbedTextItAlreadyHasStored() {
        TestChapter saved = postgresChapters.save(
                new TestChapter("Reload Me", "Prose that was already embedded once."));

        RecordingEmbeddingProvider recording = new RecordingEmbeddingProvider();
        JavAIRuntime.configureEmbeddingProvider(recording);
        TestChapter loaded = postgresChapters.findById(saved.getId()).orElseThrow();
        loaded.concatenatedTextVector();

        assertEquals(0, recording.ledger().totalCalls(),
                "a stored text vector must be hydrated, not recomputed -- otherwise every load of every"
                        + " participating entity costs a live model call\n\n" + recording.ledger().report());
    }

    @Test
    void theSearchFindsAnEntityByItsConcatenatedTextVector() {
        TestChapter saved = postgresChapters.save(
                new TestChapter("Findable", "Distinctive searchable prose."));

        var matches = postgresChapters.findNearestByConcatenatedTextVector(
                saved.concatenatedTextVector(), 5);

        assertTrue(matches.stream().anyMatch(c -> c.getId().equals(saved.getId())),
                "an entity must be findable by the vector it stored");
    }

    /**
     * Rejected when the repository is created, not on first call -- otherwise the query would return an
     * empty list forever, indistinguishable from "nothing was similar".
     */
    @Test
    void aRepositoryForANonParticipatingTypeIsRejectedAtCreationTime() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> JavAIPI.repository(NonParticipatingChapterRepository.class, postgresConfig()));

        assertTrue(failure.getMessage().contains("@Summary(concatenate = true)"),
                "the message must name the annotation that is missing: " + failure.getMessage());
    }

    /** {@link TestArticle} never opts in, so this method can never be satisfied. */
    interface NonParticipatingChapterRepository extends JavAIRepository<TestArticle> {
        java.util.List<TestArticle> findNearestByConcatenatedTextVector(
                dev.xtrafe.javai.vector.EmbeddingVector reference, int limit);
    }

    /**
     * **A concatenated text vector with no summary vector is stored, not refused** (Dom, 2026-08-23 —
     * OMI-435).
     *
     * <h2>How the pair arises, which is the part that is not obvious</h2>
     *
     * Within one computation the two aggregates agree: text implies content implies a summary. What breaks
     * the symmetry is <b>time</b>. A {@code @Summary} container is recomputed <em>after</em> commit, by
     * {@code recomputeOwner}, which reloads the entity in a fresh session and calls {@code hydrateVectors} —
     * restoring the <b>stored</b> concatenated vector into a clean slot. So the reloaded instance carries
     * the cleared fields <em>and</em> the concatenated vector computed from the text it used to hold. Its
     * summary recomputes to absent; its concatenated vector is served from storage, present.
     *
     * <p>⚠️ That pair used to throw. On the inline path the owner's whole save rolled back over a vector
     * bookkeeping constraint, and on the drain path the recomputation requeued forever, leaving every
     * container above it stale. The column is nullable now and the row records what is true.
     */
    @Test
    void aConcatenatedVectorWithNoSummaryIsStoredRatherThanRefused() throws Exception {
        TestChapter chapter = postgresChapters.save(new TestChapter("Departure", "The tide went out."));
        UUID id = chapter.getId();
        assertNotNull(postgresConcatenatedText(id), "precondition: it stored text to go stale");

        chapter.setHeading(null);
        chapter.setProse(null);
        postgresChapters.save(chapter);

        // ⚠️ The row must still be **there**, holding the pair. A bare "the summary is null" would pass just
        // as well if the row had been deleted outright — which is the other thing this writer does, and not
        // what is being tested. Deleting is for both-absent; this is one-absent, and the text survives.
        assertTrue(postgresSummaryRowExists(id), "the row holds the pair rather than being deleted");
        assertNull(postgresSummaryVector(id), "no content of its own means no summary vector, and says so");
        assertTrue(postgresConcatenatedVectorExists(id),
                "and the concatenated vector it was hydrated with is what made the pair");
    }

    // ---- raw datastore inspection, deliberately bypassing JavAI's own read path ----

    private static boolean postgresSummaryRowExists(UUID id) throws Exception {
        String table = "javai_summary_vectors__" + ModelIds.sanitize(FakeEmbeddingProvider.MODEL_ID);
        try (Connection connection = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT 1 FROM " + table + " WHERE owner_id = ?")) {
            statement.setObject(1, id);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next();
            }
        }
    }

    private static String postgresSummaryVector(UUID id) throws Exception {
        String table = "javai_summary_vectors__" + ModelIds.sanitize(FakeEmbeddingProvider.MODEL_ID);
        try (Connection connection = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT vector::text FROM " + table + " WHERE owner_id = ?")) {
            statement.setObject(1, id);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? rows.getString(1) : null;
            }
        }
    }



    private static String postgresConcatenatedText(UUID id) throws Exception {
        String table = "javai_summary_vectors__" + ModelIds.sanitize(FakeEmbeddingProvider.MODEL_ID);
        try (Connection connection = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT concatenated_text FROM " + table + " WHERE owner_id = ?")) {
            statement.setObject(1, id);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? rows.getString(1) : null;
            }
        }
    }

    private static boolean postgresConcatenatedVectorExists(UUID id) throws Exception {
        String table = "javai_summary_vectors__" + ModelIds.sanitize(FakeEmbeddingProvider.MODEL_ID);
        try (Connection connection = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT 1 FROM " + table
                                + " WHERE owner_id = ? AND concatenated_text_vector IS NOT NULL")) {
            statement.setObject(1, id);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next();
            }
        }
    }

    private static boolean neo4jHasConcatenatedText(UUID id) {
        String property = "concatenatedText__" + ModelIds.sanitize(FakeEmbeddingProvider.MODEL_ID);
        try (org.neo4j.driver.Driver driver = org.neo4j.driver.GraphDatabase.driver(
                neo4j.getBoltUrl(), org.neo4j.driver.AuthTokens.basic("neo4j", NEO4J_PASSWORD));
                org.neo4j.driver.Session session = driver.session()) {
            return session.executeRead(tx -> tx.run(
                            "MATCH (n:TestChapter {id: $id}) RETURN n.`" + property + "` AS text",
                            org.neo4j.driver.Values.parameters("id", id.toString()))
                    .list().stream()
                    .anyMatch(record -> !record.get("text").isNull()));
        }
    }

    private static boolean mongoHasConcatenatedText(UUID id) {
        String field = "concatenatedText__" + ModelIds.sanitize(FakeEmbeddingProvider.MODEL_ID);
        try (com.mongodb.client.MongoClient client = com.mongodb.client.MongoClients.create(
                "mongodb://" + mongo.getHost() + ":" + mongo.getMappedPort(27017) + "/?directConnection=true")) {
            org.bson.Document document = client.getDatabase(DATABASE)
                    .getCollection("TestChapter")
                    .find(new org.bson.Document("_id", id.toString()))
                    .first();
            return document != null && document.getString(field) != null;
        }
    }
}
