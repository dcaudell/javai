package dev.xtrafe.javai.e2e;

import dev.xtrafe.javai.annotations.Modifying;
import dev.xtrafe.javai.annotations.Query;
import dev.xtrafe.javai.e2e.domain.Article;
import dev.xtrafe.javai.e2e.domain.ArticleCommentCount;
import dev.xtrafe.javai.e2e.domain.ArticleQueryRepository;
import dev.xtrafe.javai.e2e.domain.Comment;
import dev.xtrafe.javai.e2e.domain.assoc.AssocAnyPlain;
import dev.xtrafe.javai.e2e.domain.assoc.AssocAnyTarget;
import dev.xtrafe.javai.e2e.domain.assoc.AssocAnyVectorizable;
import dev.xtrafe.javai.e2e.domain.assoc.AssocHub;
import dev.xtrafe.javai.e2e.domain.assoc.AssocHubQueryRepository;
import dev.xtrafe.javai.e2e.domain.assoc.AssocLeaf;
import dev.xtrafe.javai.e2e.environment.JavAIEnvironment;
import dev.xtrafe.javai.model.JavAIVectorizable;
import dev.xtrafe.javai.persistence.JavAIPI;
import dev.xtrafe.javai.persistence.JavAIPersistenceConfig;
import dev.xtrafe.javai.persistence.JavAIRepository;
import dev.xtrafe.javai.persistence.Windows;
import dev.xtrafe.javai.vector.EmbeddingVector;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Slice;
import org.springframework.data.domain.Sort;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Declared queries (OMI-398) and {@code @Any} predicates (OMI-407) against real weaving, a real Postgres, and
 * real embeddings -- as a client of the published artifacts rather than from inside the module.
 *
 * <p><b>What this proves that the unit tests cannot.</b> {@code javai-persistence}'s own fixtures are
 * hand-written stand-ins for woven classes: they implement {@code JavAIVectorizable} by delegating to
 * {@code JavAIRuntime}, because that module has no dependency on {@code javai-substrate}. Everything here is
 * genuinely woven by the agent, so the refusals are checked against real {@code @Vectorize} fields on real
 * synthesized accessors, and "the vector survived a targeted write" is a statement about a real embedding of
 * real text rather than a fake one. The object graph is the project's own: an {@code Article} with a
 * {@code @Summary} singular association, a {@code @Summary} {@code JavAIList}, a {@code JavAIMap}, private
 * search visibility, and -- through {@link AssocHub} -- lazy and eager polymorphic {@code @Any} references
 * that may resolve per row to a vectorizable or a non-vectorizable target.
 *
 * <p><b>One structural finding, encoded here rather than described.</b> A {@code @Query} is refused when the
 * repository is <em>realized</em>, and only Postgres serves one -- so a declared query cannot live on an
 * interface that is also realized against Neo4j or MongoDB. {@code ArticleRepository} is realized against all
 * three in this environment, which is why {@link ArticleQueryRepository} exists beside it. That is the
 * pattern an adopter should copy, and it is asserted below rather than left as advice.
 */
class DeclaredQueryE2ETest {

    @BeforeAll
    static void ensureEnvironment() {
        JavAIEnvironment.ensureRunning();
    }

    private static String tag() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private static Article articleWithComments(String title, String... authors) {
        Article article = new Article(title, "A critical vulnerability in a widely used TLS library.");
        for (String author : authors) {
            article.getComments().add(new Comment(author, "comment by " + author));
        }
        return article;
    }

    // ---- offset windows (OMI-460) ------------------------------------------------------------------

    /**
     * The forever-scroll idiom, against a real declared query: page 3 of 2, fetched as 3 rows so the extra
     * row answers "is there more?" -- an offset of 4 with a limit of 3, which no page number can express.
     *
     * <p>The comparison against {@code PageRequest} is the point. Both calls are legal, both return rows,
     * and only one returns the rows the caller asked for; before {@code Windows} the adopter's choice was to
     * write their own {@code Pageable} or to be quietly off by two.
     */
    @Test
    void anOffsetWindowPagesADeclaredQueryWhereAPageNumberCannot() {
        ArticleQueryRepository articles = JavAIEnvironment.postgresArticleQueryRepository();
        String tag = tag();
        String pattern = "window-" + tag + "-%";
        for (int index = 1; index <= 6; index++) {
            articles.save(new Article("window-" + tag + "-" + index,
                    "Body of windowed article number " + index + " for run " + tag + "."));
        }

        List<String> window = articles.windowByTitleLike(pattern, Windows.of(4, 3))
                .stream().map(Article::getTitle).toList();
        assertEquals(List.of("window-" + tag + "-5", "window-" + tag + "-6"), window,
                "offset 4 of a title-ordered query -- and only two rows left, so this is the last page");

        List<String> probe = articles.windowByTitleLike(pattern, Windows.of(2, 3))
                .stream().map(Article::getTitle).toList();
        assertEquals(3, probe.size(), "three rows came back for a page of two, so a next page exists -- "
                + "learned without a second count query");
        assertEquals(List.of("window-" + tag + "-3", "window-" + tag + "-4", "window-" + tag + "-5"), probe);

        List<String> nearestPageNumber = articles.windowByTitleLike(pattern, PageRequest.of(4 / 3, 3))
                .stream().map(Article::getTitle).toList();
        assertEquals(List.of("window-" + tag + "-4", "window-" + tag + "-5", "window-" + tag + "-6"),
                nearestPageNumber, "PageRequest lands on offset 3, not 4 -- different rows, silently");
    }

    /** An offset window drives the ordinary {@code Page} return too, total count and all. */
    @Test
    void anOffsetWindowAlsoBacksAPageReturn() {
        ArticleQueryRepository articles = JavAIEnvironment.postgresArticleQueryRepository();
        String tag = tag();
        String pattern = "paged-" + tag + "-%";
        for (int index = 1; index <= 5; index++) {
            articles.save(new Article("paged-" + tag + "-" + index, "Body " + index + " for run " + tag + "."));
        }

        Page<Article> page = articles.pageByTitleLike(pattern, Windows.of(1, 2, Sort.by("title")));

        assertEquals(2, page.getContent().size());
        assertEquals(5, page.getTotalElements(), "the count query ignores the window, as it should");
        assertEquals(List.of("paged-" + tag + "-2", "paged-" + tag + "-3"),
                page.getContent().stream().map(Article::getTitle).toList());
    }

    // ---- the grouped aggregate, over a real JavAI collection ---------------------------------------

    /**
     * The question the parent ticket opens with, asked of the real graph: a count per article rather than one
     * total. {@code countByCommentsIn(...)} cannot express it, and the alternatives were N queries or loading
     * every comment.
     */
    @Test
    void aGroupedAggregateReturnsOneRowPerArticle() {
        ArticleQueryRepository articles = JavAIEnvironment.postgresArticleQueryRepository();
        String tag = tag();
        UUID two = articles.save(articleWithComments("agg-two-" + tag, "ann-" + tag, "bob-" + tag)).getId();
        UUID one = articles.save(articleWithComments("agg-one-" + tag, "cal-" + tag)).getId();
        UUID none = articles.save(articleWithComments("agg-none-" + tag)).getId();

        Map<UUID, Long> counts = articles.commentCountsByArticle(List.of(two, one, none)).stream()
                .collect(Collectors.toMap(row -> (UUID) row[0], row -> (Long) row[1]));

        assertEquals(2L, counts.get(two));
        assertEquals(1L, counts.get(one));
        assertFalse(counts.containsKey(none),
                "an inner join over the comments association omits an article with none, as SQL says it should");
    }

    @Test
    void theSameAggregateComesBackAsARecord() {
        ArticleQueryRepository articles = JavAIEnvironment.postgresArticleQueryRepository();
        String tag = tag();
        UUID two = articles.save(articleWithComments("rec-two-" + tag, "ann-" + tag, "bob-" + tag)).getId();
        UUID one = articles.save(articleWithComments("rec-one-" + tag, "cal-" + tag)).getId();

        List<ArticleCommentCount> counts = articles.typedCommentCountsByArticle(List.of(two, one));

        assertEquals(List.of(new ArticleCommentCount(two, 2), new ArticleCommentCount(one, 1)), counts,
                "a JPQL constructor expression instantiates the record directly, in the query's own order");
    }

    @Test
    void aScalarProjectionOverANestedToManyReturnsIdsOnly() {
        ArticleQueryRepository articles = JavAIEnvironment.postgresArticleQueryRepository();
        String tag = tag();
        String author = "solo-" + tag;
        UUID wanted = articles.save(articleWithComments("ids-" + tag, author)).getId();
        articles.save(articleWithComments("ids-other-" + tag, "someone-else-" + tag));

        assertEquals(List.of(wanted), articles.idsOfArticlesCommentedOnBy(author));
    }

    // ---- entity returns on a genuinely woven class -------------------------------------------------

    /**
     * A declared query is an ordinary read, so an entity it returns must arrive with its stored vectors
     * already in its slots. Asserted against a <em>real</em> embedding: the vector read back must be the
     * identical array the save computed, which a recomputation from the same text could accidentally match --
     * so this is paired with the write test below, where the values would genuinely diverge if anything
     * re-embedded.
     */
    @Test
    void anEntityFromADeclaredQueryCarriesItsRealStoredVectors() {
        ArticleQueryRepository articles = JavAIEnvironment.postgresArticleQueryRepository();
        String title = "hydrate-" + tag();
        Article saved = articles.save(new Article(title, "Post-quantum key exchange has landed in the browser."));
        float[] savedVector = ((JavAIVectorizable) saved).fieldVector("title").values();

        List<Article> found = articles.byTitle(title);

        assertEquals(1, found.size());
        assertArrayEquals(savedVector, ((JavAIVectorizable) found.get(0)).fieldVector("title").values(),
                "the stored vector must be served back, not recomputed");
    }

    @Test
    void adaptsOptionalPageSliceAndDynamicSortOverTheRealGraph() {
        ArticleQueryRepository articles = JavAIEnvironment.postgresArticleQueryRepository();
        String tag = tag();
        articles.save(new Article("page-c-" + tag, "third"));
        articles.save(new Article("page-a-" + tag, "first"));
        articles.save(new Article("page-b-" + tag, "second"));
        String pattern = "page-%-" + tag;

        assertTrue(articles.oneByTitle("page-a-" + tag).isPresent());

        Page<Article> page = articles.pageByTitleLike(pattern, PageRequest.of(0, 2));
        assertEquals(2, page.getContent().size());
        assertEquals(3, page.getTotalElements(), "from countQuery, not from the window");

        Slice<Article> slice = articles.sliceByTitleLike(pattern, PageRequest.of(0, 2));
        assertTrue(slice.hasNext(), "a Slice probes one extra row rather than counting");

        assertEquals(List.of("page-a-" + tag, "page-b-" + tag, "page-c-" + tag),
                articles.sortedByTitleLike(pattern, Sort.by("title").ascending())
                        .stream().map(Article::getTitle).toList(),
                "a dynamic Sort is applied through the query model, never by editing the query text");
    }

    @Test
    void reachesThroughASummarySingularAssociationWithPositionalBinding() {
        ArticleQueryRepository articles = JavAIEnvironment.postgresArticleQueryRepository();
        String tag = tag();
        String editor = "editor-" + tag;
        Article article = new Article("featured-" + tag, "An editor's pick.");
        article.setFeaturedComment(new Comment(editor, "editor's pick"));
        UUID id = articles.save(article).getId();

        assertEquals(List.of(id),
                articles.byFeaturedCommentAuthor(editor).stream().map(Article::getId).toList());
    }

    @Test
    void runsANativeQueryAgainstTheRealSchema() {
        ArticleQueryRepository articles = JavAIEnvironment.postgresArticleQueryRepository();
        String tag = tag();
        String title = "native-" + tag;
        UUID id = articles.save(new Article(title, "Straight SQL against the mapped table.")).getId();

        assertEquals(List.of(id), articles.nativeByTitle(title).stream().map(Article::getId).toList());
        assertEquals(1L, articles.nativeCountByTitleLike("native-" + tag));
    }

    @Test
    void aQueryJoiningANestedAssociationOrdersAndDeduplicates() {
        ArticleQueryRepository articles = JavAIEnvironment.postgresArticleQueryRepository();
        String tag = tag();
        String prefix = "au-" + tag;
        articles.save(articleWithComments("join-b-" + tag, prefix + "-1", prefix + "-2"));
        articles.save(articleWithComments("join-a-" + tag, prefix + "-3"));

        List<String> titles = articles.commentedOnByAuthorLike(prefix + "%")
                .stream().map(Article::getTitle).toList();

        assertEquals(List.of("join-a-" + tag, "join-b-" + tag), titles,
                "distinct must collapse the two-comment article to one row, and the ordering must hold");
    }

    // ---- targeted writes on a woven, vectorized entity ---------------------------------------------

    /**
     * The half a refusal must not break, measured where it counts: a targeted write to an ordinary column on
     * a genuinely woven {@code @JavAIVectorizable} entity, with the real embedding left untouched.
     */
    @Test
    void aTargetedWriteToAnOrdinaryColumnLeavesTheRealVectorAlone() {
        ArticleQueryRepository articles = JavAIEnvironment.postgresArticleQueryRepository();
        String title = "view-" + tag();
        Article saved = articles.save(new Article(title, "Counting views must not disturb an embedding."));
        UUID id = saved.getId();
        float[] before = ((JavAIVectorizable) saved).fieldVector("title").values();

        assertEquals(1, articles.recordView(id));
        assertEquals(1, articles.recordView(id));

        Article reloaded = articles.findById(id).orElseThrow();
        assertEquals(2, reloaded.getViewCount());
        assertArrayEquals(before, ((JavAIVectorizable) reloaded).fieldVector("title").values(),
                "a write to a column no vector reads must leave the embedding exactly as it was");
        assertEquals(title, reloaded.getTitle());
    }

    /**
     * The ticket's motivating defect, end to end: a count maintained by its own path, an unrelated edit
     * saved over the top of it, and the count surviving.
     */
    @Test
    void aColumnMaintainedByItsOwnPathSurvivesAnUnrelatedSave() {
        ArticleQueryRepository articles = JavAIEnvironment.postgresArticleQueryRepository();
        String title = "like-" + tag();
        UUID id = articles.save(new Article(title, "Likes are counted elsewhere.")).getId();

        articles.recordLike(id);
        articles.recordLike(id);
        articles.recordLike(id);
        assertEquals(3, articles.findById(id).orElseThrow().getLikeCount());

        // The clobber: a detached article loaded before the third like, edited for an unrelated reason.
        Article stale = articles.findById(id).orElseThrow();
        stale.setLikeCount(0);
        stale.setTitle(title + "-edited");
        articles.save(stale);

        Article reloaded = articles.findById(id).orElseThrow();
        assertEquals(3, reloaded.getLikeCount(),
                "@Column(updatable = false) is what stops an unrelated save carrying a stale count back");
        assertEquals(title + "-edited", reloaded.getTitle(),
                "while the edit the save was actually for still lands -- including its re-embedding");
    }

    @Test
    void aDeclaredDeleteRemovesTheArticleAndItsVectorRows() {
        ArticleQueryRepository articles = JavAIEnvironment.postgresArticleQueryRepository();
        String title = "del-" + tag();
        UUID id = articles.save(articleWithComments(title, "commenter-" + tag())).getId();

        assertEquals(1, articles.deleteByTitleDeclared(title));

        assertTrue(articles.findById(id).isEmpty());
        assertTrue(articles.byTitle(title).isEmpty(),
                "and it is gone from the store, not merely detached from this session");
    }

    // ---- @Any predicates over the polymorphic hub --------------------------------------------------

    /**
     * {@code mandatoryLazyManyToOne} is {@code optional = false} with a NOT NULL join column and <em>no</em>
     * cascade, so its leaf has to be persisted through its own repository before the hub references it --
     * the same construction {@code AssociationGraphE2ETest} uses.
     */
    private static AssocHub hubWith(String label, AssocAnyTarget eager, AssocAnyTarget lazy) {
        AssocLeaf leaf = JavAIEnvironment.postgresAssocLeafRepository().save(new AssocLeaf("leaf for " + label));
        AssocHub hub = new AssocHub(label, leaf);
        hub.setEagerAny(eager);
        hub.setLazyAny(lazy);
        return hub;
    }

    @Test
    void filtersByAnAnyTargetsTypeAcrossVectorizableAndPlainTargets() {
        AssocHubQueryRepository hubs = JavAIEnvironment.postgresAssocHubQueryRepository();
        String tag = tag();
        UUID vectorizable = hubs.save(hubWith("hub-vec-" + tag,
                new AssocAnyVectorizable("vec-" + tag), new AssocAnyPlain("lazy-plain-" + tag))).getId();
        UUID plain = hubs.save(hubWith("hub-plain-" + tag,
                new AssocAnyPlain("plain-" + tag), new AssocAnyVectorizable("lazy-vec-" + tag))).getId();

        Set<UUID> vectorizableBacked = ids(hubs.findByEagerAnyOfType(AssocAnyVectorizable.class), tag);
        Set<UUID> plainBacked = ids(hubs.findByEagerAnyOfType(AssocAnyPlain.class), tag);

        assertEquals(Set.of(vectorizable), vectorizableBacked,
                "the discriminator resolves the target type without loading the target");
        assertEquals(Set.of(plain), plainBacked);
        assertEquals(Set.of(plain), ids(hubs.findByEagerAnyOfTypeNot(AssocAnyVectorizable.class), tag));
        assertEquals(Set.of(vectorizable, plain),
                ids(hubs.findByEagerAnyOfTypeIn(
                        List.of(AssocAnyVectorizable.class, AssocAnyPlain.class)), tag));
    }

    @Test
    void filtersByALazyAnyWithoutInitializingIt() {
        AssocHubQueryRepository hubs = JavAIEnvironment.postgresAssocHubQueryRepository();
        String tag = tag();
        UUID lazyVectorizable = hubs.save(hubWith("hub-lazy-vec-" + tag,
                new AssocAnyPlain("eager-plain-" + tag), new AssocAnyVectorizable("lazy-vec-" + tag))).getId();
        hubs.save(hubWith("hub-lazy-plain-" + tag,
                new AssocAnyPlain("eager-plain2-" + tag), new AssocAnyPlain("lazy-plain-" + tag)));

        assertEquals(Set.of(lazyVectorizable),
                ids(hubs.findByLazyAnyOfType(AssocAnyVectorizable.class), tag),
                "the discriminator is a column on the hub's own row, so a lazy target need never be loaded");
    }

    @Test
    void filtersByTheTargetInstanceItself() {
        AssocHubQueryRepository hubs = JavAIEnvironment.postgresAssocHubQueryRepository();
        String tag = tag();
        AssocAnyVectorizable target = new AssocAnyVectorizable("instance-" + tag);
        UUID id = hubs.save(hubWith("hub-instance-" + tag, target, new AssocAnyPlain("other-" + tag))).getId();
        hubs.save(hubWith("hub-instance-other-" + tag,
                new AssocAnyVectorizable("elsewhere-" + tag), new AssocAnyPlain("other2-" + tag)));

        AssocAnyTarget stored = hubs.findById(id).orElseThrow().getEagerAny();

        assertEquals(Set.of(id), ids(hubs.findByEagerAny(stored), tag),
                "equality on an @Any resolves to its discriminator and key together");
    }

    @Test
    void composesTargetTypePredicatesWithEachOtherAndWithOrdinaryColumns() {
        AssocHubQueryRepository hubs = JavAIEnvironment.postgresAssocHubQueryRepository();
        String tag = tag();
        String label = "hub-composed-" + tag;
        UUID wanted = hubs.save(hubWith(label,
                new AssocAnyVectorizable("v-" + tag), new AssocAnyPlain("p-" + tag))).getId();
        hubs.save(hubWith("hub-composed-other-" + tag,
                new AssocAnyVectorizable("v2-" + tag), new AssocAnyVectorizable("p2-" + tag)));

        assertEquals(Set.of(wanted), ids(hubs.findByEagerAnyOfTypeAndLabel(AssocAnyVectorizable.class, label), tag));
        assertEquals(Set.of(wanted), ids(hubs.findByEagerAnyOfTypeAndLazyAnyOfType(
                AssocAnyVectorizable.class, AssocAnyPlain.class), tag),
                "two @Any fields, each with its own target-type predicate, in one query");
        assertTrue(hubs.existsByEagerAnyOfType(AssocAnyVectorizable.class));
        assertTrue(hubs.countByEagerAnyOfType(AssocAnyVectorizable.class) > 0);
    }

    /** A `@Summary` `@Any` is filterable like any other, and filtering it disturbs nothing it feeds. */
    @Test
    void filtersBySummaryBearingAnyWithoutDisturbingTheSummaryVector() {
        AssocHubQueryRepository hubs = JavAIEnvironment.postgresAssocHubQueryRepository();
        String tag = tag();
        AssocHub hub = hubWith("hub-summary-" + tag,
                new AssocAnyVectorizable("eager-" + tag), new AssocAnyPlain("lazy-" + tag));
        hub.setSummaryLazyAny(new AssocAnyVectorizable("summary-" + tag));
        UUID id = hubs.save(hub).getId();

        // Read inside a unit of work, deliberately: summaryVector() walks this hub's @Summary children, one
        // of which is a lazy JavAI collection, and a repository hands back a detached graph (OMI-271). This
        // is the documented shape for wanting a summary off a loaded entity, not a workaround.
        float[] before = JavAIPI.inTransaction(JavAIEnvironment.postgresConfig(),
                () -> ((JavAIVectorizable) hubs.findById(id).orElseThrow()).summaryVector().values());

        assertEquals(Set.of(id), ids(hubs.findBySummaryLazyAnyOfType(AssocAnyVectorizable.class), tag));

        float[] after = JavAIPI.inTransaction(JavAIEnvironment.postgresConfig(),
                () -> ((JavAIVectorizable) hubs.findById(id).orElseThrow()).summaryVector().values());
        assertArrayEquals(before, after, "a read must not move the summary it read through");
    }

    /** The keyword inside a narrowed vector search, over real woven embeddings. */
    @Test
    void narrowsARealVectorSearchByTargetType() {
        AssocHubQueryRepository hubs = JavAIEnvironment.postgresAssocHubQueryRepository();
        String tag = tag();
        AssocHub vectorizableBacked = hubs.save(hubWith("searchable hub " + tag,
                new AssocAnyVectorizable("v-" + tag), new AssocAnyPlain("p-" + tag)));
        hubs.save(hubWith("searchable hub plain " + tag,
                new AssocAnyPlain("p2-" + tag), new AssocAnyPlain("p3-" + tag)));

        EmbeddingVector reference = ((JavAIVectorizable) vectorizableBacked).fieldVector("label");
        List<AssocHub> hits = hubs.findNearestByLabelVectorAndEagerAnyOfType(
                reference, 10, AssocAnyVectorizable.class);

        assertFalse(hits.isEmpty());
        assertTrue(hits.stream().allMatch(hit -> hit.getEagerAny() instanceof AssocAnyVectorizable),
                "the predicate is applied by the ranking query, not to its output");
    }

    private static Set<UUID> ids(List<AssocHub> hubs, String tag) {
        return hubs.stream()
                .filter(hub -> hub.getLabel() != null && hub.getLabel().contains(tag))
                .map(AssocHub::getId)
                .collect(Collectors.toSet());
    }

    // ---- the refusals, against genuinely woven classes ---------------------------------------------

    /** A woven {@code @Vectorize} field: the refusal must read the real annotation on the real class. */
    interface WritesAWovenVectorizeField extends JavAIRepository<Article> {
        @Modifying
        @Query("update Article a set a.title = :title where a.id = :id")
        int rename(@Param("id") UUID id, @Param("title") String title);
    }

    interface WritesAWovenSummaryAssociation extends JavAIRepository<Article> {
        @Modifying
        @Query("update Article a set a.featuredComment = null where a.id = :id")
        int unfeature(@Param("id") UUID id);
    }

    interface WritesNativelyToAWovenVectorizedEntity extends JavAIRepository<Article> {
        @Modifying
        @Query(value = "update article set view_count = view_count + 1", nativeQuery = true)
        int bump();
    }

    interface TraversesThroughAnAny extends JavAIRepository<AssocHub> {
        List<AssocHub> findByEagerAnyLabel(String label);
    }

    @Test
    void refusesABulkWriteToAWovenVectorizeField() {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> JavAIPI.repository(WritesAWovenVectorizeField.class, JavAIEnvironment.postgresConfig()));

        assertTrue(thrown.getMessage().contains("@Vectorize"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("outlives this process"),
                "the message must name the durable consequence, not just the rule: " + thrown.getMessage());
    }

    @Test
    void refusesABulkWriteToAWovenSummaryAssociation() {
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> JavAIPI.repository(WritesAWovenSummaryAssociation.class, JavAIEnvironment.postgresConfig()))
                .getMessage().contains("@Summary"));
    }

    @Test
    void refusesANativeBulkWriteToAWovenVectorizedEntity() {
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> JavAIPI.repository(WritesNativelyToAWovenVectorizedEntity.class,
                        JavAIEnvironment.postgresConfig()))
                .getMessage().contains("vectors"));
    }

    /**
     * Traversing into an {@code @Any} is refused when the repository is realized -- what a caller needs.
     *
     * <p>The message here names the unresolvable property rather than {@code @Any}, and that is this domain
     * rather than a weaker refusal: {@code AssocAnyTarget} declares {@code label()}, not {@code getLabel()},
     * so {@code PartTree} cannot resolve the path and rejects it before the backend's own {@code @Any}-naming
     * check is reached. Both refusals are real; which one fires depends on whether the target interface
     * exposes a bean property. The {@code @Any}-specific wording is pinned by
     * {@code AnyPredicateTest.refusesTraversingThroughAnAny}, whose target does.
     */
    @Test
    void refusesTraversingThroughAnAny() {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> JavAIPI.repository(TraversesThroughAnAny.class, JavAIEnvironment.postgresConfig()));

        assertTrue(thrown.getMessage().contains("eagerAny") || thrown.getMessage().contains("@Any"),
                "the refusal must name the association it could not traverse: " + thrown.getMessage());
    }

    /**
     * The structural consequence this test class exists to encode: a declared query cannot share an interface
     * with a repository realized against another backend, because the refusal happens at realization.
     *
     * <p>Asserted against Neo4j and MongoDB configs built here rather than the environment's, so nothing this
     * test does can disturb the shared proxies every other e2e test uses.
     */
    @Test
    void aDeclaredQueryCannotBeRealizedAgainstNeo4jOrMongo() {
        for (JavAIPersistenceConfig.Backend backend : List.of(
                JavAIPersistenceConfig.Backend.NEO4J, JavAIPersistenceConfig.Backend.MONGODB)) {
            UnsupportedOperationException thrown = assertThrows(UnsupportedOperationException.class,
                    () -> JavAIPI.repository(ArticleQueryRepository.class, JavAIPersistenceConfig.builder()
                            .backend(backend)
                            .neo4jUri("bolt://localhost:7687").neo4jUsername("neo4j").neo4jPassword("unused")
                            .mongoUri("mongodb://localhost:27017").mongoDatabase("unused")
                            .build()),
                    backend + " must refuse a declared query when the repository is realized");

            assertTrue(thrown.getMessage().contains("Postgres backend only"), thrown.getMessage());
        }
        assertNotNull(JavAIEnvironment.neo4jArticleRepository(),
                "while the plain ArticleRepository, which declares none, stays usable on every backend -- "
                        + "which is why the declared queries live in their own interface");
    }
}
