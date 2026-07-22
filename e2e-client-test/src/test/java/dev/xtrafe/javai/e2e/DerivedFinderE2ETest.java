package dev.xtrafe.javai.e2e;

import dev.xtrafe.javai.e2e.domain.Article;
import dev.xtrafe.javai.e2e.domain.ArticleRepository;
import dev.xtrafe.javai.e2e.domain.Comment;
import dev.xtrafe.javai.e2e.domain.Place;
import dev.xtrafe.javai.e2e.domain.PlaceRepository;
import dev.xtrafe.javai.e2e.environment.JavAIEnvironment;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.geo.Circle;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.Metrics;
import org.springframework.data.geo.Point;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ordinary relational derived finders (OMI-138) and their OMI-141 extensions -- nested singular + to-many
 * traversal, collection emptiness, regex, and geo {@code Near}/{@code Within} -- exercised end to end against
 * all three real backends with real weaving (the {@code javai-substrate} agent) and this project's own
 * {@link Article}/{@link Comment} domain, plus a non-vectorized {@link Place} for geo. The same assertions run
 * on Postgres, Neo4j, and MongoDB, proving the finders behave identically regardless of backend -- the client
 * writes one repository interface and never sees the per-backend translation (Criteria / Cypher / driver
 * filter) underneath.
 *
 * <p>Every entity is created with a per-invocation unique suffix so the assertions are robust against the
 * shared containers' seeded/leftover data (each query is scoped to just this run's own rows). Derived finders
 * use ordinary reads (not {@code $vectorSearch}), so -- unlike {@link PersistenceE2ETest}'s vector queries --
 * no near-real-time-index polling is needed even on MongoDB.
 */
class DerivedFinderE2ETest {

    @BeforeAll
    static void ensureEnvironment() {
        JavAIEnvironment.ensureRunning();
    }

    // Three real cities (x = longitude, y = latitude): PDX->SEA ~233 km, PDX->SFO ~860 km.
    private static final Point PORTLAND = new Point(-122.6765, 45.5231);
    private static final Point SEATTLE = new Point(-122.3321, 47.6062);
    private static final Point SAN_FRANCISCO = new Point(-122.4194, 37.7749);

    @Test
    void postgresDerivedFinders() {
        assertDerivedFinders(JavAIEnvironment.postgresArticleRepository(), JavAIEnvironment.postgresPlaceRepository());
    }

    @Test
    void neo4jDerivedFinders() {
        assertDerivedFinders(JavAIEnvironment.neo4jArticleRepository(), JavAIEnvironment.neo4jPlaceRepository());
    }

    @Test
    void mongoDerivedFinders() {
        assertDerivedFinders(JavAIEnvironment.mongoArticleRepository(), JavAIEnvironment.mongoPlaceRepository());
    }

    private static void assertDerivedFinders(ArticleRepository articles, PlaceRepository places) {
        String tag = UUID.randomUUID().toString().substring(0, 8);

        // An article with a featured comment and a to-many comment, plus a comment-less one.
        String withTitle = "e2e-derived-alpha-" + tag;
        String emptyTitle = "e2e-derived-empty-" + tag;
        String featuredAuthor = "featured-" + tag;
        String commenter = "commenter-" + tag;

        Article withComments = new Article(withTitle,
                "A critical vulnerability affecting a widely used TLS library was patched today.");
        withComments.setFeaturedComment(new Comment(featuredAuthor, "editor's pick"));
        withComments.getComments().add(new Comment(commenter, "great write-up, thanks"));
        Article saved = articles.save(withComments);
        Article empty = articles.save(new Article(emptyTitle, "A short note with no discussion."));

        // Scalar equality / exists / contains / regex.
        assertTrue(containsId(articles.findByTitle(withTitle), saved.getId()));
        assertTrue(articles.existsByTitle(withTitle));
        assertFalse(articles.existsByTitle("no-such-title-" + tag));
        assertTrue(containsId(articles.findByTitleContaining("derived-alpha-" + tag), saved.getId()));
        assertTrue(containsId(articles.findByTitleRegex("^e2e-derived-alpha-" + tag + "$"), saved.getId()));

        // Nested filter through the singular featuredComment association (an inherited @MappedSuperclass field).
        assertTrue(containsId(articles.findByFeaturedCommentAuthor(featuredAuthor), saved.getId()));
        assertFalse(containsId(articles.findByFeaturedCommentAuthor("nobody-" + tag), saved.getId()));

        // Nested filter through the to-many comments JavAI collection.
        assertTrue(containsId(articles.findByCommentsAuthor(commenter), saved.getId()));

        // Collection emptiness: the comment-less article is empty, the other is not.
        assertTrue(containsId(articles.findByCommentsIsEmpty(), empty.getId()));
        assertFalse(containsId(articles.findByCommentsIsEmpty(), saved.getId()));
        assertTrue(articles.countByCommentsIsNotEmpty() > 0);

        assertGeoFinders(places, tag);
    }

    private static void assertGeoFinders(PlaceRepository places, String tag) {
        String pdx = "PDX-" + tag;
        String sea = "SEA-" + tag;
        String sfo = "SFO-" + tag;
        places.save(new Place(pdx, PORTLAND));
        places.save(new Place(sea, SEATTLE));
        places.save(new Place(sfo, SAN_FRANCISCO));

        // Near 50 km of Portland -> only PDX; near 400 km -> PDX + SEA (SFO is ~860 km).
        assertEquals(List.of(pdx),
                mine(places.findByLocationNear(PORTLAND, new Distance(50, Metrics.KILOMETERS)), tag));
        assertEquals(List.of(pdx, sea),
                mine(places.findByLocationNear(PORTLAND, new Distance(400, Metrics.KILOMETERS)), tag));
        assertEquals(List.of(pdx),
                mine(places.findByLocationWithin(new Circle(PORTLAND, new Distance(100, Metrics.KILOMETERS))), tag));

        // Plain relational finders over the same non-vectorized entity.
        assertEquals(List.of(pdx), mine(places.findByName(pdx), tag));
        assertEquals(List.of(pdx), mine(places.findByNameRegex("^PDX-" + tag + "$"), tag));
    }

    private static boolean containsId(List<Article> articles, UUID id) {
        return articles.stream().anyMatch(a -> a.getId().equals(id));
    }

    /** The names of just this run's own places (suffix-scoped), sorted -- so leftover rows from other runs in
     *  the shared container can't affect the assertion. */
    private static List<String> mine(List<Place> places, String tag) {
        return places.stream().map(Place::getName).filter(n -> n.endsWith("-" + tag)).sorted().toList();
    }
}
