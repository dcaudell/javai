package dev.xtrafe.javai.e2e;

import dev.xtrafe.javai.e2e.domain.Comment;
import dev.xtrafe.javai.vector.EmbeddingVector;
import dev.xtrafe.javai.vector.JavAIDirtyTracking;
import dev.xtrafe.javai.model.JavAILinkedHashMap;
import dev.xtrafe.javai.model.JavAILinkedHashSet;
import dev.xtrafe.javai.model.JavAIList;
import dev.xtrafe.javai.model.JavAIRuntime;
import dev.xtrafe.javai.model.JavAIVectorizable;
import dev.xtrafe.javai.vector.LocalEmbeddingDefaults;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ArticleGraphEmbeddingE2ETest} already exercises {@code JavAIArrayList} exhaustively (via
 * {@code Article.comments}) against real embeddings. This covers the other two concrete
 * {@code javai-model} collection types the same way -- real, woven {@link Comment} objects, real
 * embeddings from {@link MonolithicInfrastructure}'s Ollama, not the fake providers
 * {@code javai-model}'s own hermetic collection tests use.
 */
class CollectionTypesE2ETest {

    @BeforeAll
    static void configureRealProvider() {
        JavAIRuntime.configureEmbeddingProvider(LocalEmbeddingDefaults.create(MonolithicInfrastructure.embeddingEndpoint()));
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
}
