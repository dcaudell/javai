package dev.xtrafe.javai.tagging;

import dev.xtrafe.javai.collections.VectorIndex;
import dev.xtrafe.javai.model.JavAIList;
import dev.xtrafe.javai.model.JavAIRuntime;
import dev.xtrafe.javai.model.JavAIVectorizable;
import dev.xtrafe.javai.persistence.JavAIPI;
import dev.xtrafe.javai.persistence.JavAIPersistenceConfig;
import dev.xtrafe.javai.vector.EmbeddingVector;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real container, not hermetic -- same reasoning as every sibling persistence test: there's no meaningful
 * way to fake whether a real {@code taggings} table round-trips correctly. Tag/TagSet themselves are
 * persisted through the ordinary {@code JavAIPI.repository(...)} path, unchanged; only the association
 * (via {@link JavAITagging}) is this module's own new code being exercised here.
 */
@Testcontainers
class JavAITaggingPostgresE2ETest {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    private static TagRepository tagRepository;
    private static TagSetRepository tagSetRepository;
    private static TestThingRepository thingRepository;

    @BeforeAll
    static void configure() {
        JavAIRuntime.configureEmbeddingProvider(new FakeEmbeddingProvider());
        JavAIPersistenceConfig config = JavAIPersistenceConfig.builder()
                .backend(JavAIPersistenceConfig.Backend.POSTGRES)
                .postgresUrl(postgres.getJdbcUrl())
                .postgresUsername(postgres.getUsername())
                .postgresPassword(postgres.getPassword())
                .build();
        JavAIPI.configurePersistence(config);
        tagRepository = JavAIPI.repository(TagRepository.class);
        tagSetRepository = JavAIPI.repository(TagSetRepository.class);
        thingRepository = JavAIPI.repository(TestThingRepository.class);

        JavAITagging.configureTagging(config, tagRepository);
    }

    @Test
    void addTagThenTagsOfRoundTrips() {
        TagSet tagSet = tagSetRepository.save(new TagSet("severity"));
        Tag urgent = tagRepository.save(new Tag(tagSet, "en", "Urgent"));
        TestThing thing = thingRepository.save(new TestThing("widget"));

        JavAITagging.addTag(thing, urgent);

        JavAIList<Tag> tags = JavAITagging.tagsOf(thing);
        assertEquals(1, tags.size());
        assertEquals("urgent", tags.get(0).getSlug());
    }

    @Test
    void hasTagReflectsAdditionAndRemoval() {
        TagSet tagSet = tagSetRepository.save(new TagSet("topics"));
        Tag tag = tagRepository.save(new Tag(tagSet, "en", "Widgets"));
        TestThing thing = thingRepository.save(new TestThing("gadget"));

        assertFalse(JavAITagging.hasTag(thing, tag));
        JavAITagging.addTag(thing, tag);
        assertTrue(JavAITagging.hasTag(thing, tag));
        JavAITagging.removeTag(thing, tag);
        assertFalse(JavAITagging.hasTag(thing, tag));
    }

    @Test
    void addTagIsIdempotentPerInstanceMatchingZeroOrOneAssociationInvariant() {
        TagSet tagSet = tagSetRepository.save(new TagSet("topics2"));
        Tag tag = tagRepository.save(new Tag(tagSet, "en", "Repeatable"));
        TestThing thing = thingRepository.save(new TestThing("thingy"));

        JavAITagging.addTag(thing, tag);
        JavAITagging.addTag(thing, tag, 0.75);

        JavAIList<Tag> tags = JavAITagging.tagsOf(thing);
        assertEquals(1, tags.size(), "re-adding the same tag must update, not duplicate, the association");
    }

    @Test
    void taggedWithFindsAllInstancesAcrossCandidateTypes() {
        TagSet tagSet = tagSetRepository.save(new TagSet("topics3"));
        Tag tag = tagRepository.save(new Tag(tagSet, "en", "Findable"));
        TestThing thingA = thingRepository.save(new TestThing("a"));
        TestThing thingB = thingRepository.save(new TestThing("b"));
        TestThing untagged = thingRepository.save(new TestThing("c"));

        JavAITagging.addTag(thingA, tag);
        JavAITagging.addTag(thingB, tag);

        JavAIList<TaggableRef> refs = JavAITagging.taggedWith(tag, List.of(TestThing.class));
        assertEquals(2, refs.size());
        assertTrue(refs.stream().anyMatch(ref -> ref.taggableId().equals(thingA.getId())));
        assertTrue(refs.stream().anyMatch(ref -> ref.taggableId().equals(thingB.getId())));
        assertFalse(refs.stream().anyMatch(ref -> ref.taggableId().equals(untagged.getId())));
    }

    @Test
    void aTagCanItselfCarryTagsSinceTagsAreRecursivelyTaggable() {
        TagSet tagSet = tagSetRepository.save(new TagSet("meta"));
        Tag security = tagRepository.save(new Tag(tagSet, "en", "Security"));
        Tag reviewed = tagRepository.save(new Tag(tagSet, "en", "Reviewed"));

        JavAITagging.addTag(security, reviewed);

        JavAIList<Tag> tagsOnSecurity = JavAITagging.tagsOf(security);
        assertEquals(1, tagsOnSecurity.size());
        assertEquals("reviewed", tagsOnSecurity.get(0).getSlug());
    }

    @Test
    void tagSlugIsUniqueOnlyWithinItsOwningTagSet() {
        TagSet setA = tagSetRepository.save(new TagSet("set-a"));
        TagSet setB = tagSetRepository.save(new TagSet("set-b"));

        // Same slug ("urgent"), two different TagSets -- must not conflict; see doc/spec/tagging.md's
        // "Uniqueness" for why global slug uniqueness would be actively wrong here.
        Tag urgentA = tagRepository.save(new Tag(setA, "en", "Urgent"));
        Tag urgentB = tagRepository.save(new Tag(setB, "en", "Urgent"));

        Optional<Tag> reloadedA = tagRepository.findById(urgentA.getId());
        Optional<Tag> reloadedB = tagRepository.findById(urgentB.getId());
        assertTrue(reloadedA.isPresent());
        assertTrue(reloadedB.isPresent());
        assertEquals("urgent", reloadedA.get().getSlug());
        assertEquals("urgent", reloadedB.get().getSlug());
    }

    @Test
    void tagSimilarityIndexFindsInstancesRankedByTagSummaryVectorSimilarity() {
        TagSet tagSet = tagSetRepository.save(new TagSet("similarity-postgres"));
        Tag focusTag = tagRepository.save(new Tag(tagSet, "en", "Focus Alpha Postgres"));
        Tag unrelatedTag = tagRepository.save(new Tag(tagSet, "en", "Unrelated Beta Postgres"));
        TestThing onlyThing = thingRepository.save(new TestThing("onlyThingPostgres"));
        TestThing otherThing = thingRepository.save(new TestThing("otherThingPostgres"));

        JavAITagging.addTag(onlyThing, focusTag);
        JavAITagging.addTag(otherThing, unrelatedTag);

        EmbeddingVector reference = ((JavAIVectorizable) focusTag).summaryVector();
        VectorIndex<TaggableRef> index = JavAITagging.tagSimilarityIndex();
        JavAIList<TaggableRef> nearest = index.nearestN(reference, 1);
        assertEquals(1, nearest.size());
        assertEquals(onlyThing.getId(), nearest.get(0).taggableId());

        JavAIList<TaggableRef> almostIdentical = index.filterByMinSimilarity(reference, 0.999999);
        assertTrue(almostIdentical.stream().anyMatch(ref -> ref.taggableId().equals(onlyThing.getId())));
        assertFalse(almostIdentical.stream().anyMatch(ref -> ref.taggableId().equals(otherThing.getId())));

        JavAITagging.removeTag(onlyThing, focusTag);
        JavAIList<TaggableRef> afterRemoval = index.nearestN(reference, 5);
        assertFalse(afterRemoval.stream().anyMatch(ref -> ref.taggableId().equals(onlyThing.getId())),
                "tag-summary vector must be removed from the index once an instance has zero remaining Taggings");
    }
}
