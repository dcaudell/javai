package dev.xtrafe.javai.e2e;

import dev.xtrafe.javai.collections.VectorIndex;
import dev.xtrafe.javai.e2e.domain.Anthology;
import dev.xtrafe.javai.e2e.domain.Article;
import dev.xtrafe.javai.e2e.domain.ArticleRepository;
import dev.xtrafe.javai.e2e.domain.Comment;
import dev.xtrafe.javai.e2e.domain.CommentRepository;
import dev.xtrafe.javai.e2e.domain.Library;
import dev.xtrafe.javai.e2e.environment.JavAIEnvironment;
import dev.xtrafe.javai.model.JavAIList;
import dev.xtrafe.javai.model.JavAIVectorizable;
import dev.xtrafe.javai.tagging.JavAITagRepository;
import dev.xtrafe.javai.tagging.Tag;
import dev.xtrafe.javai.tagging.TagRepository;
import dev.xtrafe.javai.tagging.TagSet;
import dev.xtrafe.javai.tagging.TagSetRepository;
import dev.xtrafe.javai.tagging.TaggableRef;
import dev.xtrafe.javai.vector.EmbeddingVector;
import dev.xtrafe.javai.vector.Ranked;
import dev.xtrafe.javai.vector.VectorMath;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Filter-by-type on the tag indexes, against this project's own real, heterogeneous domain and real
 * embeddings, on all three backends (OMI-460).
 *
 * <h2>The failure this reproduces</h2>
 *
 * {@code tagSimilarityIndex()} spans <b>every</b> {@code @Taggable} type at once, which is the whole point of
 * it -- and the reason "the nearest N" over it is so rarely the question anyone has. The report that opened
 * OMI-460 measured it: a top-5 query for one type came back as three of another type interleaved with two of
 * the wanted one, and the caller's only recourse was to draw {@code limit × 5} and discard, which is
 * wasteful inside the multiplier and undetectably wrong outside it.
 *
 * <p>Reproduced here with {@link Comment}s and {@link Article}s, both {@code @Taggable}, in one index. The
 * comments carry the <em>same</em> tag the query is made with, so their tag-summary vector is that tag's own
 * direction and their similarity is 1.0 -- nothing else in the store, from this run or any earlier one, can
 * match that. So the nearest three are three comments, always, and "the articles among the nearest three" is
 * reliably empty. A rank-then-filter implementation cannot pass this by accident.
 *
 * <h2>What the similarity assertion adds</h2>
 *
 * Each article's reported similarity is checked against {@link VectorMath#cosineSimilarity} computed
 * <b>in this process</b> from the two tags' own vectors -- the number JavAI promises is the same one however
 * it was obtained ({@link Ranked}'s own contract). That is what pins each store's conversion of its native
 * score, and it has to be measured on a hit that is <em>not</em> an exact match: a perfect match is a fixed
 * point of the {@code (1 + cos) / 2} rescaling Neo4j and Atlas report, so a backend that forgot to undo it
 * would still say 1.0. An article at a real, partial similarity would not.
 *
 * <p>{@code javai-tagging}'s own {@code NarrowedTagIndexAllBackendsTest} proves the same contract against
 * throwaway containers with a scripted provider, where the angles are chosen. This proves it survives real
 * embeddings, a real multi-type domain, and stores that already hold data from previous runs.
 */
class NarrowedTagSearchE2ETest {

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

    @Test
    void postgresNarrowsTheTagSimilarityIndexToOneType() {
        assertNarrowsByType(JavAIEnvironment.postgresTagging(), postgresArticles, postgresComments,
                postgresTags, postgresTagSets, "Postgres");
    }

    @Test
    void neo4jNarrowsTheTagSimilarityIndexToOneType() {
        assertNarrowsByType(JavAIEnvironment.neo4jTagging(), neo4jArticles, neo4jComments,
                neo4jTags, neo4jTagSets, "Neo4j");
    }

    @Test
    void mongoNarrowsTheTagSimilarityIndexToOneType() {
        assertNarrowsByType(JavAIEnvironment.mongoTagging(), mongoArticles, mongoComments,
                mongoTags, mongoTagSets, "MongoDB");
    }

    /**
     * The tag-<b>text</b> index narrows on identical terms, over two real Taggregate containers.
     *
     * <p>Postgres-only because {@link Library} is realized against that backend alone in this project's
     * environment -- {@link Anthology} alone would put only one type in the index, which is precisely the
     * situation in which narrowing proves nothing.
     */
    @Test
    void postgresNarrowsTheTagTextIndexToOneType() {
        JavAITagRepository tagging = JavAIEnvironment.postgresTagging();
        String run = shortRun();
        TagSet tagSet = postgresTagSets.save(new TagSet("tag-text-narrowing-" + run));
        Tag shelved = postgresTags.save(new Tag(tagSet, "en", "Shelved collection " + run));
        Tag curated = postgresTags.save(new Tag(tagSet, "en", "Curated anthology " + run));

        Library library = JavAIEnvironment.postgresLibraryRepository().save(new Library("library-" + run));
        Anthology anthology = JavAIEnvironment.postgresAnthologyRepository()
                .save(new Anthology("anthology-" + run));
        tagging.addTag(library, shelved);
        tagging.addTag(anthology, curated);

        EmbeddingVector reference = tagging.tagTextVector(library);
        assertTrue(!reference.isAbsent(), "both types opt into concatenated tag text, so both are indexed");

        JavAIList<TaggableRef> libraries = tagging.nearestByTagText(reference, 3, List.of(Library.class));
        assertTrue(libraries.stream().allMatch(ref -> ref.taggableType().equals(Library.class.getName())),
                "the tag-text index spans every opted-in type; narrowed, it must return only this one");
        assertTrue(libraries.stream().anyMatch(ref -> ref.taggableId().equals(library.getId())),
                "the library whose own tag text produced the reference must be among its own nearest");

        assertEquals(0, tagging.tagTextIndex().ofType(Library.class).ofType(Anthology.class).size(),
                "narrowing intersects, so these two leave nothing");
    }

    /** Every claim OMI-460 makes about narrowing the tag-summary index, against one real backend. */
    private void assertNarrowsByType(JavAITagRepository tagging, ArticleRepository articles,
            CommentRepository comments, TagRepository tags, TagSetRepository tagSets, String backend) {
        String run = shortRun();
        TagSet tagSet = tagSets.save(new TagSet("coastal-" + run));
        // Two genuinely related but distinct real-world topics: the articles must land at a real, partial
        // similarity to the query, not at 1.0 and not at nothing (see the class javadoc).
        Tag beach = tags.save(new Tag(tagSet, "en", "Beaches and swimming " + run));
        Tag coastline = tags.save(new Tag(tagSet, "en", "Coastal erosion and sea walls " + run));

        List<UUID> articleIds = List.of(
                tagAndReturnId(tagging, articles.save(new Article("Sea wall rebuilt after winter storms " + run,
                        "The rebuilt sea wall is expected to slow erosion along the northern shore.")), coastline),
                tagAndReturnId(tagging, articles.save(new Article("Erosion survey published for the coast " + run,
                        "A survey of the coastline recorded significant sand loss over the past decade.")), coastline),
                tagAndReturnId(tagging, articles.save(new Article("Harbour defences approved by council " + run,
                        "New harbour defences were approved after a long consultation with residents.")), coastline));
        List<UUID> commentIds = new ArrayList<>();
        for (String author : List.of("ada", "brix", "cleo")) {
            Comment comment = comments.save(new Comment(author,
                    "Spent the whole afternoon swimming off the beach " + run + "."));
            tagging.addTag(comment, beach);
            commentIds.add(comment.getId());
        }

        EmbeddingVector reference = ((JavAIVectorizable) beach).summaryVector();

        // 1. Unnarrowed, the wanted type is invisible: the nearest three are this run's three comments,
        //    which carry the query's own tag and therefore sit at similarity 1.0 -- a place no earlier run's
        //    data can reach, since the tag text carries this run's id. (The await is MongoDB's index
        //    catching up, and expires rather than looping forever, so the assertion still runs either way.)
        JavAIList<TaggableRef> unnarrowed = await(
                () -> tagging.tagSimilarityIndex().nearestN(reference, 3),
                hits -> hits.size() == 3 && commentIds.containsAll(
                        hits.stream().map(TaggableRef::taggableId).toList()));
        assertTrue(unnarrowed.stream().allMatch(ref -> ref.taggableType().equals(Comment.class.getName())),
                backend + ": the comments carry the query's own tag, so nothing can outrank them -- this is "
                        + "the state a caller was stuck with, and why they had to over-fetch");
        assertTrue(commentIds.containsAll(unnarrowed.stream().map(TaggableRef::taggableId).toList()),
                backend + ": and specifically this run's comments, at 1.0");

        // 2. Narrowed, the same top-three question answers it.
        JavAIList<TaggableRef> narrowed = await(
                () -> tagging.nearestByTagSimilarity(reference, 3, List.of(Article.class)),
                hits -> hits.size() == 3);
        assertEquals(3, narrowed.size(), backend + ": the nearest 3 articles must be 3 articles -- "
                + "filtering the nearest 3 would have returned none of them");
        assertTrue(narrowed.stream().allMatch(ref -> ref.taggableType().equals(Article.class.getName())));

        // 2b. This run's own articles are reachable, which the top-3 alone cannot show: an earlier run's
        //     articles carry a near-identical tag and rank alongside these, so the wider draw is where
        //     "mine came back" is a fair question. Still every hit an Article, at any depth.
        JavAIList<TaggableRef> wide = await(
                () -> tagging.nearestByTagSimilarity(reference, 50, List.of(Article.class)),
                hits -> hits.stream().map(TaggableRef::taggableId).toList().containsAll(articleIds));
        assertTrue(wide.stream().allMatch(ref -> ref.taggableType().equals(Article.class.getName())),
                backend + ": narrowing holds for the whole draw, not just its head");
        assertTrue(wide.stream().map(TaggableRef::taggableId).toList().containsAll(articleIds),
                backend + ": this run's three articles are all reachable through the narrowed index");

        // 3. The chained spelling is the same query.
        assertEquals(List.copyOf(narrowed),
                List.copyOf(tagging.tagSimilarityIndex().ofType(Article.class).nearestN(reference, 3)));

        // 4. The reported similarity is the one this process computes from the same two vectors.
        double expected = VectorMath.cosineSimilarity(reference,
                VectorMath.normalize(((JavAIVectorizable) coastline).summaryVector()));
        assertTrue(expected > 0.0 && expected < 0.999, backend + ": the fixture must put the articles at a "
                + "real partial similarity, or the check below tests nothing");
        List<Ranked<TaggableRef>> ranked = tagging.tagSimilarityIndex()
                .ofType(Article.class).nearestNRanked(reference, 50);
        List<UUID> scored = new ArrayList<>();
        for (Ranked<TaggableRef> hit : ranked) {
            if (articleIds.contains(hit.entity().taggableId())) {
                scored.add(hit.entity().taggableId());
                assertEquals(expected, hit.similarity(), 1e-3, backend + ": a store's own score converted to "
                        + "the cosine JavAI speaks everywhere -- measured on a partial match, which the "
                        + "(1 + cos) / 2 rescaling does not leave unchanged");
            }
        }
        assertEquals(articleIds.size(), scored.size(),
                backend + ": every one of this run's articles was scored, so the check above ran three times");

        // 5. Narrowing to no type at all matches nothing, rather than everything.
        VectorIndex<TaggableRef> nothing = tagging.tagSimilarityIndex().ofType(List.of());
        assertEquals(0, nothing.size(), backend + ": naming no types is not a way to say 'everything'");
        assertTrue(nothing.nearestN(reference, 5).isEmpty());
    }

    private static UUID tagAndReturnId(JavAITagRepository tagging, Article article, Tag tag) {
        tagging.addTag(article, tag);
        return article.getId();
    }

    /** Unique per run: these stores are not reset between runs, and a tag reused across two runs would put
     *  an earlier run's instances at exactly the similarity this test's assertions reserve for its own. */
    private static String shortRun() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    /** See {@code PersistenceE2ETest.awaitNearest}: MongoDB Search's {@code $vectorSearch} index updates
     *  near-real-time rather than synchronously with the write. Harmless on the other two backends, where
     *  the first attempt already satisfies the condition. */
    private static <T> T await(Supplier<T> query, Predicate<T> satisfied) {
        Instant deadline = Instant.now().plus(Duration.ofMinutes(2));
        while (true) {
            T result = query.get();
            if (satisfied.test(result) || Instant.now().isAfter(deadline)) {
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
}
