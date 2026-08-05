package dev.xtrafe.javai.tagging;

import dev.xtrafe.javai.model.JavAIRuntime;
import dev.xtrafe.javai.persistence.JavAIPI;
import dev.xtrafe.javai.persistence.JavAIPersistenceConfig;
import dev.xtrafe.javai.persistence.Ranked;
import dev.xtrafe.javai.vector.EmbeddingVector;
import dev.xtrafe.javai.vector.testsupport.ScriptedEmbeddingProvider;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Narrowed vector search over a real tag catalogue (OMI-230).
 *
 * <h2>Why tagging, specifically</h2>
 *
 * A tag catalogue is the shape the feature exists for, and the one where over-fetching hurts most. Tags are
 * numerous, they live in sets, and "the tags nearest this text" is almost never the actual question -- "the
 * tags nearest this text <em>in this set</em>" is. Before this, that meant fetching a large N across the
 * whole catalogue and discarding everything from the other sets, with no way to know whether N was enough.
 *
 * <p>It is also the sharpest available test of the ordering contract, because the fixture can make the
 * unwanted set win outright: every tag in the <b>colours</b> set is placed nearer the reference than every
 * tag in <b>materials</b>. Asking for the nearest 2 materials must therefore return 2 materials -- a
 * rank-then-filter implementation returns none at all.
 *
 * <p>These are shipped, pre-woven {@code @JavAIVectorizable} entities rather than a purpose-built fixture,
 * so this also confirms the feature works against a real weaved class and a real {@code @ManyToOne} back
 * reference, not only against this project's hand-written test stand-ins.
 */
@Testcontainers
class NarrowedTagSearchTest {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    private static ScriptedEmbeddingProvider provider;
    private static NarrowedTagSearchRepository tags;
    private static NarrowedTagSetSearchRepository tagSets;
    private static TagSet colours;
    private static TagSet materials;

    @BeforeAll
    static void seed() {
        // Every colour nearer than every material, deliberately -- see the class javadoc.
        provider = new ScriptedEmbeddingProvider()
                .at("crimson", 2).at("scarlet", 4).at("azure", 6).at("emerald", 8)
                .at("oak", 40).at("walnut", 45).at("granite", 50).at("marble", 55)
                .at("colours", 20).at("materials", 60);
        JavAIRuntime.configureEmbeddingProvider(provider);

        JavAIPersistenceConfig config = JavAIPersistenceConfig.builder()
                .backend(JavAIPersistenceConfig.Backend.POSTGRES)
                .postgresUrl(postgres.getJdbcUrl())
                .postgresUsername(postgres.getUsername())
                .postgresPassword(postgres.getPassword())
                .build();
        TagSetRepository plainTagSets = JavAIPI.repository(TagSetRepository.class, config);
        tagSets = JavAIPI.repository(NarrowedTagSetSearchRepository.class, config);
        tags = JavAIPI.repository(NarrowedTagSearchRepository.class, config);

        colours = plainTagSets.save(new TagSet("colours"));
        materials = plainTagSets.save(new TagSet("materials"));

        saveTag(colours, "crimson", "a deep red");
        saveTag(colours, "scarlet", null);
        saveTag(colours, "azure", "a vivid blue");
        saveTag(colours, "emerald", null);
        saveTag(materials, "oak", "a hardwood");
        saveTag(materials, "walnut", null);
        saveTag(materials, "granite", "an igneous rock");
        saveTag(materials, "marble", null);

        // Re-saved after their tags exist: a Tag registers itself with its TagSet on construction,
        // so the sets' own membership rows are only complete once every tag has been created.
        plainTagSets.save(colours);
        plainTagSets.save(materials);
    }

    private static void saveTag(TagSet set, String name, String description) {
        Tag tag = new Tag(set, "en", name);
        if (description != null) {
            tag.setDescription(description);
        }
        tags.save(tag);
    }

    private static EmbeddingVector reference() {
        return provider.reference();
    }

    private static List<String> slugs(List<Tag> hits) {
        return hits.stream().map(Tag::getSlug).toList();
    }

    @Test
    @DisplayName("unnarrowed, the colours win the whole ranking")
    void unnarrowedSearchIsDominatedByOneSet() {
        assertEquals(List.of("crimson", "scarlet", "azure", "emerald"),
                slugs(tags.findNearestBySlugVector(reference(), 4)),
                "the fixture is arranged so every colour outranks every material -- which is what makes the "
                        + "narrowed cases below meaningful");
    }

    @Test
    @DisplayName("narrowing to a set returns that set's nearest, not the leftovers of a global ranking")
    void narrowingToASetReturnsThatSetsNearest() {
        List<Tag> hits = tags.findNearestBySlugVectorAndTagSetIdIs(reference(), 2, materials.getId());

        assertEquals(2, hits.size(), "asking for the nearest 2 materials must yield 2 -- a rank-then-filter "
                + "implementation returns none, because the top 2 overall are both colours");
        assertEquals(List.of("oak", "walnut"), slugs(hits));
    }

    @Test
    @DisplayName("narrowing through a nested property path works, and carries similarities")
    void narrowingThroughANestedPathWorks() {
        List<Ranked<Tag>> hits = tags.findNearestBySlugVectorAndTagSetSlugIs(reference(), 3, "materials");

        assertEquals(List.of("oak", "walnut", "granite"),
                hits.stream().map(hit -> hit.entity().getSlug()).toList());
        assertEquals(ScriptedEmbeddingProvider.expectedSimilarity(40), hits.get(0).similarity(), 1e-4);
    }

    @Test
    @DisplayName("a scalar predicate narrows across sets")
    void scalarPredicateNarrowsAcrossSets() {
        List<Tag> hits = tags.findNearestBySlugVectorAndDescriptionIsNotNull(reference(), 10);

        assertEquals(List.of("crimson", "azure", "oak", "granite"), slugs(hits));
    }

    @Test
    @DisplayName("the builder narrows a tag search the same way the method name does")
    void builderNarrowsTheSameWay() {
        List<String> viaMethodName =
                slugs(tags.findNearestBySlugVectorAndTagSetIdIs(reference(), 2, materials.getId()));
        List<String> viaBuilder = slugs(tags.nearestBy("slug")
                .to(reference())
                .where("tagSet.id").is(materials.getId())
                .limit(2)
                .results());

        assertEquals(viaMethodName, viaBuilder);
    }

    @Test
    @DisplayName("the builder composes set membership with a further condition, and pages within it")
    void builderComposesAndPages() {
        List<Tag> described = tags.nearestBy("slug")
                .to(reference())
                .where("tagSet.id").is(colours.getId())
                .and("description").isNotNull()
                .limit(10)
                .results();
        assertEquals(List.of("crimson", "azure"), slugs(described));

        List<Tag> secondPage = tags.nearestBy("slug")
                .to(reference())
                .where("tagSet.id").is(colours.getId())
                .offset(2)
                .limit(2)
                .results();
        assertEquals(List.of("azure", "emerald"), slugs(secondPage),
                "the offset must count colours, not tags in general");
    }

    @Test
    @DisplayName("a set with nothing in it yields nothing rather than the nearest from elsewhere")
    void anEmptySetYieldsNothing() {
        TagSet unused = new TagSet("unused");

        assertTrue(tags.findNearestBySlugVectorAndTagSetIdIs(reference(), 10, unused.getId()).isEmpty());
    }

    /**
     * Narrowing through a <b>to-many</b> path, which takes a different route inside the backend than every
     * other case here: {@code TagSet.tags} is declared by the concrete {@code JavAIArrayList} type, so it
     * lives in {@code javai_collection_members} and a predicate reaching through it resolves as an id set
     * rather than a Criteria join. The vector path has to work over that too.
     */
    @Test
    @DisplayName("a vector search narrows through a to-many collection path")
    void narrowingThroughAToManyPathWorks() {
        List<TagSet> holdingOak =
                tagSets.findNearestBySlugVectorAndTagsSlugIs(reference(), 10, "oak");
        List<TagSet> holdingCrimson =
                tagSets.findNearestBySlugVectorAndTagsSlugIs(reference(), 10, "crimson");

        assertEquals(List.of("materials"), holdingOak.stream().map(TagSet::getSlug).toList());
        assertEquals(List.of("colours"), holdingCrimson.stream().map(TagSet::getSlug).toList());
    }

    @Test
    @DisplayName("...and the builder narrows through the same to-many path")
    void builderNarrowsThroughAToManyPath() {
        List<TagSet> hits = tagSets.nearestBy("slug")
                .to(reference())
                .where("tags.slug").is("granite")
                .limit(10)
                .results();

        assertEquals(List.of("materials"), hits.stream().map(TagSet::getSlug).toList());
    }
}
