package dev.xtrafe.javai.e2e;

import dev.xtrafe.javai.collections.JavAIKnowledgeGraph;
import dev.xtrafe.javai.collections.SubgraphResult;
import dev.xtrafe.javai.e2e.domain.Article;
import dev.xtrafe.javai.e2e.domain.Attachment;
import dev.xtrafe.javai.e2e.domain.Attribution;
import dev.xtrafe.javai.e2e.domain.Comment;
import dev.xtrafe.javai.e2e.domain.RelatesTo;
import dev.xtrafe.javai.runtime.EmbeddingVector;
import dev.xtrafe.javai.runtime.JavAIDirtyTracking;
import dev.xtrafe.javai.runtime.JavAIList;
import dev.xtrafe.javai.runtime.JavAIRuntime;
import dev.xtrafe.javai.runtime.JavAIVectorizable;
import dev.xtrafe.javai.runtime.LocalEmbeddingDefaults;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real end-to-end proof, not a hermetic one: a genuine object graph (a single {@code @Summary}
 * reference plus a {@code @Summary} collection), real embeddings from a live container (not
 * {@code javai-runtime}/{@code javai-agent}'s own fake providers), and client code that never writes
 * {@code implements JavAIVectorizable} by hand -- {@code Article}/{@code Comment} are plain annotated
 * classes, built with plain {@code new}. Phase 0 has no real compiler yet (that's {@code javaic},
 * Phase 1+), so there's no compile-time-checked {@code article.vector()} call the way the whitepaper's own
 * examples show -- this test calls through the woven-in {@code JavAIVectorizable}/{@code JavAIDirtyTracking}
 * interfaces instead, which the weaver makes every annotated class genuinely implement at load time. That
 * cast is the one honest concession to "no compiler yet"; everything else is exactly what it will look
 * like once {@code javaic} exists.
 *
 * <p>Domain mirrors doc/spec/end-to-end-example.md's own Article/Comment worked example. Requires Docker;
 * first run builds {@link MonolithicInfrastructure}'s image (slow, one-time) -- see README.md.
 *
 * <p><b>The embedding provider/model this test runs against is fixed by {@link MonolithicInfrastructure}</b>,
 * not chosen per-platform here: the monolithic container it starts bakes in Ollama specifically (with the
 * reference model, {@code Qwen/Qwen3-Embedding-0.6B} / {@code qwen3-embedding:0.6b}, §4.5.1), and forces
 * {@link LocalEmbeddingDefaults} to agree via its override property, so {@code modelLabel()}/
 * {@code create(...)} below still resolve correctly without this test itself carrying any
 * platform-specific logic. See {@code MonolithicInfrastructure}'s javadoc for why (TEI's Candle backend has
 * a confirmed, unresolved upstream bug running this model on CPU -- doc/spec/vector-core.md's "Provider
 * selection across platforms").
 *
 * <p>The weaver itself is installed by {@link JavAIWeavingLauncherSessionListener}, not here -- see that
 * class's javadoc for why {@code @BeforeAll} turned out not to be early enough.
 */
class ArticleGraphEmbeddingE2ETest {

    private static final int EXPECTED_DIMS = 1024; // Qwen3-Embedding-0.6B's output dimensionality

    @BeforeAll
    static void configureRealProvider() {
        JavAIRuntime.configureEmbeddingProvider(LocalEmbeddingDefaults.create(MonolithicInfrastructure.embeddingEndpoint()));
    }

    private Article article;
    private Comment featured;
    private Comment firstListedComment;
    private Comment secondListedComment;

    @BeforeEach
    void buildGraph() {
        article = new Article(
                "Zero-day disclosed in widely used TLS library",
                "Researchers today disclosed a critical vulnerability affecting a widely used open-source "
                        + "TLS implementation, prompting an emergency patch cycle across the industry.");

        featured = new Comment("alice", "This is going to be a nightmare for the supply chain.");
        article.setFeaturedComment(featured);

        firstListedComment = new Comment("bob", "Already seeing scanners probing for this in the wild.");
        secondListedComment = new Comment("carol", "Our vendor still hasn't shipped a fix.");
        article.getComments().add(firstListedComment);
        article.getComments().add(secondListedComment);
    }

    @Test
    void fieldVectorsAreRealAndDistinct() {
        JavAIVectorizable articleV = vectorizable(article);
        EmbeddingVector title = articleV.fieldVector("title");
        EmbeddingVector body = articleV.fieldVector("body");

        assertEquals(EXPECTED_DIMS, title.dims());
        assertEquals(EXPECTED_DIMS, body.dims());
        assertEquals(LocalEmbeddingDefaults.modelLabel(), title.modelId());
        assertTrue(containsNonZero(title.values()), "a real embedding should not be all zeros");
        assertNotEquals(toList(title.values()), toList(body.values()),
                "title and body have different text, so must embed to different vectors");
    }

    @Test
    void vectorLifecycleIsLazyAndReflectsMutation() {
        JavAIVectorizable articleV = vectorizable(article);
        JavAIDirtyTracking articleD = dirtyTracking(article);

        assertTrue(articleD.isFieldDirty(), "never-read object starts dirty");
        EmbeddingVector before = articleV.vector();
        assertFalse(articleD.isFieldDirty(), "reading vector() must clear FieldDirty");

        EmbeddingVector reread = articleV.vector();
        assertEquals(before, reread, "a repeat clean read must return the cached vector, not recompute");

        article.setTitle("Zero-day disclosed in widely used TLS library -- UPDATED");
        assertTrue(articleD.isFieldDirty(), "mutating a @Vectorize field must mark FieldDirty");

        EmbeddingVector after = articleV.vector();
        assertNotEquals(toList(before.values()), toList(after.values()), "a changed field must change vector()");
    }

    @Test
    void summaryVectorPropagatesThroughSingleReferenceAndCollection() {
        JavAIVectorizable articleV = vectorizable(article);
        JavAIDirtyTracking articleD = dirtyTracking(article);

        EmbeddingVector initial = articleV.summaryVector();
        assertFalse(articleD.isSummaryDirty());

        featured.setText("Updated: this is worse than we first thought.");
        assertTrue(articleD.isSummaryDirty(),
                "mutating the single @Summary reference (featuredComment) must propagate to the article");
        EmbeddingVector afterFeaturedMutation = articleV.summaryVector();
        assertNotEquals(toList(initial.values()), toList(afterFeaturedMutation.values()));

        secondListedComment.setText("Update: our vendor shipped a fix an hour ago.");
        assertTrue(articleD.isSummaryDirty(),
                "mutating a comment inside the @Summary collection must also propagate to the article");
        EmbeddingVector afterCollectionMutation = articleV.summaryVector();
        assertNotEquals(toList(afterFeaturedMutation.values()), toList(afterCollectionMutation.values()));
    }

    @Test
    void queryFindsAllCommentsReachableFromTheArticle() {
        JavAIVectorizable articleV = vectorizable(article);
        JavAIList<Comment> found = articleV.query(articleV.fieldVector("body"), Comment.class);

        assertEquals(3, found.size(), "featuredComment + the two comments in the list");
        assertTrue(found.contains(featured));
        assertTrue(found.contains(firstListedComment));
        assertTrue(found.contains(secondListedComment));
    }

    @Test
    void similarityToItselfIsApproximatelyOne() {
        JavAIVectorizable articleV = vectorizable(article);
        assertEquals(1.0, articleV.similarityTo(articleV), 1e-4);
    }

    @Test
    void inheritedVectorizeFieldSetterIsWovenAndWorksThroughAncestorTypedReference() {
        JavAIVectorizable featuredV = vectorizable(featured);
        JavAIDirtyTracking featuredD = dirtyTracking(featured);

        EmbeddingVector before = featuredV.vector();
        assertFalse(featuredD.isFieldDirty());

        // Reference statically typed as the plain, unwoven ancestor -- Attribution -- not Comment. Java
        // resolves instance method calls virtually against the runtime type, so this must still reach the
        // weaver's synthesized override on Comment (author's setter is declared only on Attribution) and
        // mark the object dirty, exactly as JavAIWeaver's own hermetic test proves in isolation.
        Attribution featuredAsAttribution = featured;
        featuredAsAttribution.setAuthor("alice, updated");
        assertTrue(featuredD.isFieldDirty(),
                "mutating an inherited @Vectorize field, even via an ancestor-typed reference, must mark FieldDirty");

        EmbeddingVector after = featuredV.vector();
        assertNotEquals(toList(before.values()), toList(after.values()),
                "changing the inherited author field must change vector()");
    }

    @Test
    void vectorizeIgnoredFieldNeverAffectsVectorEvenWithRealEmbeddings() {
        JavAIVectorizable featuredV = vectorizable(featured);

        featured.setInternalModerationNote("flagged: needs review");
        EmbeddingVector first = featuredV.vector();

        // Change only the ignored field, then force a recompute via the *other*, wired field -- mutating
        // internalModerationNote alone never marks FieldDirty at all, so vector() would just return the
        // same cached value regardless of whether the exclusion actually works, proving nothing.
        featured.setInternalModerationNote("cleared after review");
        featured.setText(featured.getText());
        EmbeddingVector second = featuredV.vector();

        assertEquals(toList(first.values()), toList(second.values()),
                "an @VectorizeIgnore'd field's value must never affect vector()'s canonical text");
    }

    @Test
    void searchVisibilityPrivateFieldBlocksTraversalIntoDraftComment() {
        Comment draft = new Comment("dave", "unpublished draft note, should never be discoverable");
        article.setDraftComment(draft);

        JavAIVectorizable articleV = vectorizable(article);
        JavAIList<Comment> found = articleV.query(articleV.fieldVector("body"), Comment.class);

        assertEquals(3, found.size(), "draftComment must not be reachable via query() at all");
        assertFalse(found.contains(draft),
                "a field-level @SearchVisibility(PRIVATE) must block traversal entirely");
    }

    @Test
    void searchVisibilityPrivateTypeBlocksMatchingButNotTraversal() {
        Attachment attachment = new Attachment("incident-report.pdf");
        Comment throughAttachment = new Comment("erin", "found via the attachment, not directly on the article");
        attachment.setRelatedComment(throughAttachment);
        article.setAttachment(attachment);

        JavAIVectorizable articleV = vectorizable(article);
        EmbeddingVector reference = articleV.fieldVector("body");

        JavAIList<Attachment> attachmentMatches = articleV.query(reference, Attachment.class);
        assertTrue(attachmentMatches.isEmpty(),
                "a type-level @SearchVisibility(PRIVATE) class must never be returned as a query() match");

        JavAIList<Comment> commentMatches = articleV.query(reference, Comment.class);
        assertEquals(4, commentMatches.size(),
                "featuredComment + the two listed comments + the one reachable through Attachment");
        assertTrue(commentMatches.contains(throughAttachment),
                "traversal must continue through a type-hidden node to reach its own descendants");
    }

    @Test
    void knowledgeGraphNearestSubgraphRanksByRealEmbeddingSimilarity() {
        Article cooking = new Article("Simple Weeknight Pasta Recipes",
                "A collection of quick, easy pasta dishes you can make in under thirty minutes on a busy weeknight.");
        Article sports = new Article("Local Team Advances to Championship Game",
                "After a dramatic overtime victory, the local basketball team secured a spot in next week's championship.");

        JavAIKnowledgeGraph<Article, RelatesTo> graph = new JavAIKnowledgeGraph<>();
        graph.addNode(article);
        graph.addNode(cooking);
        graph.addNode(sports);
        graph.addEdge(article, cooking, new RelatesTo("published same day"));
        graph.addEdge(article, sports, new RelatesTo("published same day"));

        EmbeddingVector securityReference = vectorizable(article).vector();
        SubgraphResult<Article, RelatesTo> nearest = graph.nearestSubgraph(securityReference, 1, 1);

        assertEquals(1.0, nearest.scoreOf(article), 1e-4,
                "the article must score ~1.0 against a reference vector taken from its own vector()");
        assertTrue(nearest.nodes().contains(article));
        assertEquals(0, nearest.hopsFrom(article, article));

        // 1-hop expansion from the single nearest origin (the article) must pull in its structural
        // neighbors -- graph structure, not similarity ranking, drives their inclusion here.
        assertTrue(nearest.nodes().contains(cooking), "cooking must be reachable one hop from the article");
        assertTrue(nearest.nodes().contains(sports), "sports must be reachable one hop from the article");
        assertEquals(1, nearest.hopsFrom(cooking, article));
        assertEquals(1, nearest.hopsFrom(sports, article));
    }

    /**
     * Every {@code @JavAIVectorizable} class genuinely implements this interface once woven -- this cast
     * always succeeds at runtime. Only needed because Phase 0 has no real compiler to make the call sites
     * above type-check without it; see the class javadoc.
     */
    private static JavAIVectorizable vectorizable(Object woven) {
        return (JavAIVectorizable) woven;
    }

    private static JavAIDirtyTracking dirtyTracking(Object woven) {
        return (JavAIDirtyTracking) woven;
    }

    private static boolean containsNonZero(float[] values) {
        for (float value : values) {
            if (value != 0f) {
                return true;
            }
        }
        return false;
    }

    /** Compares by value, not by reference/timestamp -- {@code EmbeddingVector} carries a computedAt. */
    private static List<Float> toList(float[] values) {
        List<Float> list = new ArrayList<>(values.length);
        for (float value : values) {
            list.add(value);
        }
        return list;
    }
}
