package dev.xtrafe.javai.persistence;

import dev.xtrafe.javai.model.EmbeddingConsistencyMode;
import dev.xtrafe.javai.model.JavAIRuntime;
import dev.xtrafe.javai.vector.testsupport.FakeEmbeddingProvider;

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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A {@code @Vectorize} field that loses its content must lose its stored vector too, on every backend.
 *
 * <h2>Why this is a correctness test, not a cleanup test</h2>
 *
 * Vectors are stored per field and written by upsert; nothing ever deleted one except deleting the whole
 * entity. That was invisible until OMI-187 introduced {@link dev.xtrafe.javai.vector.EmbeddingVector#absent()},
 * because before it every field always produced <em>some</em> vector (an empty one embedded the empty string).
 * Now a field can legitimately have no vector -- and simply skipping the write would leave the previous
 * save's row in place.
 *
 * <p>The consequence is worse than the wasted embedding calls this ticket started from. A stale row in an ANN
 * index means the entity keeps being returned as a similarity match for content it no longer has: a wrong
 * answer, not a slow one. The in-memory object would be correct the whole time, which is exactly what makes
 * it hard to notice.
 *
 * <p>All three backends are covered because all three store vectors independently, and "this backend does it
 * right" is precisely the claim that quietly stops being true.
 */
@Testcontainers
class AbsentVectorRemovalTest {

    private static final String NEO4J_PASSWORD = "absent-vector-password";
    private static final String DATABASE = "absentvectors";

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

    private static TestArticleRepository postgresArticles;
    private static TestArticleRepository neo4jArticles;
    private static TestArticleRepository mongoArticles;

    @BeforeAll
    static void configureRepositories() {
        postgresArticles = JavAIPI.repository(TestArticleRepository.class, JavAIPersistenceConfig.builder()
                .backend(JavAIPersistenceConfig.Backend.POSTGRES)
                .postgresUrl(postgres.getJdbcUrl())
                .postgresUsername(postgres.getUsername())
                .postgresPassword(postgres.getPassword())
                .build());
        neo4jArticles = JavAIPI.repository(TestArticleRepository.class, JavAIPersistenceConfig.builder()
                .backend(JavAIPersistenceConfig.Backend.NEO4J)
                .neo4jUri(neo4j.getBoltUrl())
                .neo4jUsername("neo4j")
                .neo4jPassword(NEO4J_PASSWORD)
                .build());
        mongoArticles = JavAIPI.repository(TestArticleRepository.class, JavAIPersistenceConfig.builder()
                .backend(JavAIPersistenceConfig.Backend.MONGODB)
                .mongoUri("mongodb://" + mongo.getHost() + ":" + mongo.getMappedPort(27017)
                        + "/?directConnection=true")
                .mongoDatabase(DATABASE)
                .build());
    }

    @BeforeEach
    void configureProvider() {
        JavAIRuntime.configureConsistencyMode(EmbeddingConsistencyMode.IMMEDIATE_CONSISTENCY);
        JavAIRuntime.configureEmbeddingProvider(new FakeEmbeddingProvider());
    }

    @Test
    void postgresDeletesAVectorRowWhenItsFieldLosesContent() throws Exception {
        TestArticle article = postgresArticles.save(new TestArticle("a real title", "a real body"));
        UUID id = article.getId();
        assertTrue(postgresVectorRowExists(id, "title"),
                "precondition: a populated field must have stored a vector");

        article.setTitle(null);
        postgresArticles.save(article);

        assertFalse(postgresVectorRowExists(id, "title"),
                "a field with no content must not leave a stale vector row behind for an ANN search to find");
    }

    @Test
    void postgresLeavesOtherFieldsVectorsAlone() throws Exception {
        TestArticle article = postgresArticles.save(new TestArticle("another title", "another body"));
        UUID id = article.getId();

        article.setTitle(null);
        postgresArticles.save(article);

        assertTrue(postgresVectorRowExists(id, "body"),
                "clearing one field must not remove a sibling field's vector");
    }

    @Test
    void neo4jRemovesAVectorPropertyWhenItsFieldLosesContent() {
        TestArticle article = neo4jArticles.save(new TestArticle("neo4j title", "neo4j body"));
        UUID id = article.getId();
        assertEquals(1, neo4jVectorPropertyCount(id, "titleVector"),
                "precondition: a populated field must have stored a vector property");

        article.setTitle(null);
        neo4jArticles.save(article);

        assertEquals(0, neo4jVectorPropertyCount(id, "titleVector"),
                "a field with no content must not leave a stale vector property behind");
        assertEquals(1, neo4jVectorPropertyCount(id, "bodyVector"),
                "clearing one field must not remove a sibling field's vector");
    }

    @Test
    void mongoUnsetsAVectorFieldWhenItsFieldLosesContent() {
        TestArticle article = mongoArticles.save(new TestArticle("mongo title", "mongo body"));
        UUID id = article.getId();
        assertTrue(mongoVectorFieldExists(id, "titleVector"),
                "precondition: a populated field must have stored a vector field");

        article.setTitle(null);
        mongoArticles.save(article);

        assertFalse(mongoVectorFieldExists(id, "titleVector"),
                "a field with no content must not leave a stale vector field behind");
        assertTrue(mongoVectorFieldExists(id, "bodyVector"),
                "clearing one field must not remove a sibling field's vector");
    }

    // ---- raw datastore inspection, deliberately bypassing JavAI's own read path ----

    private static boolean postgresVectorRowExists(UUID id, String fieldName) throws Exception {
        String table = "javai_vectors__" + ModelIds.sanitize(FakeEmbeddingProvider.MODEL_ID);
        try (Connection connection = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT 1 FROM " + table + " WHERE owner_type = ? AND owner_id = ? AND field_name = ?")) {
            statement.setString(1, TestArticle.class.getName());
            statement.setObject(2, id);
            statement.setString(3, fieldName);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next();
            }
        }
    }

    private static long neo4jVectorPropertyCount(UUID id, String baseName) {
        String property = baseName + "__" + ModelIds.sanitize(FakeEmbeddingProvider.MODEL_ID);
        try (org.neo4j.driver.Driver driver = org.neo4j.driver.GraphDatabase.driver(
                neo4j.getBoltUrl(), org.neo4j.driver.AuthTokens.basic("neo4j", NEO4J_PASSWORD));
                org.neo4j.driver.Session session = driver.session()) {
            return session.executeRead(tx -> tx.run(
                            "MATCH (n:TestArticle {id: $id}) RETURN count(n.`" + property + "`) AS present",
                            org.neo4j.driver.Values.parameters("id", id.toString()))
                    .single().get("present").asLong());
        }
    }

    private static boolean mongoVectorFieldExists(UUID id, String baseName) {
        String field = baseName + "__" + ModelIds.sanitize(FakeEmbeddingProvider.MODEL_ID);
        try (com.mongodb.client.MongoClient client = com.mongodb.client.MongoClients.create(
                "mongodb://" + mongo.getHost() + ":" + mongo.getMappedPort(27017) + "/?directConnection=true")) {
            org.bson.Document document = client.getDatabase(DATABASE)
                    .getCollection("TestArticle")
                    .find(com.mongodb.client.model.Filters.eq("_id", id.toString()))
                    .first();
            return document != null && document.containsKey(field);
        }
    }
}
