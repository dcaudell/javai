package dev.xtrafe.javai.e2e;

import dev.xtrafe.javai.e2e.domain.Anthology;
import dev.xtrafe.javai.e2e.domain.AnthologyRepository;
import dev.xtrafe.javai.e2e.domain.Article;
import dev.xtrafe.javai.e2e.domain.ArticleRepository;
import dev.xtrafe.javai.e2e.domain.Comment;
import dev.xtrafe.javai.e2e.domain.Library;
import dev.xtrafe.javai.e2e.domain.LibraryRepository;
import dev.xtrafe.javai.e2e.domain.Shelf;
import dev.xtrafe.javai.e2e.domain.ShelfRepository;
import dev.xtrafe.javai.e2e.environment.JavAIEnvironment;
import dev.xtrafe.javai.e2e.fixtures.ArticleFixtures;
import dev.xtrafe.javai.model.JavAIList;
import dev.xtrafe.javai.model.VectorizableString;
import dev.xtrafe.javai.persistence.JavAIPI;
import dev.xtrafe.javai.tagging.ClassificationResult;
import dev.xtrafe.javai.tagging.JavAITagRepository;
import dev.xtrafe.javai.tagging.RankedTaggableRef;
import dev.xtrafe.javai.tagging.Tag;
import dev.xtrafe.javai.tagging.TagRepository;
import dev.xtrafe.javai.tagging.TagSet;
import dev.xtrafe.javai.tagging.TagSetRepository;
import dev.xtrafe.javai.tagging.TaggableRef;
import dev.xtrafe.javai.tagging.Tagging;
import dev.xtrafe.javai.vector.EmbeddingVector;
import dev.xtrafe.javai.vector.VectorMath;
import org.hibernate.Hibernate;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Taggregate (OMI-302), end to end: {@code javai-tagging}'s own module tests prove the mechanism against
 * flat, mostly-unpersisted fixtures and a hash-based fake embedding provider; this class's job is the
 * composition that suite cannot exercise -- a real, persisted, three-level container domain
 * ({@link Library} of {@link Shelf} of {@link Anthology} of {@link Article}/{@link Comment}) with lazy
 * Hibernate associations, heterogeneous container/member lineage in both directions (plain {@code Anthology}
 * holding woven members; woven {@code Shelf} holding plain containers), and <b>real semantic embeddings</b>,
 * so the concatenated tag-text vector's actual retrieval quality is asserted, not just its storage.
 *
 * <p>Postgres carries the deep coverage, matching the rest of the suite's convention; Neo4j and MongoDB each
 * get the full single-container flow (aggregate, rankedByTags, tag text served). Two Postgres tests use
 * deliberately <em>unpersisted</em> containers: a JPA {@code @OneToMany} join table enforces single-parent
 * membership, so a diamond (one article in two anthologies) is built in memory -- which the ref-keyed
 * Taggregate stores support by construction, and which doubles as the e2e pin that a container needs no
 * persistence of its own on this backend.
 */
class TaggregateE2ETest {

    private static ArticleRepository postgresArticles;
    private static TagRepository postgresTags;
    private static TagSetRepository postgresTagSets;
    private static AnthologyRepository postgresAnthologies;
    private static ShelfRepository postgresShelves;
    private static LibraryRepository postgresLibraries;

    @BeforeAll
    static void configure() {
        JavAIEnvironment.ensureRunning();
        postgresArticles = JavAIEnvironment.postgresArticleRepository();
        postgresTags = JavAIEnvironment.postgresTagRepository();
        postgresTagSets = JavAIEnvironment.postgresTagSetRepository();
        postgresAnthologies = JavAIEnvironment.postgresAnthologyRepository();
        postgresShelves = JavAIEnvironment.postgresShelfRepository();
        postgresLibraries = JavAIEnvironment.postgresLibraryRepository();
    }

    private static TaggableRef refOf(Object instance, java.util.UUID id) {
        return new TaggableRef(instance.getClass().getName(), id);
    }

    private static Tagging aggregateRowFor(List<Tagging> taggings, Tag tag) {
        return taggings.stream()
                .filter(t -> Tagging.SOURCE_AGGREGATE.equals(t.source()) && t.tag().getId().equals(tag.getId()))
                .findFirst().orElseThrow(() -> new AssertionError(
                        "no aggregate row for '" + tag.getSlug() + "' among " + taggings.size() + " taggings"));
    }

    private static boolean hasAggregateRowFor(List<Tagging> taggings, Tag tag) {
        return taggings.stream()
                .anyMatch(t -> Tagging.SOURCE_AGGREGATE.equals(t.source()) && t.tag().getId().equals(tag.getId()));
    }

    private static Article fixtureArticle(ArticleFixtures.Topic topic, int index) {
        List<Article> matching = ArticleFixtures.newArticles().stream()
                .filter(article -> ArticleFixtures.topicOf(article.getTitle()) == topic)
                .toList();
        return matching.get(index);
    }

    // ---- Postgres -----------------------------------------------------------------------------

    /** One container, every {@code @Taggregate} placement at once: the singular reference, both typed
     *  collections (heterogeneous {@code Article} + {@code Comment} membership), tags from two different
     *  {@code TagSet}s aggregating side by side, and the read path itself ({@code taggingsOf}, no explicit
     *  reconcile) detecting the never-reconciled container by snapshot drift. */
    @Test
    void postgresAnthologyAggregatesHeterogeneousMembersAcrossFieldsAndSets() {
        JavAITagRepository tagging = JavAIEnvironment.postgresTagging();
        TagSet perception = postgresTagSets.save(new TagSet("e2e-agg-perception"));
        TagSet editorial = postgresTagSets.save(new TagSet("e2e-agg-editorial"));
        Tag malware = postgresTags.save(new Tag(perception, "en", "Malware Analysis"));
        Tag editorsPick = postgresTags.save(new Tag(editorial, "en", "Editors Pick"));

        Anthology anthology = new Anthology("heterogeneous-members");
        Article feature = fixtureArticle(ArticleFixtures.Topic.CYBERSECURITY, 0);
        Article listed = fixtureArticle(ArticleFixtures.Topic.CYBERSECURITY, 1);
        Comment comment = new Comment("grace", "A comment carrying an editorial tag.");
        anthology.setFeatureArticle(feature);
        anthology.getArticles().add(listed);
        anthology.getComments().add(comment);
        postgresAnthologies.save(anthology);

        tagging.addTag(feature, malware, 0.9);
        tagging.addTag(listed, malware, 0.6);
        tagging.addTag(comment, editorsPick);   // null affinity -> 1.0

        List<Tagging> taggings = tagging.taggingsOf(anthology);
        assertEquals(0.5, aggregateRowFor(taggings, malware).affinity(), 1e-9,
                "(0.9 + 0.6 + 0) / 3 members, across the reference field and both collections");
        assertEquals(1.0 / 3, aggregateRowFor(taggings, editorsPick).affinity(), 1e-9,
                "tags from a second TagSet aggregate side by side, distinguishable at query time by set");
    }

    /** The taggregate-of-taggregate-of-taggregate chain, numerically: an affinity applied three levels down
     *  divides through each tier's member count, a sibling anthology with an untagged member dilutes the
     *  shelf, and the woven/plain lineage mix ({@code Shelf} is genuinely load-time woven; its neighbours
     *  are not) changes nothing. {@code Shelf} also pins the opt-in split: it aggregates but never opted
     *  into tag text, while {@code Library} serves a text derived from tags three levels below it. */
    @Test
    void postgresTaggregateOfTaggregateOfTaggregateComposesNumerically() {
        JavAITagRepository tagging = JavAIEnvironment.postgresTagging();
        TagSet set = postgresTagSets.save(new TagSet("e2e-nesting"));
        Tag topic = postgresTags.save(new Tag(set, "en", "Deep Nesting Topic"));

        Anthology tagged = new Anthology("nesting-tagged");
        Article carrier = fixtureArticle(ArticleFixtures.Topic.SPACE, 0);
        Article silent = fixtureArticle(ArticleFixtures.Topic.SPACE, 1);
        tagged.getArticles().add(carrier);
        tagged.getArticles().add(silent);
        Anthology diluting = new Anthology("nesting-diluting");
        diluting.getArticles().add(fixtureArticle(ArticleFixtures.Topic.SPORTS, 0));

        Shelf shelf = new Shelf("nesting-shelf");
        shelf.getAnthologies().add(tagged);
        shelf.getAnthologies().add(diluting);
        Library library = new Library("nesting-library");
        library.getShelves().add(shelf);
        postgresLibraries.save(library);

        tagging.addTag(carrier, topic, 0.8);

        assertEquals(0.4, aggregateRowFor(tagging.taggingsOf(tagged), topic).affinity(), 1e-9,
                "0.8 over two articles");
        assertTrue(tagging.taggingsOf(diluting).isEmpty(), "no member tags, no aggregate rows");
        assertEquals(0.2, aggregateRowFor(tagging.taggingsOf(shelf), topic).affinity(), 1e-9,
                "the tagged anthology's 0.4 diluted by its untagged sibling");
        assertEquals(0.2, aggregateRowFor(tagging.taggingsOf(library), topic).affinity(), 1e-9,
                "a single-shelf library mirrors its shelf");

        assertNull(tagging.tagText(shelf), "aggregating without concatenate = true must produce no tag text");
        assertEquals("Deep Nesting Topic", tagging.tagText(library),
                "the library's tag text derives, transitively, from a tag applied three levels down");
    }

    /**
     * Update propagation, all three mutation choke points, through all three levels -- and through the
     * <b>sweep alone</b>, verified by a search-only read ({@code taggedWith}) that never recomputes on its
     * own. The loader hands back this test's live objects; the repository-loader idiom (with its lazy-
     * initialization obligation) is exercised separately by
     * {@link #postgresMembershipDriftIsSeenOnReadAndByTheRepositoryLoaderSweep}.
     */
    @Test
    void postgresUpdatesPropagateThroughThreeLevelsViaTheSweep() {
        JavAITagRepository tagging = JavAIEnvironment.postgresTagging();
        TagSet set = postgresTagSets.save(new TagSet("e2e-propagation"));
        Tag topic = postgresTags.save(new Tag(set, "en", "Propagation Topic"));

        Article leaf = fixtureArticle(ArticleFixtures.Topic.COOKING, 0);
        Anthology anthology = new Anthology("propagation-anthology");
        anthology.getArticles().add(leaf);
        Shelf shelf = new Shelf("propagation-shelf");
        shelf.getAnthologies().add(anthology);
        Library library = new Library("propagation-library");
        library.getShelves().add(shelf);
        postgresLibraries.save(library);

        tagging.addTag(leaf, topic, 0.5);
        // Establish the chain bottom-up once -- before any snapshot exists, no choke point can know who
        // contains whom; every later mutation propagates through those snapshots on its own.
        tagging.reconcileTaggregate(anthology);
        tagging.reconcileTaggregate(shelf);
        tagging.reconcileTaggregate(library);
        Map<TaggableRef, Object> live = new HashMap<>();
        live.put(refOf(anthology, anthology.getId()), anthology);
        live.put(refOf(shelf, shelf.getId()), shelf);
        live.put(refOf(library, library.getId()), library);
        TaggableRef libraryRef = refOf(library, library.getId());
        assertEquals(0.5, aggregateRowFor(tagging.taggingsOf(library), topic).affinity(), 1e-9);

        // Affinity update at the leaf (addTag choke point) -> the sweep alone trues the whole chain, proven
        // by a search-only read of the top level.
        tagging.addTag(leaf, topic, 0.9);
        sweepUntilQuiet(tagging, live);
        assertTrue(tagging.taggedWith(topic, List.of(Library.class)).contains(libraryRef));
        assertEquals(0.9, aggregateRowFor(tagging.taggingsOf(library), topic).affinity(), 1e-9);
        assertEquals(0.9, aggregateRowFor(tagging.taggingsOf(shelf), topic).affinity(), 1e-9);

        // Removal at the leaf (removeTag choke point) -> the tag leaves every level.
        tagging.removeTag(leaf, topic);
        sweepUntilQuiet(tagging, live);
        assertFalse(tagging.taggedWith(topic, List.of(Library.class)).contains(libraryRef));
        assertFalse(hasAggregateRowFor(tagging.taggingsOf(anthology), topic));
        assertFalse(hasAggregateRowFor(tagging.taggingsOf(library), topic));

        // Reclassification at the leaf (applyClassification choke point) -> the auto row's affinity
        // reappears at every level.
        tagging.applyClassification(leaf, set,
                List.of(new ClassificationResult.AppliedTag(topic, 0.7, null)));
        sweepUntilQuiet(tagging, live);
        assertEquals(0.7, aggregateRowFor(tagging.taggingsOf(anthology), topic).affinity(), 1e-9);
        assertEquals(0.7, aggregateRowFor(tagging.taggingsOf(shelf), topic).affinity(), 1e-9);
        assertEquals(0.7, aggregateRowFor(tagging.taggingsOf(library), topic).affinity(), 1e-9);
    }

    /** Drains the pending set completely: one sweep pass claims oldest-first, and a pass that reconciled
     *  something may itself re-mark parents (a recompute that changed rows marks its containers), so loop
     *  until a pass finds nothing. Aggregates belonging to other tests claim as unloadable and are dropped,
     *  which is the documented behaviour. */
    private static void sweepUntilQuiet(JavAITagRepository tagging, Map<TaggableRef, Object> live) {
        int guard = 0;
        while (tagging.reconcilePendingTaggregates(100, live::get) > 0) {
            if (++guard > 10) {
                throw new AssertionError("pending sweep did not converge within 10 passes");
            }
        }
    }

    /**
     * A diamond -- one article in two anthologies, both on one shelf -- built from <em>unpersisted</em>
     * containers over persisted members (see the class javadoc for why), proving membership is genuinely
     * ref-keyed and that a leaf update fans out to every containing aggregate, not just the first one the
     * snapshot lookup happens to return.
     */
    @Test
    void postgresDiamondMembershipFansUpdatesOutToBothContainers() {
        JavAITagRepository tagging = JavAIEnvironment.postgresTagging();
        TagSet set = postgresTagSets.save(new TagSet("e2e-diamond"));
        Tag shared = postgresTags.save(new Tag(set, "en", "Diamond Shared"));
        Tag solo = postgresTags.save(new Tag(set, "en", "Diamond Solo"));

        Article sharedArticle = postgresArticles.save(fixtureArticle(ArticleFixtures.Topic.SPORTS, 1));
        Article leftOnly = postgresArticles.save(fixtureArticle(ArticleFixtures.Topic.SPORTS, 2));
        Article rightOnly = postgresArticles.save(fixtureArticle(ArticleFixtures.Topic.SPORTS, 3));
        tagging.addTag(sharedArticle, shared, 0.6);
        tagging.addTag(rightOnly, solo, 0.8);

        Anthology left = new Anthology("diamond-left");
        left.getArticles().add(sharedArticle);
        left.getArticles().add(leftOnly);
        Anthology right = new Anthology("diamond-right");
        right.getArticles().add(sharedArticle);
        right.getArticles().add(rightOnly);
        Shelf shelf = new Shelf("diamond-shelf");
        shelf.getAnthologies().add(left);
        shelf.getAnthologies().add(right);

        assertEquals(0.3, aggregateRowFor(tagging.taggingsOf(left), shared).affinity(), 1e-9);
        List<Tagging> rightRows = tagging.taggingsOf(right);
        assertEquals(0.3, aggregateRowFor(rightRows, shared).affinity(), 1e-9);
        assertEquals(0.4, aggregateRowFor(rightRows, solo).affinity(), 1e-9);
        List<Tagging> shelfRows = tagging.taggingsOf(shelf);
        assertEquals(0.3, aggregateRowFor(shelfRows, shared).affinity(), 1e-9, "(0.3 + 0.3) / 2 anthologies");
        assertEquals(0.2, aggregateRowFor(shelfRows, solo).affinity(), 1e-9);

        // The shared leaf updates once; both sides of the diamond (and the shelf above them) follow.
        tagging.addTag(sharedArticle, shared, 1.0);
        assertEquals(0.5, aggregateRowFor(tagging.taggingsOf(left), shared).affinity(), 1e-9);
        assertEquals(0.5, aggregateRowFor(tagging.taggingsOf(right), shared).affinity(), 1e-9);
        assertEquals(0.5, aggregateRowFor(tagging.taggingsOf(shelf), shared).affinity(), 1e-9);
    }

    /**
     * Two tags whose <b>display names</b> collide while their slugs differ -- one authored in English
     * ({@code firewall}), one authored in French and localized into English afterward
     * ({@code le-pare-feu}, slug fixed at creation, display {@code "Firewall"} added later). They must stay
     * distinct rows in the aggregate, sum separately in {@code rankedByTags}, and render as two entries in
     * a deterministic tag text -- identical words, slug-tie-broken order, run after run.
     */
    @Test
    void postgresSameDisplayNameDifferentSlugTagsStayDistinctEverywhere() {
        JavAITagRepository tagging = JavAIEnvironment.postgresTagging();
        TagSet set = postgresTagSets.save(new TagSet("e2e-collision"));
        Tag english = postgresTags.save(new Tag(set, "en", "Firewall"));
        Tag french = new Tag(set, "fr", "Le Pare-feu");
        french.setLocalizedName("en", "Firewall");   // display collides; the slug never re-derives
        french = postgresTags.save(french);
        assertEquals("firewall", english.getSlug());
        assertEquals("le-pare-feu", french.getSlug());

        Article article = postgresArticles.save(fixtureArticle(ArticleFixtures.Topic.CYBERSECURITY, 2));
        tagging.addTag(article, english, 0.9);
        tagging.addTag(article, french, 0.4);
        Anthology anthology = new Anthology("collision-anthology");
        anthology.getArticles().add(article);
        postgresAnthologies.save(anthology);

        List<Tagging> taggings = tagging.taggingsOf(anthology);
        assertEquals(0.9, aggregateRowFor(taggings, english).affinity(), 1e-9);
        assertEquals(0.4, aggregateRowFor(taggings, french).affinity(), 1e-9,
                "same display name, different slug: two distinct aggregate rows, never merged");

        assertEquals("Firewall, Firewall", tagging.tagText(anthology),
                "both entries render; affinity orders them; determinism holds despite identical words");

        List<RankedTaggableRef> ranked = tagging.rankedByTags(
                List.of(english, french), List.of(Anthology.class), 10);
        TaggableRef anthologyRef = refOf(anthology, anthology.getId());
        RankedTaggableRef hit = ranked.stream().filter(r -> r.ref().equals(anthologyRef)).findFirst().orElseThrow();
        assertEquals(1.3, hit.similarity(), 1e-9, "0.9 + 0.4 -- each colliding tag scores separately");
    }

    /**
     * The point of the tag-text vector, measured: with <b>real</b> embeddings, a container whose members'
     * tags are about cooking must rank nearer a natural-language cooking query than a security one, in both
     * directions, and win the head-to-head ranking in the shared index. The queries deliberately share no
     * vocabulary with the tag display names -- this passes on semantic quality or not at all.
     */
    @Test
    void postgresConcatenatedTagTextVectorQualityWithRealEmbeddings() {
        JavAITagRepository tagging = JavAIEnvironment.postgresTagging();
        TagSet set = postgresTagSets.save(new TagSet("e2e-text-quality"));
        Tag homeCooking = postgresTags.save(new Tag(set, "en", "Home Cooking"));
        Tag mealPrep = postgresTags.save(new Tag(set, "en", "Recipes And Meal Prep"));
        Tag cybersecurity = postgresTags.save(new Tag(set, "en", "Cybersecurity"));
        Tag ransomware = postgresTags.save(new Tag(set, "en", "Ransomware Attack"));

        Article cookingOne = postgresArticles.save(fixtureArticle(ArticleFixtures.Topic.COOKING, 1));
        Article cookingTwo = postgresArticles.save(fixtureArticle(ArticleFixtures.Topic.COOKING, 2));
        tagging.addTag(cookingOne, homeCooking, 0.95);
        tagging.addTag(cookingTwo, mealPrep, 0.9);
        Anthology cookingAnthology = new Anthology("quality-cooking");
        cookingAnthology.getArticles().add(cookingOne);
        cookingAnthology.getArticles().add(cookingTwo);
        postgresAnthologies.save(cookingAnthology);
        tagging.reconcileTaggregate(cookingAnthology);

        Article securityOne = postgresArticles.save(fixtureArticle(ArticleFixtures.Topic.CYBERSECURITY, 3));
        tagging.addTag(securityOne, cybersecurity, 0.95);
        tagging.addTag(securityOne, ransomware, 0.9);
        Anthology securityAnthology = new Anthology("quality-security");
        securityAnthology.getArticles().add(securityOne);
        postgresAnthologies.save(securityAnthology);
        tagging.reconcileTaggregate(securityAnthology);

        EmbeddingVector cookingQuery = new VectorizableString(
                "what should I make for dinner tonight with the ingredients in my fridge").vector();
        EmbeddingVector securityQuery = new VectorizableString(
                "criminals broke into the company network and encrypted every file").vector();
        EmbeddingVector cookingText = tagging.tagTextVector(cookingAnthology);
        EmbeddingVector securityText = tagging.tagTextVector(securityAnthology);
        assertFalse(cookingText.isAbsent());
        assertFalse(securityText.isAbsent());

        assertTrue(VectorMath.cosineSimilarity(cookingText, cookingQuery)
                        > VectorMath.cosineSimilarity(cookingText, securityQuery),
                "the cooking anthology's tag text must read nearer a cooking query than a security one");
        assertTrue(VectorMath.cosineSimilarity(securityText, securityQuery)
                        > VectorMath.cosineSimilarity(securityText, cookingQuery),
                "and the security anthology's the reverse");

        // Head to head in the shared index: other tests' entries may rank in between, so assert relative
        // order of these two rather than absolute first place.
        TaggableRef cookingRef = refOf(cookingAnthology, cookingAnthology.getId());
        TaggableRef securityRef = refOf(securityAnthology, securityAnthology.getId());
        JavAIList<TaggableRef> byCookingQuery = tagging.tagTextIndex().nearestN(cookingQuery, 50);
        assertTrue(byCookingQuery.indexOf(cookingRef) >= 0, "cooking anthology must be retrievable by language");
        int cookingRank = byCookingQuery.indexOf(cookingRef);
        int securityRank = byCookingQuery.indexOf(securityRef);
        assertTrue(securityRank < 0 || cookingRank < securityRank,
                "a cooking query must rank the cooking anthology above the security one");
    }

    /**
     * Membership drift on a <b>persisted</b> container: a member added to the real Hibernate-owned
     * collection is invisible to every choke point, is seen by a read holding the (reloaded, lazy)
     * container inside a unit of work, and is seen by the sweep when the loader is the documented
     * repository idiom -- which must initialize the lazy {@code @Taggregate} collections before returning,
     * since the recompute's reflective walk runs after the loading session closes.
     */
    @Test
    void postgresMembershipDriftIsSeenOnReadAndByTheRepositoryLoaderSweep() {
        JavAITagRepository tagging = JavAIEnvironment.postgresTagging();
        TagSet set = postgresTagSets.save(new TagSet("e2e-drift"));
        Tag original = postgresTags.save(new Tag(set, "en", "Drift Original"));
        Tag late = postgresTags.save(new Tag(set, "en", "Drift Late"));

        Article first = postgresArticles.save(fixtureArticle(ArticleFixtures.Topic.SPACE, 2));
        tagging.addTag(first, original, 0.8);
        Anthology anthology = new Anthology("drift-anthology");
        anthology.getArticles().add(first);
        postgresAnthologies.save(anthology);
        tagging.reconcileTaggregate(anthology);
        assertEquals(0.8, aggregateRowFor(tagging.taggingsOf(anthology), original).affinity(), 1e-9);

        Article latecomer = postgresArticles.save(fixtureArticle(ArticleFixtures.Topic.SPACE, 3));
        tagging.addTag(latecomer, late, 0.6);
        anthology.getArticles().add(latecomer);
        postgresAnthologies.save(anthology);

        // Search-only reads stay on the last-reconciled state...
        TaggableRef anthologyRef = refOf(anthology, anthology.getId());
        assertFalse(tagging.taggedWith(late, List.of(Anthology.class)).contains(anthologyRef));

        // ...a read holding the reloaded container -- lazy collections and all -- detects the drift...
        List<Tagging> reloadedRows = JavAIPI.inTransaction(JavAIEnvironment.postgresConfig(), () ->
                tagging.taggingsOf(postgresAnthologies.findById(anthology.getId()).orElseThrow()));
        assertEquals(0.4, aggregateRowFor(reloadedRows, original).affinity(), 1e-9, "0.8 over two members now");
        assertEquals(0.3, aggregateRowFor(reloadedRows, late).affinity(), 1e-9);

        // ...and so does the sweep, with the adopter's own repositories as the loader. The loader
        // initializes the lazy member collections inside its own unit of work -- the reflective walk runs
        // detached, after this session has closed.
        tagging.markTaggregateStale(anthology);
        int reconciled = tagging.reconcilePendingTaggregates(100, ref -> {
            if (!ref.taggableType().equals(Anthology.class.getName())) {
                return null;
            }
            return JavAIPI.inTransaction(JavAIEnvironment.postgresConfig(), () -> {
                Anthology loaded = postgresAnthologies.findById(ref.taggableId()).orElse(null);
                if (loaded != null) {
                    Hibernate.initialize(loaded.getArticles());
                    Hibernate.initialize(loaded.getComments());
                }
                return loaded;
            });
        });
        assertTrue(reconciled >= 1);
        assertTrue(tagging.taggedWith(late, List.of(Anthology.class)).contains(anthologyRef),
                "after the sweep, the search path sees the drifted-in member's tag");

        List<RankedTaggableRef> ranked = tagging.rankedByTags(List.of(original, late), List.of(Anthology.class), 10);
        RankedTaggableRef hit = ranked.stream().filter(r -> r.ref().equals(anthologyRef)).findFirst().orElseThrow();
        assertEquals(0.7, hit.similarity(), 1e-9, "0.4 + 0.3 -- rankedByTags sums the aggregate rows");
    }

    // ---- Neo4j --------------------------------------------------------------------------------

    @Test
    void neo4jAnthologyAggregatesRanksAndServesTagText() {
        JavAITagRepository tagging = JavAIEnvironment.neo4jTagging();
        TagRepository tags = JavAIEnvironment.neo4jTagRepository();
        TagSetRepository tagSets = JavAIEnvironment.neo4jTagSetRepository();
        ArticleRepository articles = JavAIEnvironment.neo4jArticleRepository();
        AnthologyRepository anthologies = JavAIEnvironment.neo4jAnthologyRepository();

        TagSet set = tagSets.save(new TagSet("e2e-neo4j-taggregate"));
        Tag strong = tags.save(new Tag(set, "en", "Neo4j Strong Topic"));
        Tag binary = tags.save(new Tag(set, "en", "Neo4j Binary Topic"));

        Article one = articles.save(fixtureArticle(ArticleFixtures.Topic.SPACE, 0));
        Article two = articles.save(fixtureArticle(ArticleFixtures.Topic.SPACE, 1));
        tagging.addTag(one, strong, 0.8);
        tagging.addTag(two, binary);   // null affinity -> 1.0

        Anthology anthology = new Anthology("neo4j-anthology");
        anthology.getArticles().add(one);
        anthology.getArticles().add(two);
        anthologies.save(anthology);
        tagging.reconcileTaggregate(anthology);

        List<Tagging> taggings = tagging.taggingsOf(anthology);
        assertEquals(0.4, aggregateRowFor(taggings, strong).affinity(), 1e-9);
        assertEquals(0.5, aggregateRowFor(taggings, binary).affinity(), 1e-9);

        List<RankedTaggableRef> ranked = tagging.rankedByTags(List.of(strong, binary), List.of(Anthology.class), 10);
        TaggableRef anthologyRef = new TaggableRef(Anthology.class.getName(), anthology.getId());
        RankedTaggableRef hit = ranked.stream().filter(r -> r.ref().equals(anthologyRef)).findFirst().orElseThrow();
        assertEquals(0.9, hit.similarity(), 1e-9);

        assertEquals("Neo4j Binary Topic, Neo4j Strong Topic", tagging.tagText(anthology),
                "affinity 0.5 outranks 0.4; display names, never slugs");
        assertFalse(tagging.tagTextVector(anthology).isAbsent());
    }

    // ---- MongoDB ------------------------------------------------------------------------------

    @Test
    void mongoAnthologyAggregatesRanksAndServesTagText() {
        JavAITagRepository tagging = JavAIEnvironment.mongoTagging();
        TagRepository tags = JavAIEnvironment.mongoTagRepository();
        TagSetRepository tagSets = JavAIEnvironment.mongoTagSetRepository();
        ArticleRepository articles = JavAIEnvironment.mongoArticleRepository();
        AnthologyRepository anthologies = JavAIEnvironment.mongoAnthologyRepository();

        TagSet set = tagSets.save(new TagSet("e2e-mongo-taggregate"));
        Tag strong = tags.save(new Tag(set, "en", "Mongo Strong Topic"));
        Tag binary = tags.save(new Tag(set, "en", "Mongo Binary Topic"));

        Article one = articles.save(fixtureArticle(ArticleFixtures.Topic.COOKING, 3));
        Article two = articles.save(fixtureArticle(ArticleFixtures.Topic.SPORTS, 0));
        tagging.addTag(one, strong, 0.8);
        tagging.addTag(two, binary);   // null affinity -> 1.0

        Anthology anthology = new Anthology("mongo-anthology");
        anthology.getArticles().add(one);
        anthology.getArticles().add(two);
        anthologies.save(anthology);
        tagging.reconcileTaggregate(anthology);

        List<Tagging> taggings = tagging.taggingsOf(anthology);
        assertEquals(0.4, aggregateRowFor(taggings, strong).affinity(), 1e-9);
        assertEquals(0.5, aggregateRowFor(taggings, binary).affinity(), 1e-9);

        List<RankedTaggableRef> ranked = tagging.rankedByTags(List.of(strong, binary), List.of(Anthology.class), 10);
        TaggableRef anthologyRef = new TaggableRef(Anthology.class.getName(), anthology.getId());
        RankedTaggableRef hit = ranked.stream().filter(r -> r.ref().equals(anthologyRef)).findFirst().orElseThrow();
        assertEquals(0.9, hit.similarity(), 1e-9);

        assertEquals("Mongo Binary Topic, Mongo Strong Topic", tagging.tagText(anthology));
        assertFalse(tagging.tagTextVector(anthology).isAbsent());
    }
}
