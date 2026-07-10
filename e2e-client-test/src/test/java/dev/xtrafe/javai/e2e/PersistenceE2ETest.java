package dev.xtrafe.javai.e2e;

import dev.xtrafe.javai.e2e.domain.Article;
import dev.xtrafe.javai.e2e.domain.ArticleRepository;
import dev.xtrafe.javai.e2e.domain.Attachment;
import dev.xtrafe.javai.e2e.domain.Comment;
import dev.xtrafe.javai.e2e.environment.JavAIEnvironment;
import dev.xtrafe.javai.e2e.environment.MonolithicContainer;
import dev.xtrafe.javai.persistence.JavAIPI;
import dev.xtrafe.javai.vector.EmbeddingVector;
import dev.xtrafe.javai.model.JavAIVectorizable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.neo4j.driver.Values;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Persistence Bridge, end to end: real embeddings (via {@code JavAIEnvironment}'s Ollama, same as
 * {@link ArticleGraphEmbeddingE2ETest}), real weaving, and this project's own {@link Article}/
 * {@link Comment} domain -- not {@code javai-persistence}'s own flat, non-relational test fixture --
 * saved into and queried back out of both real backends the monolithic container provides.
 *
 * <p>A repository proxy is bound to whichever backend was configured at the moment
 * {@link JavAIPI#repository(Class)} created it -- switching {@link JavAIPI#configurePersistence} afterward
 * doesn't retroactively affect an already-created proxy. That's what lets {@code JavAIEnvironment} build
 * both a Postgres-backed and a Neo4j-backed {@code ArticleRepository} once, up front, each fully
 * independent, for this test class (and every other) to share.
 */
class PersistenceE2ETest {

    private static ArticleRepository postgresRepository;
    private static ArticleRepository neo4jRepository;

    @BeforeAll
    static void configureProviderAndRepositories() {
        JavAIEnvironment.ensureRunning();
        postgresRepository = JavAIEnvironment.postgresArticleRepository();
        neo4jRepository = JavAIEnvironment.neo4jArticleRepository();
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
     * The full object graph -- {@code featuredComment}, the {@code comments} list, {@code draftComment},
     * {@code attachment} -- round-trips through Postgres, not just {@code title}/{@code body}.
     * {@code featuredComment}/{@code draftComment}/{@code attachment} are real {@code @OneToOne}s;
     * {@code comments} goes through {@code javai-persistence}'s own collection-membership mechanism (see
     * {@code RepositoryBackendHibernatePostgres}'s javadoc) since {@code JavAIArrayList} can't be a native
     * Hibernate collection field.
     */
    @Test
    void postgresFullObjectGraphRoundTrips() {
        Article article = fullArticle("Full graph test (Postgres)");
        Article saved = postgresRepository.save(article);

        Article reloaded = postgresRepository.findById(saved.getId()).orElseThrow();
        assertEquals("first take: Full graph test (Postgres)", reloaded.getFeaturedComment().getText());
        assertEquals(2, reloaded.getComments().size());
        assertTrue(reloaded.getComments().stream().anyMatch(c -> c.getText().equals("first listed comment")));
        assertTrue(reloaded.getComments().stream().anyMatch(c -> c.getText().equals("second listed comment")));
        assertNotNull(reloaded.getDraftComment(), "draftComment must round-trip as a real @OneToOne");
        assertEquals("an unpublished draft", reloaded.getDraftComment().getText());
        assertNotNull(reloaded.getAttachment(), "attachment must round-trip as a real @OneToOne");
        assertEquals("report.pdf", reloaded.getAttachment().getFilename());
    }

    /**
     * {@code relatedComments} (a {@code JavAILinkedHashMap<String, Comment>}, not {@code @Summary} -- see
     * {@code Article}'s own javadoc) round-trips through the same {@code javai_collection_members} table as
     * {@code comments}, this time with {@code member_key} populated.
     */
    @Test
    void postgresJavAILinkedHashMapFieldRoundTrips() {
        Article article = newArticle("Map field test (Postgres)", "Body text for the map field test.");
        article.getRelatedComments().put("first", new Comment("erin", "first related comment"));
        article.getRelatedComments().put("second", new Comment("frank", "second related comment"));

        Article saved = postgresRepository.save(article);
        Article reloaded = postgresRepository.findById(saved.getId()).orElseThrow();

        assertEquals(2, reloaded.getRelatedComments().size());
        assertEquals("first related comment", reloaded.getRelatedComments().get("first").getText());
        assertEquals("second related comment", reloaded.getRelatedComments().get("second").getText());
    }

    /**
     * Same as {@link #postgresJavAILinkedHashMapFieldRoundTrips()}, against Neo4j: each map entry becomes a
     * relationship with the original key stored as a {@code mapKey} relationship property, read back on
     * hydration to reconstruct {@code relatedComments} with both keys and values intact -- proves the fix
     * for a real, confirmed gap (Neo4j's relationship hydration used to only handle {@code Collection}-typed
     * fields, and never persisted map keys at all; see {@code RepositoryBackendNeo4j}'s own javadoc).
     */
    @Test
    void neo4jJavAILinkedHashMapFieldRoundTrips() {
        Article article = newArticle("Map field test (Neo4j)", "Body text for the map field test.");
        article.getRelatedComments().put("first", new Comment("erin", "first related comment"));
        article.getRelatedComments().put("second", new Comment("frank", "second related comment"));

        Article saved = neo4jRepository.save(article);
        Article reloaded = neo4jRepository.findById(saved.getId()).orElseThrow();

        assertEquals(2, reloaded.getRelatedComments().size());
        assertEquals("first related comment", reloaded.getRelatedComments().get("first").getText());
        assertEquals("second related comment", reloaded.getRelatedComments().get("second").getText());
    }

    /** Same as {@link #postgresFullObjectGraphRoundTrips()}, against Neo4j's reflective relationship
     *  mapping instead of Postgres's collection-membership table. */
    @Test
    void neo4jFullObjectGraphRoundTrips() {
        Article article = fullArticle("Full graph test (Neo4j)");
        Article saved = neo4jRepository.save(article);

        Article reloaded = neo4jRepository.findById(saved.getId()).orElseThrow();
        assertEquals("first take: Full graph test (Neo4j)", reloaded.getFeaturedComment().getText());
        assertEquals(2, reloaded.getComments().size());
        assertTrue(reloaded.getComments().stream().anyMatch(c -> c.getText().equals("first listed comment")));
        assertTrue(reloaded.getComments().stream().anyMatch(c -> c.getText().equals("second listed comment")));
        assertNotNull(reloaded.getDraftComment(), "draftComment must round-trip as a real relationship");
        assertEquals("an unpublished draft", reloaded.getDraftComment().getText());
        assertNotNull(reloaded.getAttachment(), "attachment must round-trip as a real relationship");
        assertEquals("report.pdf", reloaded.getAttachment().getFilename());
    }

    /**
     * The same Article instance, saved to *both* backends -- proves an object isn't backend-exclusive:
     * whichever repository proxy you saved it through, the other backend's own copy is independently
     * correct and independently queryable, sharing only the {@code UUID} identity.
     */
    @Test
    void sameArticleCanBePersistedSimultaneouslyToBothBackends() {
        Article article = fullArticle("Dual-persisted article");

        Article postgresManaged = postgresRepository.save(article);
        Article neo4jManaged = neo4jRepository.save(article);
        assertEquals(postgresManaged.getId(), neo4jManaged.getId(), "both saves share the same identity");

        Article fromPostgres = postgresRepository.findById(article.getId()).orElseThrow();
        Article fromNeo4j = neo4jRepository.findById(article.getId()).orElseThrow();

        assertEquals("Dual-persisted article", fromPostgres.getTitle());
        assertEquals("Dual-persisted article", fromNeo4j.getTitle());
        assertEquals(2, fromPostgres.getComments().size());
        assertEquals(2, fromNeo4j.getComments().size());
        assertEquals(fromPostgres.getFeaturedComment().getText(), fromNeo4j.getFeaturedComment().getText());
    }

    private static Article fullArticle(String title) {
        Article article = newArticle(title, "Body text for " + title + ".");
        article.getComments().add(new Comment("bob", "first listed comment"));
        article.getComments().add(new Comment("carol", "second listed comment"));
        article.setDraftComment(new Comment("dave", "an unpublished draft"));
        article.setAttachment(new Attachment("report.pdf"));
        return article;
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

        try (Driver driver = GraphDatabase.driver(MonolithicContainer.neo4jUri(),
                AuthTokens.basic(MonolithicContainer.NEO4J_USERNAME, MonolithicContainer.NEO4J_PASSWORD));
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
