package dev.xtrafe.javai.e2e;

import dev.xtrafe.javai.e2e.domain.Article;
import dev.xtrafe.javai.e2e.domain.ArticleRepository;
import dev.xtrafe.javai.e2e.domain.Comment;
import dev.xtrafe.javai.e2e.domain.CommentRepository;
import dev.xtrafe.javai.persistence.JavAIPI;
import dev.xtrafe.javai.persistence.JavAIPersistenceConfig;
import dev.xtrafe.javai.runtime.EmbeddingVector;
import dev.xtrafe.javai.runtime.JavAIRuntime;
import dev.xtrafe.javai.runtime.JavAIVectorizable;
import dev.xtrafe.javai.runtime.LocalEmbeddingDefaults;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.neo4j.driver.Values;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Persistence Bridge, end to end: real embeddings (via {@link MonolithicInfrastructure}'s Ollama, same as
 * {@link ArticleGraphEmbeddingE2ETest}), real weaving, and this project's own {@link Article}/
 * {@link Comment} domain -- not {@code javai-persistence}'s own flat, non-relational test fixture --
 * saved into and queried back out of both real backends the monolithic container provides.
 *
 * <p>A repository proxy is bound to whichever backend was configured at the moment
 * {@link JavAIPI#repository(Class)} created it -- switching {@link JavAIPI#configurePersistence} afterward
 * doesn't retroactively affect an already-created proxy. That's what lets this one test class hold both a
 * Postgres-backed and a Neo4j-backed {@code ArticleRepository} at once, each fully independent, rather
 * than needing two separate test classes.
 */
class PersistenceE2ETest {

    private static ArticleRepository postgresRepository;
    private static ArticleRepository neo4jRepository;

    @BeforeAll
    static void configureProviderAndRepositories() {
        JavAIRuntime.configureEmbeddingProvider(LocalEmbeddingDefaults.create(MonolithicInfrastructure.embeddingEndpoint()));

        JavAIPI.configurePersistence(JavAIPersistenceConfig.builder()
                .backend(JavAIPersistenceConfig.Backend.POSTGRES)
                .postgresUrl(MonolithicInfrastructure.postgresUrl())
                .postgresUsername("javai")
                .postgresPassword("javai")
                .build());
        postgresRepository = JavAIPI.repository(ArticleRepository.class);

        JavAIPI.configurePersistence(JavAIPersistenceConfig.builder()
                .backend(JavAIPersistenceConfig.Backend.NEO4J)
                .neo4jUri(MonolithicInfrastructure.neo4jUri())
                .neo4jUsername("neo4j")
                .neo4jPassword("javai12345")
                .build());
        neo4jRepository = JavAIPI.repository(ArticleRepository.class);
        // Comment is never queried independently in this project -- only reached via Article's @Summary
        // fields -- but the Neo4j backend still needs its node label registered to hydrate a related
        // Comment node back into a real Comment object. See CommentRepository's own javadoc.
        JavAIPI.repository(CommentRepository.class);
    }

    private static Article newArticle(String title, String body) {
        Article article = new Article(title, body);
        article.setFeaturedComment(new Comment("alice", "first take: " + title));
        return article;
    }

    // ---- Postgres ---------------------------------------------------------------------------

    @Test
    void postgresSaveAndFindByIdRoundTrips() {
        Article article = postgresRepository.save(newArticle("Zero-day disclosed",
                "A critical vulnerability affecting a widely used TLS library was patched today."));
        assertTrue(article.getId() != null);

        Optional<Article> found = postgresRepository.findById(article.getId());
        assertTrue(found.isPresent());
        assertEquals("Zero-day disclosed", found.get().getTitle());
    }

    @Test
    void postgresFindNearestByFieldVectorRanksByRealSimilarity() {
        Article security = postgresRepository.save(newArticle("Zero-day disclosed in widely used TLS library",
                "Researchers disclosed a critical vulnerability prompting an emergency patch cycle."));
        postgresRepository.save(newArticle("Simple weeknight pasta recipes",
                "Quick, easy pasta dishes you can make in under thirty minutes on a busy weeknight."));
        postgresRepository.save(newArticle("Local team advances to championship game",
                "A dramatic overtime victory secured the local team a spot in next week's championship."));

        EmbeddingVector reference = ((JavAIVectorizable) security).fieldVector("title");
        List<Article> nearest = postgresRepository.findNearestByTitleVector(reference, 1);

        assertEquals(1, nearest.size());
        assertEquals(security.getId(), nearest.get(0).getId(),
                "the article whose own title produced the reference vector must rank nearest to itself");
    }

    @Test
    void postgresFindNearestByVectorAndSummaryVectorAlsoWork() {
        Article article = postgresRepository.save(newArticle("Combined and summary vector test",
                "Proves the whole-object vector() and summaryVector() query variants against Postgres."));
        JavAIVectorizable vectorizable = (JavAIVectorizable) article;

        List<Article> byVector = postgresRepository.findNearestByVector(vectorizable.vector(), 5);
        assertTrue(byVector.stream().anyMatch(a -> a.getId().equals(article.getId())));

        List<Article> bySummary = postgresRepository.findNearestBySummaryVector(vectorizable.summaryVector(), 5);
        assertTrue(bySummary.stream().anyMatch(a -> a.getId().equals(article.getId())));
    }

    // ---- Neo4j --------------------------------------------------------------------------------

    @Test
    void neo4jSaveAndFindByIdRoundTrips() {
        Article article = neo4jRepository.save(newArticle("Zero-day disclosed",
                "A critical vulnerability affecting a widely used TLS library was patched today."));
        assertTrue(article.getId() != null);

        Optional<Article> found = neo4jRepository.findById(article.getId());
        assertTrue(found.isPresent());
        assertEquals("Zero-day disclosed", found.get().getTitle());
    }

    @Test
    void neo4jFindNearestByFieldVectorRanksByRealSimilarity() {
        Article security = neo4jRepository.save(newArticle("Zero-day disclosed in widely used TLS library",
                "Researchers disclosed a critical vulnerability prompting an emergency patch cycle."));
        neo4jRepository.save(newArticle("Simple weeknight pasta recipes",
                "Quick, easy pasta dishes you can make in under thirty minutes on a busy weeknight."));
        neo4jRepository.save(newArticle("Local team advances to championship game",
                "A dramatic overtime victory secured the local team a spot in next week's championship."));

        EmbeddingVector reference = ((JavAIVectorizable) security).fieldVector("title");
        List<Article> nearest = neo4jRepository.findNearestByTitleVector(reference, 1);

        assertEquals(1, nearest.size());
        assertEquals(security.getId(), nearest.get(0).getId(),
                "the article whose own title produced the reference vector must rank nearest to itself");
    }

    /**
     * Neo4j-specific: {@code featuredComment} is a real {@code @Summary} field on this project's own
     * {@link Article}, not a flat test fixture -- proves it becomes a genuine graph relationship, not just
     * a property, when persisted for real.
     */
    @Test
    void neo4jPersistsSummaryReferenceAsARealRelationship() {
        Article article = newArticle("Relationship test", "Proves featuredComment becomes a real edge.");
        Comment featured = article.getFeaturedComment();
        neo4jRepository.save(article);

        try (Driver driver = GraphDatabase.driver(MonolithicInfrastructure.neo4jUri(),
                AuthTokens.basic("neo4j", "javai12345"));
                var session = driver.session()) {
            long relationshipCount = session.run(
                    "MATCH (a:Article {id: $articleId})-[:FEATURED_COMMENT]->(c:Comment {id: $commentId}) RETURN count(*) AS c",
                    Values.parameters("articleId", article.getId().toString(), "commentId", featured.getId().toString()))
                    .single().get("c").asLong();
            assertEquals(1, relationshipCount,
                    "featuredComment must be persisted as a real :FEATURED_COMMENT relationship to its own Comment node");
        }
    }
}
