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
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Taggregate end to end, as a downstream consumer writes it (OMI-302, rebuilt on containment by OMI-304):
 * a real persisted three-level container domain ({@link Library} of {@link Shelf} of {@link Anthology} of
 * {@link Article}/{@link Comment}) with lazy Hibernate-owned associations, real embeddings, and all three
 * backends.
 *
 * <p>⚠️ <b>The adopter-facing point of this file is what is absent from it.</b> There is no reconciler, no
 * loader, no sweep, no bootstrap pass and no type→repository map -- the whole ~190-line
 * {@code TaggregateReconciler} an adopter used to need. Every test tags a member and asserts the
 * containers. That is the entire API.
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
        return ArticleFixtures.newArticles().stream()
                .filter(article -> ArticleFixtures.topicOf(article.getTitle()) == topic)
                .toList().get(index);
    }

    // ---- Postgres -----------------------------------------------------------------------------

    /** Heterogeneous membership across both collection fields and two TagSets, on a container nothing has
     *  reconciled -- one {@code addTag} per member is the whole interaction. */
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
                "a second TagSet aggregates side by side");
    }

    /**
     * The taggregate-of-taggregate-of-taggregate chain, from one tag applied three levels down and nothing
     * else. Under the snapshot design this needed a bootstrap pass over every level first, in the right
     * order; containment needs none, and the drain carries the change up on its own.
     */
    @Test
    void postgresThreeLevelNestingComposesFromOneTagCall() {
        JavAITagRepository tagging = JavAIEnvironment.postgresTagging();
        TagSet set = postgresTagSets.save(new TagSet("e2e-nesting"));
        Tag topic = postgresTags.save(new Tag(set, "en", "Deep Nesting Topic"));

        Anthology tagged = new Anthology("nesting-tagged");
        Article carrier = fixtureArticle(ArticleFixtures.Topic.SPACE, 0);
        tagged.getArticles().add(carrier);
        tagged.getArticles().add(fixtureArticle(ArticleFixtures.Topic.SPACE, 1));
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
        assertEquals(0.2, aggregateRowFor(tagging.taggingsOf(library), topic).affinity(), 1e-9);

        assertNull(tagging.tagText(shelf), "aggregating without concatenate = true produces no tag text");
        assertEquals("Deep Nesting Topic", tagging.tagText(library),
                "the library's tag text derives from a tag applied three levels down");
    }

    /** Every mutation choke point drives the chain, and removal propagates back out of it. */
    @Test
    void postgresUpdatesPropagateThroughEveryChokePoint() {
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
        assertEquals(0.5, aggregateRowFor(tagging.taggingsOf(library), topic).affinity(), 1e-9);

        tagging.addTag(leaf, topic, 0.9);   // affinity update
        assertEquals(0.9, aggregateRowFor(tagging.taggingsOf(library), topic).affinity(), 1e-9);
        assertEquals(0.9, aggregateRowFor(tagging.taggingsOf(shelf), topic).affinity(), 1e-9);

        tagging.removeTag(leaf, topic);
        assertFalse(hasAggregateRowFor(tagging.taggingsOf(anthology), topic));
        assertFalse(hasAggregateRowFor(tagging.taggingsOf(library), topic));

        tagging.applyClassification(leaf, set,
                List.of(new ClassificationResult.AppliedTag(topic, 0.7, null)));
        assertEquals(0.7, aggregateRowFor(tagging.taggingsOf(library), topic).affinity(), 1e-9);
    }

    /** A diamond: one article in two anthologies on one shelf. One leaf update reaches both sides, which is
     *  what a containment query gives and a first-container-wins lookup would not. */
    @Test
    void postgresDiamondMembershipFansUpdatesOutToBothContainers() {
        JavAITagRepository tagging = JavAIEnvironment.postgresTagging();
        TagSet set = postgresTagSets.save(new TagSet("e2e-diamond"));
        Tag shared = postgresTags.save(new Tag(set, "en", "Diamond Shared"));

        Article sharedArticle = postgresArticles.save(fixtureArticle(ArticleFixtures.Topic.SPORTS, 1));
        Anthology left = new Anthology("diamond-left");
        left.getArticles().add(sharedArticle);
        left.getArticles().add(fixtureArticle(ArticleFixtures.Topic.SPORTS, 2));
        Anthology right = new Anthology("diamond-right");
        right.getArticles().add(sharedArticle);
        Shelf shelf = new Shelf("diamond-shelf");
        shelf.getAnthologies().add(left);
        shelf.getAnthologies().add(right);
        postgresShelves.save(shelf);

        tagging.addTag(sharedArticle, shared, 0.6);

        assertEquals(0.3, aggregateRowFor(tagging.taggingsOf(left), shared).affinity(), 1e-9, "0.6 over two");
        assertEquals(0.6, aggregateRowFor(tagging.taggingsOf(right), shared).affinity(), 1e-9, "0.6 over one");
        assertEquals(0.45, aggregateRowFor(tagging.taggingsOf(shelf), shared).affinity(), 1e-9,
                "(0.3 + 0.6) / 2 anthologies -- both sides of the diamond reached");
    }

    /**
     * Two tags with identical display names but different slugs -- one authored in English, one in French
     * and localized afterward -- stay distinct rows through aggregation, ranking and rendered text.
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
        Anthology anthology = new Anthology("collision-anthology");
        anthology.getArticles().add(article);
        postgresAnthologies.save(anthology);

        tagging.addTag(article, english, 0.9);
        tagging.addTag(article, french, 0.4);

        List<Tagging> taggings = tagging.taggingsOf(anthology);
        assertEquals(0.9, aggregateRowFor(taggings, english).affinity(), 1e-9);
        assertEquals(0.4, aggregateRowFor(taggings, french).affinity(), 1e-9,
                "same display name, different slug: two distinct rows, never merged");
        assertEquals("Firewall, Firewall", tagging.tagText(anthology),
                "both entries render; affinity orders them; determinism holds despite identical words");

        TaggableRef ref = new TaggableRef(Anthology.class.getName(), anthology.getId());
        RankedTaggableRef hit = tagging.rankedByTags(List.of(english, french), List.of(Anthology.class), 10)
                .stream().filter(r -> r.ref().equals(ref)).findFirst().orElseThrow();
        assertEquals(1.3, hit.similarity(), 1e-9, "0.9 + 0.4 -- each colliding tag scores separately");
    }

    /**
     * The tag-text vector's actual retrieval quality, with real embeddings: a container whose members' tags
     * are about cooking must read nearer a natural-language cooking query than a security one, in both
     * directions. The queries share no vocabulary with the tag names.
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
        Anthology cookingAnthology = new Anthology("quality-cooking");
        cookingAnthology.getArticles().add(cookingOne);
        cookingAnthology.getArticles().add(cookingTwo);
        postgresAnthologies.save(cookingAnthology);
        tagging.addTag(cookingOne, homeCooking, 0.95);
        tagging.addTag(cookingTwo, mealPrep, 0.9);

        Article securityOne = postgresArticles.save(fixtureArticle(ArticleFixtures.Topic.CYBERSECURITY, 3));
        Anthology securityAnthology = new Anthology("quality-security");
        securityAnthology.getArticles().add(securityOne);
        postgresAnthologies.save(securityAnthology);
        tagging.addTag(securityOne, cybersecurity, 0.95);
        tagging.addTag(securityOne, ransomware, 0.9);

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

        TaggableRef cookingRef = new TaggableRef(Anthology.class.getName(), cookingAnthology.getId());
        TaggableRef securityRef = new TaggableRef(Anthology.class.getName(), securityAnthology.getId());
        JavAIList<TaggableRef> byCookingQuery = tagging.tagTextIndex().nearestN(cookingQuery, 50);
        int cookingRank = byCookingQuery.indexOf(cookingRef);
        int securityRank = byCookingQuery.indexOf(securityRef);
        assertTrue(cookingRank >= 0, "the cooking anthology must be retrievable by language");
        assertTrue(securityRank < 0 || cookingRank < securityRank,
                "a cooking query must rank the cooking anthology above the security one");
    }

    /**
     * A member added to a container after the fact changes what the aggregate <em>should</em> say (the mean
     * dilutes), and no tag mutation happened to notice. This pins the honest consequence of OMI-304's "the
     * choke points are the only trigger": the next tag mutation beneath that container corrects it, and
     * {@code rebuildTaggregates()} corrects it without one.
     */
    @Test
    void postgresMembershipChangeIsCorrectedByTheNextTagMutationOrByRebuild() {
        JavAITagRepository tagging = JavAIEnvironment.postgresTagging();
        TagSet set = postgresTagSets.save(new TagSet("e2e-membership"));
        Tag topic = postgresTags.save(new Tag(set, "en", "Membership Topic"));

        Article first = postgresArticles.save(fixtureArticle(ArticleFixtures.Topic.SPACE, 2));
        Anthology anthology = new Anthology("membership-anthology");
        anthology.getArticles().add(first);
        postgresAnthologies.save(anthology);
        tagging.addTag(first, topic, 0.8);
        assertEquals(0.8, aggregateRowFor(tagging.taggingsOf(anthology), topic).affinity(), 1e-9);

        Article latecomer = postgresArticles.save(fixtureArticle(ArticleFixtures.Topic.SPACE, 3));
        anthology.getArticles().add(latecomer);
        postgresAnthologies.save(anthology);

        // Nothing was tagged, so nothing recomputed -- the stored value still describes one member.
        assertEquals(0.8, aggregateRowFor(tagging.taggingsOf(anthology), topic).affinity(), 1e-9);

        assertTrue(tagging.rebuildTaggregates() > 0);
        assertEquals(0.4, aggregateRowFor(tagging.taggingsOf(anthology), topic).affinity(), 1e-9,
                "rebuild reads the join table, so the new member dilutes exactly as it should");
    }

    // ---- Neo4j / MongoDB ----------------------------------------------------------------------

    @Test
    void neo4jAnthologyAggregatesFromOneTagCall() {
        assertAggregatesOnBackend(JavAIEnvironment.neo4jTagging(), JavAIEnvironment.neo4jTagRepository(),
                JavAIEnvironment.neo4jTagSetRepository(), JavAIEnvironment.neo4jArticleRepository(),
                JavAIEnvironment.neo4jAnthologyRepository(), "neo4j");
    }

    @Test
    void mongoAnthologyAggregatesFromOneTagCall() {
        assertAggregatesOnBackend(JavAIEnvironment.mongoTagging(), JavAIEnvironment.mongoTagRepository(),
                JavAIEnvironment.mongoTagSetRepository(), JavAIEnvironment.mongoArticleRepository(),
                JavAIEnvironment.mongoAnthologyRepository(), "mongo");
    }

    /** The same contract on every backend: save a container, tag a member, read the container's aggregate.
     *  Containment is answered from relationships on Neo4j and reference arrays on MongoDB, but nothing a
     *  caller does differs. */
    private static void assertAggregatesOnBackend(JavAITagRepository tagging, TagRepository tags,
            TagSetRepository tagSets, ArticleRepository articles, AnthologyRepository anthologies,
            String backend) {
        TagSet set = tagSets.save(new TagSet("e2e-" + backend + "-taggregate"));
        Tag strong = tags.save(new Tag(set, "en", backend + " Strong Topic"));
        Tag binary = tags.save(new Tag(set, "en", backend + " Binary Topic"));

        Article one = articles.save(fixtureArticle(ArticleFixtures.Topic.SPACE, 0));
        Article two = articles.save(fixtureArticle(ArticleFixtures.Topic.SPACE, 1));
        Anthology anthology = new Anthology(backend + "-anthology");
        anthology.getArticles().add(one);
        anthology.getArticles().add(two);
        anthologies.save(anthology);

        tagging.addTag(one, strong, 0.8);
        tagging.addTag(two, binary);   // null affinity -> 1.0

        List<Tagging> taggings = tagging.taggingsOf(anthology);
        assertEquals(0.4, aggregateRowFor(taggings, strong).affinity(), 1e-9, backend);
        assertEquals(0.5, aggregateRowFor(taggings, binary).affinity(), 1e-9, backend);

        UUID id = anthology.getId();
        TaggableRef ref = new TaggableRef(Anthology.class.getName(), id);
        RankedTaggableRef hit = tagging.rankedByTags(List.of(strong, binary), List.of(Anthology.class), 10)
                .stream().filter(r -> r.ref().equals(ref)).findFirst().orElseThrow();
        assertEquals(0.9, hit.similarity(), 1e-9, backend);
    }
}
