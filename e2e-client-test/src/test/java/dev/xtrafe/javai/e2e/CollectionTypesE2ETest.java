package dev.xtrafe.javai.e2e;

import dev.xtrafe.javai.collections.JavAIVectorIndex;
import dev.xtrafe.javai.collections.VectorIndex;
import dev.xtrafe.javai.e2e.domain.Article;
import dev.xtrafe.javai.e2e.domain.Comment;
import dev.xtrafe.javai.e2e.environment.JavAIEnvironment;
import dev.xtrafe.javai.vector.EmbeddingVector;
import dev.xtrafe.javai.vector.Ranked;
import dev.xtrafe.javai.vector.JavAIDirtyTracking;
import dev.xtrafe.javai.model.JavAILinkedHashMap;
import dev.xtrafe.javai.model.JavAILinkedHashSet;
import dev.xtrafe.javai.model.JavAIList;
import dev.xtrafe.javai.model.JavAIVectorizable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ArticleGraphEmbeddingE2ETest} already exercises {@code JavAIArrayList} exhaustively (via
 * {@code Article.comments}) against real embeddings. This covers the other two concrete
 * {@code javai-model} collection types the same way -- real, woven {@link Comment} objects, real
 * embeddings from {@code JavAIEnvironment}'s Ollama, not the fake providers {@code javai-model}'s own
 * hermetic collection tests use.
 */
class CollectionTypesE2ETest {

    @BeforeAll
    static void configureRealProvider() {
        JavAIEnvironment.ensureRunning();
    }

    @Test
    void linkedHashSetRanksRealMembersBySimilarityAndPropagatesDirty() {
        Comment security = new Comment("alice", "A critical zero-day vulnerability was disclosed today.");
        Comment cooking = new Comment("bob", "Quick pasta recipes for a busy weeknight dinner.");
        Comment sports = new Comment("carol", "The local team won a dramatic overtime victory.");

        JavAILinkedHashSet<Comment> set = new JavAILinkedHashSet<>();
        set.add(security);
        set.add(cooking);
        set.add(sports);

        EmbeddingVector reference = ((JavAIVectorizable) security).vector();
        JavAIList<Comment> nearest = set.nearestN(reference, 1);
        assertEquals(1, nearest.size());
        assertEquals(security, nearest.get(0),
                "the comment whose own vector produced the reference must rank nearest to itself");

        JavAIList<Comment> onlyClose = set.filterByMinSimilarity(reference, 0.999);
        assertTrue(onlyClose.contains(security));

        JavAIDirtyTracking setDirtyTracking = (JavAIDirtyTracking) set;
        set.summaryVector();
        assertFalse(setDirtyTracking.isSummaryDirty());
        cooking.setText("Updated: an even quicker pasta recipe for busy weeknights.");
        assertTrue(setDirtyTracking.isSummaryDirty(), "mutating a member must dirty the set that holds it");
    }

    @Test
    void linkedHashMapRanksRealValuesBySimilarityAndPropagatesDirty() {
        Comment security = new Comment("alice", "A critical zero-day vulnerability was disclosed today.");
        Comment cooking = new Comment("bob", "Quick pasta recipes for a busy weeknight dinner.");
        Comment sports = new Comment("carol", "The local team won a dramatic overtime victory.");

        JavAILinkedHashMap<String, Comment> map = new JavAILinkedHashMap<>();
        map.put("security", security);
        map.put("cooking", cooking);
        map.put("sports", sports);

        EmbeddingVector reference = ((JavAIVectorizable) security).vector();
        JavAIList<Comment> ranked = map.sortByCosineDistance(reference);
        assertEquals(3, ranked.size());
        assertEquals(security, ranked.get(0),
                "the comment whose own vector produced the reference must rank nearest to itself");

        JavAIDirtyTracking mapDirtyTracking = (JavAIDirtyTracking) map;
        map.summaryVector();
        assertFalse(mapDirtyTracking.isSummaryDirty());
        sports.setText("Updated: the local team advances to the championship.");
        assertTrue(mapDirtyTracking.isSummaryDirty(), "mutating a value must dirty the map that holds it");
    }

    /**
     * {@code VectorIndex} narrowing over a genuinely mixed, woven, real-embedding corpus (OMI-460).
     *
     * <p>The index holds {@link Article}s and {@link Comment}s together, which is what makes narrowing worth
     * having: the reference is one comment's own vector, so the comments occupy the head of the ranking and
     * "the nearest article" is unreachable without either narrowing or an over-fetch. The persistence-backed
     * tag indexes push the same narrowing into a query; this is the in-memory realization of the same
     * contract, and it is the one an adopter reaches for when the candidates are already in hand.
     */
    @Test
    void vectorIndexNarrowsAMixedCorpusToOneTypeAndKeepsTheScore() {
        Comment aboutSecurity = new Comment("alice", "A critical zero-day vulnerability was disclosed today.");
        Comment alsoSecurity = new Comment("bob", "The zero-day was patched in an emergency release.");
        Article securityArticle = new Article("Zero-day disclosed in a TLS library",
                "Researchers disclosed a critical vulnerability prompting an emergency patch cycle.");
        Article cookingArticle = new Article("Simple weeknight pasta recipes",
                "Quick, easy pasta dishes you can make in under thirty minutes on a busy weeknight.");

        JavAIVectorIndex<Object> index = new JavAIVectorIndex<>();
        index.add(aboutSecurity);
        index.add(alsoSecurity);
        index.add(securityArticle);
        index.add(cookingArticle);

        EmbeddingVector reference = ((JavAIVectorizable) aboutSecurity).vector();

        assertEquals(2, index.nearestN(reference, 2).size());
        assertTrue(index.nearestN(reference, 2).stream().allMatch(Comment.class::isInstance),
                "a comment's own vector puts the comments at the head -- so the top 2 hold no article at all");

        VectorIndex<Object> articlesOnly = index.ofType(Article.class);
        assertEquals(2, articlesOnly.size());
        List<Object> nearestArticles = List.copyOf(articlesOnly.nearestN(reference, 2));
        assertEquals(securityArticle, nearestArticles.get(0),
                "narrowed, the on-topic article is reachable and ranks first among articles");
        assertEquals(cookingArticle, nearestArticles.get(1));

        List<Ranked<Object>> ranked = articlesOnly.nearestNRanked(reference, 2);
        assertEquals(securityArticle, ranked.get(0).entity());
        assertTrue(ranked.get(0).similarity() > ranked.get(1).similarity(),
                "real embeddings: the security article is genuinely nearer a security comment than the "
                        + "cooking one is, and the ranked form is where a caller can see by how much");
        assertEquals(1.0 - ranked.get(0).similarity(), ranked.get(0).distance(), 1e-9);

        assertTrue(index.ofType(Article.class).ofType(Comment.class).nearestN(reference, 5).isEmpty(),
                "narrowing intersects, so these two leave nothing");
    }

}
