package dev.xtrafe.javai.e2e;

import dev.xtrafe.javai.collections.VectorIndex;
import dev.xtrafe.javai.e2e.domain.Article;
import dev.xtrafe.javai.e2e.domain.ArticleRepository;
import dev.xtrafe.javai.e2e.domain.Comment;
import dev.xtrafe.javai.e2e.domain.CommentRepository;
import dev.xtrafe.javai.e2e.environment.JavAIEnvironment;
import dev.xtrafe.javai.e2e.fixtures.ArticleFixtures;
import dev.xtrafe.javai.e2e.tagging.TagRepository;
import dev.xtrafe.javai.e2e.tagging.TagSetRepository;
import dev.xtrafe.javai.model.JavAILinkedHashMap;
import dev.xtrafe.javai.model.JavAILinkedHashSet;
import dev.xtrafe.javai.model.JavAIList;
import dev.xtrafe.javai.model.JavAIVectorizable;
import dev.xtrafe.javai.tagging.ClassificationResult;
import dev.xtrafe.javai.tagging.JavAITagging;
import dev.xtrafe.javai.tagging.Tag;
import dev.xtrafe.javai.tagging.TagSet;
import dev.xtrafe.javai.tagging.TaggableRef;
import dev.xtrafe.javai.vector.EmbeddingVector;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tagging, end to end: this project's own real, relational {@link Article}/{@link Comment} domain (both
 * {@code @Taggable}, not a flat test fixture), real embeddings, and -- for classification -- a real
 * {@code Cortex} (the same Ollama instance {@link CompletionE2ETest} exercises), against all three real
 * persistence backends the e2e infrastructure provides. {@code javai-tagging}'s own module tests already
 * prove the mechanism against a throwaway container per backend with a fake embedding provider and a fake
 * {@code Cortex}; this class's job is different -- proving the same mechanism composes correctly with a
 * real, already-populated multi-entity-type domain, real semantic embeddings, and a real LLM, none of which
 * that module's own tests exercise.
 *
 * <p>Organized in per-backend sections, matching {@link PersistenceE2ETest}'s own convention -- except each
 * tagging test method calls its own backend's {@code JavAIEnvironment.activateXxxTagging()} first: unlike
 * an {@code ArticleRepository} proxy (permanently bound to one backend at creation time), {@code
 * JavAITagging}'s own static facade always resolves against whichever backend was <em>most recently</em>
 * activated, so every method is self-contained regardless of what ran before it in the same JVM (see {@code
 * JavAIEnvironment}'s own javadoc for why).
 *
 * <p>Tag/TagSet content deliberately reuses real, already-proven-distinct real-world topics ({@code
 * ArticleFixtures}' own Cybersecurity/Cooking/Sports/Space, plus the same "zero-day"/"pasta"/"championship"
 * style content {@link PersistenceE2ETest} already relies on) rather than inventing new ad hoc text --
 * real embeddings are continuous, unlike the fake hash-based provider {@code javai-tagging}'s own tests use,
 * so a similarity/classification assertion needs genuinely well-separated real content to be reliable, not
 * two superficially-different phrases that might still embed close together.
 */
class TaggingE2ETest {

    private static ArticleRepository postgresArticles;
    private static ArticleRepository neo4jArticles;
    private static ArticleRepository mongoArticles;

    private static CommentRepository postgresComments;
    private static CommentRepository neo4jComments;
    private static CommentRepository mongoComments;

    private static TagRepository postgresTags;
    private static TagRepository neo4jTags;
    private static TagRepository mongoTags;

    private static TagSetRepository postgresTagSets;
    private static TagSetRepository neo4jTagSets;
    private static TagSetRepository mongoTagSets;

    @BeforeAll
    static void configure() {
        JavAIEnvironment.ensureRunning();
        postgresArticles = JavAIEnvironment.postgresArticleRepository();
        neo4jArticles = JavAIEnvironment.neo4jArticleRepository();
        mongoArticles = JavAIEnvironment.mongoArticleRepository();
        postgresComments = JavAIEnvironment.postgresCommentRepository();
        neo4jComments = JavAIEnvironment.neo4jCommentRepository();
        mongoComments = JavAIEnvironment.mongoCommentRepository();
        postgresTags = JavAIEnvironment.postgresTagRepository();
        neo4jTags = JavAIEnvironment.neo4jTagRepository();
        mongoTags = JavAIEnvironment.mongoTagRepository();
        postgresTagSets = JavAIEnvironment.postgresTagSetRepository();
        neo4jTagSets = JavAIEnvironment.neo4jTagSetRepository();
        mongoTagSets = JavAIEnvironment.mongoTagSetRepository();
    }

    private static Article findByTopic(List<Article> articles, ArticleFixtures.Topic topic) {
        return articles.stream()
                .filter(article -> ArticleFixtures.topicOf(article.getTitle()) == topic)
                .findFirst()
                .orElseThrow();
    }

    /** MongoDB Search's {@code $vectorSearch} index (what {@code tagSimilarityIndex()} queries against on
     *  that backend) updates near-real-time, not synchronously with the write -- same eventual-consistency
     *  gap {@link PersistenceE2ETest#awaitNearest} already documents for ordinary field/summary vectors.
     *  {@code taggedWith}/{@code hasTag}/{@code tagsOf} read the tagged document's own embedded array
     *  directly, an ordinary (immediately consistent) query, so only similarity-index queries need this. */
    private static JavAIList<TaggableRef> awaitContainsRef(Supplier<JavAIList<TaggableRef>> query, TaggableRef expected) {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(15));
        JavAIList<TaggableRef> result;
        while (true) {
            result = query.get();
            if (result.contains(expected) || Instant.now().isAfter(deadline)) {
                return result;
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return result;
            }
        }
    }

    // ---- Postgres -----------------------------------------------------------------------------

    @Test
    void postgresAddTagThenTagsOfRoundTripsOnArticleAndComment() {
        JavAIEnvironment.activatePostgresTagging();
        TagSet tagSet = postgresTagSets.save(new TagSet("postgres-roundtrip"));
        Tag urgent = postgresTags.save(new Tag(tagSet, "en", "Urgent"));
        Tag reviewed = postgresTags.save(new Tag(tagSet, "en", "Reviewed"));

        Article article = postgresArticles.save(new Article("Postgres tag round-trip article", "Body text."));
        Comment comment = postgresComments.save(new Comment("alice", "Postgres tag round-trip comment."));

        JavAITagging.addTag(article, urgent);
        JavAITagging.addTag(comment, urgent, 0.8);

        assertTrue(JavAITagging.hasTag(article, urgent));
        assertTrue(JavAITagging.hasTag(comment, urgent));
        assertFalse(JavAITagging.hasTag(article, reviewed));

        // tagsOf(...) already returns a JavAIArrayList<Tag> under the hood (JavAITagging's own return type);
        // re-keying it by slug into a JavAILinkedHashMap here composes a second concrete JavAI collection
        // type on top of that result, the same way this project's other collection-type tests do with
        // Comment values instead of Tag values (see CollectionTypesE2ETest).
        JavAILinkedHashMap<String, Tag> bySlug = new JavAILinkedHashMap<>();
        for (Tag tag : JavAITagging.tagsOf(article)) {
            bySlug.put(tag.getSlug(), tag);
        }
        assertEquals(1, bySlug.size());
        assertTrue(bySlug.containsKey("urgent"));

        JavAITagging.removeTag(article, urgent);
        assertFalse(JavAITagging.hasTag(article, urgent));
        assertTrue(JavAITagging.hasTag(comment, urgent), "removing the article's own tag must not affect the comment's");
    }

    @Test
    void postgresTaggedWithFindsHomogeneousArticleSet() {
        JavAIEnvironment.activatePostgresTagging();
        TagSet tagSet = postgresTagSets.save(new TagSet("postgres-homogeneous"));
        Tag featured = postgresTags.save(new Tag(tagSet, "en", "Featured"));

        Article first = postgresArticles.save(new Article("Postgres homogeneous first", "Body."));
        Article second = postgresArticles.save(new Article("Postgres homogeneous second", "Body."));
        Article untagged = postgresArticles.save(new Article("Postgres homogeneous untagged", "Body."));

        JavAITagging.addTag(first, featured);
        JavAITagging.addTag(second, featured);

        JavAIList<TaggableRef> refs = JavAITagging.taggedWith(featured, List.of(Article.class));
        assertEquals(2, refs.size());
        assertTrue(refs.stream().anyMatch(ref -> ref.taggableId().equals(first.getId())));
        assertTrue(refs.stream().anyMatch(ref -> ref.taggableId().equals(second.getId())));
        assertFalse(refs.stream().anyMatch(ref -> ref.taggableId().equals(untagged.getId())));
    }

    @Test
    void postgresTaggedWithFindsHeterogeneousArticleAndCommentSet() {
        JavAIEnvironment.activatePostgresTagging();
        TagSet tagSet = postgresTagSets.save(new TagSet("postgres-heterogeneous"));
        Tag shared = postgresTags.save(new Tag(tagSet, "en", "Shared"));

        Article article = postgresArticles.save(new Article("Postgres heterogeneous article", "Body."));
        Comment comment = postgresComments.save(new Comment("bob", "Postgres heterogeneous comment."));
        Article untaggedArticle = postgresArticles.save(new Article("Postgres heterogeneous untagged", "Body."));

        JavAITagging.addTag(article, shared);
        JavAITagging.addTag(comment, shared);

        JavAIList<TaggableRef> refs = JavAITagging.taggedWith(shared, List.of(Article.class, Comment.class));
        assertEquals(2, refs.size(), "one Article and one Comment sharing a tag must both come back from one query");
        assertTrue(refs.stream().anyMatch(ref -> ref.taggableId().equals(article.getId())));
        assertTrue(refs.stream().anyMatch(ref -> ref.taggableId().equals(comment.getId())));
        assertFalse(refs.stream().anyMatch(ref -> ref.taggableId().equals(untaggedArticle.getId())));
    }

    @Test
    void postgresTagSimilarityIndexRanksByTagSummaryVectorAndComposesWithJavaiLinkedHashSet() {
        JavAIEnvironment.activatePostgresTagging();
        TagSet tagSet = postgresTagSets.save(new TagSet("postgres-similarity"));
        Tag cybersecurity = postgresTags.save(new Tag(tagSet, "en", "Cybersecurity and hacking"));
        Tag cooking = postgresTags.save(new Tag(tagSet, "en", "Home cooking and recipes"));

        List<Article> fixtures = ArticleFixtures.newArticles();
        Article securityArticle = postgresArticles.save(findByTopic(fixtures, ArticleFixtures.Topic.CYBERSECURITY));
        Article cookingArticle = postgresArticles.save(findByTopic(fixtures, ArticleFixtures.Topic.COOKING));

        JavAITagging.addTag(securityArticle, cybersecurity);
        JavAITagging.addTag(cookingArticle, cooking);

        EmbeddingVector reference = ((JavAIVectorizable) cybersecurity).summaryVector();
        VectorIndex<TaggableRef> index = JavAITagging.tagSimilarityIndex();

        JavAIList<TaggableRef> nearest = index.nearestN(reference, 1);
        assertEquals(1, nearest.size());
        assertEquals(securityArticle.getId(), nearest.get(0).taggableId(),
                "the article tagged with the cybersecurity-summary-matching reference must rank nearest to itself");

        // Compose the query results into a JavAILinkedHashSet<TaggableRef> -- dedupes across two
        // overlapping queries into a concrete JavAI collection type holding TaggableRef values, the same
        // way this project's other collection-type tests already do with Comment values.
        JavAILinkedHashSet<TaggableRef> aboveThreshold = new JavAILinkedHashSet<>();
        for (TaggableRef ref : index.filterByMinSimilarity(reference, 0.9)) {
            aboveThreshold.add(ref);
        }
        assertTrue(aboveThreshold.contains(new TaggableRef(Article.class.getName(), securityArticle.getId())));
        assertFalse(aboveThreshold.contains(new TaggableRef(Article.class.getName(), cookingArticle.getId())),
                "an unrelated real-world topic must not pass a 0.9 similarity threshold against this reference");
    }

    // ---- Neo4j --------------------------------------------------------------------------------

    @Test
    void neo4jAddTagThenTagsOfRoundTripsOnArticleAndComment() {
        JavAIEnvironment.activateNeo4jTagging();
        TagSet tagSet = neo4jTagSets.save(new TagSet("neo4j-roundtrip"));
        Tag urgent = neo4jTags.save(new Tag(tagSet, "en", "Urgent"));
        Tag reviewed = neo4jTags.save(new Tag(tagSet, "en", "Reviewed"));

        Article article = neo4jArticles.save(new Article("Neo4j tag round-trip article", "Body text."));
        Comment comment = neo4jComments.save(new Comment("carol", "Neo4j tag round-trip comment."));

        JavAITagging.addTag(article, urgent);
        JavAITagging.addTag(comment, urgent, 0.8);

        assertTrue(JavAITagging.hasTag(article, urgent));
        assertTrue(JavAITagging.hasTag(comment, urgent));
        assertFalse(JavAITagging.hasTag(article, reviewed));

        JavAILinkedHashMap<String, Tag> bySlug = new JavAILinkedHashMap<>();
        for (Tag tag : JavAITagging.tagsOf(article)) {
            bySlug.put(tag.getSlug(), tag);
        }
        assertEquals(1, bySlug.size());
        assertTrue(bySlug.containsKey("urgent"));

        JavAITagging.removeTag(article, urgent);
        assertFalse(JavAITagging.hasTag(article, urgent));
        assertTrue(JavAITagging.hasTag(comment, urgent), "removing the article's own tag must not affect the comment's");
    }

    @Test
    void neo4jTaggedWithFindsHomogeneousArticleSet() {
        JavAIEnvironment.activateNeo4jTagging();
        TagSet tagSet = neo4jTagSets.save(new TagSet("neo4j-homogeneous"));
        Tag featured = neo4jTags.save(new Tag(tagSet, "en", "Featured"));

        Article first = neo4jArticles.save(new Article("Neo4j homogeneous first", "Body."));
        Article second = neo4jArticles.save(new Article("Neo4j homogeneous second", "Body."));
        Article untagged = neo4jArticles.save(new Article("Neo4j homogeneous untagged", "Body."));

        JavAITagging.addTag(first, featured);
        JavAITagging.addTag(second, featured);

        JavAIList<TaggableRef> refs = JavAITagging.taggedWith(featured, List.of(Article.class));
        assertEquals(2, refs.size());
        assertTrue(refs.stream().anyMatch(ref -> ref.taggableId().equals(first.getId())));
        assertTrue(refs.stream().anyMatch(ref -> ref.taggableId().equals(second.getId())));
        assertFalse(refs.stream().anyMatch(ref -> ref.taggableId().equals(untagged.getId())));
    }

    @Test
    void neo4jTaggedWithFindsHeterogeneousArticleAndCommentSet() {
        JavAIEnvironment.activateNeo4jTagging();
        TagSet tagSet = neo4jTagSets.save(new TagSet("neo4j-heterogeneous"));
        Tag shared = neo4jTags.save(new Tag(tagSet, "en", "Shared"));

        Article article = neo4jArticles.save(new Article("Neo4j heterogeneous article", "Body."));
        Comment comment = neo4jComments.save(new Comment("dave", "Neo4j heterogeneous comment."));
        Article untaggedArticle = neo4jArticles.save(new Article("Neo4j heterogeneous untagged", "Body."));

        JavAITagging.addTag(article, shared);
        JavAITagging.addTag(comment, shared);

        JavAIList<TaggableRef> refs = JavAITagging.taggedWith(shared, List.of(Article.class, Comment.class));
        assertEquals(2, refs.size(), "one Article and one Comment sharing a tag must both come back from one query");
        assertTrue(refs.stream().anyMatch(ref -> ref.taggableId().equals(article.getId())));
        assertTrue(refs.stream().anyMatch(ref -> ref.taggableId().equals(comment.getId())));
        assertFalse(refs.stream().anyMatch(ref -> ref.taggableId().equals(untaggedArticle.getId())));
    }

    @Test
    void neo4jTagSimilarityIndexRanksByTagSummaryVectorAndComposesWithJavaiLinkedHashSet() {
        JavAIEnvironment.activateNeo4jTagging();
        TagSet tagSet = neo4jTagSets.save(new TagSet("neo4j-similarity"));
        Tag cybersecurity = neo4jTags.save(new Tag(tagSet, "en", "Cybersecurity and hacking"));
        Tag cooking = neo4jTags.save(new Tag(tagSet, "en", "Home cooking and recipes"));

        List<Article> fixtures = ArticleFixtures.newArticles();
        Article securityArticle = neo4jArticles.save(findByTopic(fixtures, ArticleFixtures.Topic.CYBERSECURITY));
        Article cookingArticle = neo4jArticles.save(findByTopic(fixtures, ArticleFixtures.Topic.COOKING));

        JavAITagging.addTag(securityArticle, cybersecurity);
        JavAITagging.addTag(cookingArticle, cooking);

        EmbeddingVector reference = ((JavAIVectorizable) cybersecurity).summaryVector();
        VectorIndex<TaggableRef> index = JavAITagging.tagSimilarityIndex();

        JavAIList<TaggableRef> nearest = index.nearestN(reference, 1);
        assertEquals(1, nearest.size());
        assertEquals(securityArticle.getId(), nearest.get(0).taggableId());

        JavAILinkedHashSet<TaggableRef> aboveThreshold = new JavAILinkedHashSet<>();
        for (TaggableRef ref : index.filterByMinSimilarity(reference, 0.9)) {
            aboveThreshold.add(ref);
        }
        assertTrue(aboveThreshold.contains(new TaggableRef(Article.class.getName(), securityArticle.getId())));
        assertFalse(aboveThreshold.contains(new TaggableRef(Article.class.getName(), cookingArticle.getId())));
    }

    // ---- MongoDB ------------------------------------------------------------------------------

    @Test
    void mongoAddTagThenTagsOfRoundTripsOnArticleAndComment() {
        JavAIEnvironment.activateMongoTagging();
        TagSet tagSet = mongoTagSets.save(new TagSet("mongo-roundtrip"));
        Tag urgent = mongoTags.save(new Tag(tagSet, "en", "Urgent"));
        Tag reviewed = mongoTags.save(new Tag(tagSet, "en", "Reviewed"));

        Article article = mongoArticles.save(new Article("Mongo tag round-trip article", "Body text."));
        Comment comment = mongoComments.save(new Comment("erin", "Mongo tag round-trip comment."));

        JavAITagging.addTag(article, urgent);
        JavAITagging.addTag(comment, urgent, 0.8);

        assertTrue(JavAITagging.hasTag(article, urgent));
        assertTrue(JavAITagging.hasTag(comment, urgent));
        assertFalse(JavAITagging.hasTag(article, reviewed));

        JavAILinkedHashMap<String, Tag> bySlug = new JavAILinkedHashMap<>();
        for (Tag tag : JavAITagging.tagsOf(article)) {
            bySlug.put(tag.getSlug(), tag);
        }
        assertEquals(1, bySlug.size());
        assertTrue(bySlug.containsKey("urgent"));

        JavAITagging.removeTag(article, urgent);
        assertFalse(JavAITagging.hasTag(article, urgent));
        assertTrue(JavAITagging.hasTag(comment, urgent), "removing the article's own tag must not affect the comment's");
    }

    @Test
    void mongoTaggedWithFindsHomogeneousArticleSet() {
        JavAIEnvironment.activateMongoTagging();
        TagSet tagSet = mongoTagSets.save(new TagSet("mongo-homogeneous"));
        Tag featured = mongoTags.save(new Tag(tagSet, "en", "Featured"));

        Article first = mongoArticles.save(new Article("Mongo homogeneous first", "Body."));
        Article second = mongoArticles.save(new Article("Mongo homogeneous second", "Body."));
        Article untagged = mongoArticles.save(new Article("Mongo homogeneous untagged", "Body."));

        JavAITagging.addTag(first, featured);
        JavAITagging.addTag(second, featured);

        JavAIList<TaggableRef> refs = JavAITagging.taggedWith(featured, List.of(Article.class));
        assertEquals(2, refs.size());
        assertTrue(refs.stream().anyMatch(ref -> ref.taggableId().equals(first.getId())));
        assertTrue(refs.stream().anyMatch(ref -> ref.taggableId().equals(second.getId())));
        assertFalse(refs.stream().anyMatch(ref -> ref.taggableId().equals(untagged.getId())));
    }

    @Test
    void mongoTaggedWithFindsHeterogeneousArticleAndCommentSet() {
        JavAIEnvironment.activateMongoTagging();
        TagSet tagSet = mongoTagSets.save(new TagSet("mongo-heterogeneous"));
        Tag shared = mongoTags.save(new Tag(tagSet, "en", "Shared"));

        Article article = mongoArticles.save(new Article("Mongo heterogeneous article", "Body."));
        Comment comment = mongoComments.save(new Comment("frank", "Mongo heterogeneous comment."));
        Article untaggedArticle = mongoArticles.save(new Article("Mongo heterogeneous untagged", "Body."));

        JavAITagging.addTag(article, shared);
        JavAITagging.addTag(comment, shared);

        JavAIList<TaggableRef> refs = JavAITagging.taggedWith(shared, List.of(Article.class, Comment.class));
        assertEquals(2, refs.size(), "one Article and one Comment sharing a tag must both come back from one query");
        assertTrue(refs.stream().anyMatch(ref -> ref.taggableId().equals(article.getId())));
        assertTrue(refs.stream().anyMatch(ref -> ref.taggableId().equals(comment.getId())));
        assertFalse(refs.stream().anyMatch(ref -> ref.taggableId().equals(untaggedArticle.getId())));
    }

    @Test
    void mongoTagSimilarityIndexRanksByTagSummaryVectorAndComposesWithJavaiLinkedHashSet() {
        JavAIEnvironment.activateMongoTagging();
        TagSet tagSet = mongoTagSets.save(new TagSet("mongo-similarity"));
        Tag cybersecurity = mongoTags.save(new Tag(tagSet, "en", "Cybersecurity and hacking"));
        Tag cooking = mongoTags.save(new Tag(tagSet, "en", "Home cooking and recipes"));

        List<Article> fixtures = ArticleFixtures.newArticles();
        Article securityArticle = mongoArticles.save(findByTopic(fixtures, ArticleFixtures.Topic.CYBERSECURITY));
        Article cookingArticle = mongoArticles.save(findByTopic(fixtures, ArticleFixtures.Topic.COOKING));

        JavAITagging.addTag(securityArticle, cybersecurity);
        JavAITagging.addTag(cookingArticle, cooking);

        EmbeddingVector reference = ((JavAIVectorizable) cybersecurity).summaryVector();
        VectorIndex<TaggableRef> index = JavAITagging.tagSimilarityIndex();

        TaggableRef securityRef = new TaggableRef(Article.class.getName(), securityArticle.getId());
        JavAIList<TaggableRef> nearest = awaitContainsRef(() -> index.nearestN(reference, 1), securityRef);
        assertEquals(1, nearest.size());
        assertEquals(securityArticle.getId(), nearest.get(0).taggableId());

        JavAILinkedHashSet<TaggableRef> aboveThreshold = new JavAILinkedHashSet<>();
        for (TaggableRef ref : index.filterByMinSimilarity(reference, 0.9)) {
            aboveThreshold.add(ref);
        }
        assertTrue(aboveThreshold.contains(securityRef));
        assertFalse(aboveThreshold.contains(new TaggableRef(Article.class.getName(), cookingArticle.getId())));
    }

    // ---- Classification (real Cortex, auto-tagging) --------------------------------------------

    /**
     * Real auto-tagging: {@code javai-tagging}'s own module tests only prove {@code classify()}'s diff/
     * marshalling logic against {@code FakeCortex} (canned JSON, no real model understanding involved) --
     * deliberately, since that module's own job is the mechanism, not model quality. This is the suite's
     * one place a real LLM actually classifies real article text into the correct topic, using the same
     * Ollama-backed {@code Cortex} {@link CompletionE2ETest} exercises. Kept to a single {@code
     * classifyAll(...)} call over two articles (not the full 16-article {@code ArticleFixtures} set) --
     * each real completion is a genuine CPU-bound qwen3:8b inference, and this suite already budgets for
     * that cost via a generous surefire timeout (see the module's own {@code pom.xml}).
     */
    @Test
    void classifyAllAppliesCorrectRealWorldTopicTagsUsingTheRealCortex() {
        JavAIEnvironment.activatePostgresTagging();
        TagSet topics = postgresTagSets.save(new TagSet("real-classification-topics"));
        postgresTags.save(new Tag(topics, "en", "Cybersecurity"));
        postgresTags.save(new Tag(topics, "en", "Cooking"));
        postgresTags.save(new Tag(topics, "en", "Sports"));
        postgresTags.save(new Tag(topics, "en", "Space"));

        List<Article> fixtures = ArticleFixtures.newArticles();
        Article securityArticle = postgresArticles.save(findByTopic(fixtures, ArticleFixtures.Topic.CYBERSECURITY));
        Article cookingArticle = postgresArticles.save(findByTopic(fixtures, ArticleFixtures.Topic.COOKING));

        List<ClassificationResult> results =
                JavAITagging.classifyAll(List.of(securityArticle, cookingArticle), topics);
        assertEquals(2, results.size());

        ClassificationResult securityResult = results.stream()
                .filter(result -> result.instance().taggableId().equals(securityArticle.getId()))
                .findFirst().orElseThrow();
        ClassificationResult cookingResult = results.stream()
                .filter(result -> result.instance().taggableId().equals(cookingArticle.getId()))
                .findFirst().orElseThrow();

        assertTrue(securityResult.appliedTags().stream().anyMatch(applied -> applied.tag().getSlug().equals("cybersecurity")),
                "the real classifier should tag a cybersecurity-topic article with the cybersecurity candidate");
        assertTrue(cookingResult.appliedTags().stream().anyMatch(applied -> applied.tag().getSlug().equals("cooking")),
                "the real classifier should tag a cooking-topic article with the cooking candidate");

        // classify()/classifyAll() persist through the exact same addTag() path the structural tests above
        // exercise directly -- so the result is independently confirmed via hasTag(), not just trusted from
        // the returned ClassificationResult.
        Tag cybersecurityTag = JavAITagging.tagsOf(securityArticle).stream()
                .filter(tag -> tag.getSlug().equals("cybersecurity"))
                .findFirst().orElseThrow();
        assertTrue(JavAITagging.hasTag(securityArticle, cybersecurityTag));
    }
}
