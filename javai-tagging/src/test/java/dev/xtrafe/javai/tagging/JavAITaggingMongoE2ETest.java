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
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Real container -- see {@link JavAITaggingPostgresE2ETest}'s own javadoc for why, and
 *  {@code RepositoryBackendSpringDataMongoTest} (javai-persistence) for why {@code mongodb-atlas-local}
 *  specifically and {@code directConnection=true}. */
@Testcontainers
class JavAITaggingMongoE2ETest {

    private static final String DATABASE = "javai_tagging_test";

    @Container
    static final GenericContainer<?> mongo =
            new GenericContainer<>(DockerImageName.parse("mongodb/mongodb-atlas-local:8.2"))
                    .withExposedPorts(27017)
                    .withStartupTimeout(Duration.ofMinutes(3));

    private static TagRepository tagRepository;
    private static TagSetRepository tagSetRepository;
    private static TestThingRepository thingRepository;
    private static JavAITagRepository tagging;

    @BeforeAll
    static void configure() {
        JavAIRuntime.configureEmbeddingProvider(new FakeEmbeddingProvider());
        JavAIPersistenceConfig config = JavAIPersistenceConfig.builder()
                .backend(JavAIPersistenceConfig.Backend.MONGODB)
                .mongoUri(mongoUri())
                .mongoDatabase(DATABASE)
                .build();
        tagRepository = JavAIPI.repository(TagRepository.class, config);
        tagSetRepository = JavAIPI.repository(TagSetRepository.class, config);
        thingRepository = JavAIPI.repository(TestThingRepository.class, config);

        tagging = new JavAITagRepository(tagRepository, config);
    }

    private static String mongoUri() {
        return "mongodb://" + mongo.getHost() + ":" + mongo.getMappedPort(27017) + "/?directConnection=true";
    }

    @Test
    void addTagThenTagsOfRoundTrips() {
        TagSet tagSet = tagSetRepository.save(new TagSet("severity"));
        Tag urgent = tagRepository.save(new Tag(tagSet, "en", "Urgent"));
        TestThing thing = thingRepository.save(new TestThing("widget"));

        tagging.addTag(thing, urgent);

        JavAIList<Tag> tags = tagging.tagsOf(thing);
        assertEquals(1, tags.size());
        assertEquals("urgent", tags.get(0).getSlug());
    }

    @Test
    void hasTagReflectsAdditionAndRemoval() {
        TagSet tagSet = tagSetRepository.save(new TagSet("topics"));
        Tag tag = tagRepository.save(new Tag(tagSet, "en", "Widgets"));
        TestThing thing = thingRepository.save(new TestThing("gadget"));

        assertFalse(tagging.hasTag(thing, tag));
        tagging.addTag(thing, tag);
        assertTrue(tagging.hasTag(thing, tag));
        tagging.removeTag(thing, tag);
        assertFalse(tagging.hasTag(thing, tag));
    }

    @Test
    void addTagIsIdempotentPerInstanceMatchingZeroOrOneAssociationInvariant() {
        TagSet tagSet = tagSetRepository.save(new TagSet("topics2"));
        Tag tag = tagRepository.save(new Tag(tagSet, "en", "Repeatable"));
        TestThing thing = thingRepository.save(new TestThing("thingy"));

        tagging.addTag(thing, tag);
        tagging.addTag(thing, tag, 0.75);

        JavAIList<Tag> tags = tagging.tagsOf(thing);
        assertEquals(1, tags.size(), "re-adding the same tag must update, not duplicate, the association");
    }

    @Test
    void taggedWithFindsAllInstancesAcrossCandidateTypes() {
        TagSet tagSet = tagSetRepository.save(new TagSet("topics3"));
        Tag tag = tagRepository.save(new Tag(tagSet, "en", "Findable"));
        TestThing thingA = thingRepository.save(new TestThing("a"));
        TestThing thingB = thingRepository.save(new TestThing("b"));
        TestThing untagged = thingRepository.save(new TestThing("c"));

        tagging.addTag(thingA, tag);
        tagging.addTag(thingB, tag);

        JavAIList<TaggableRef> refs = tagging.taggedWith(tag, List.of(TestThing.class));
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

        tagging.addTag(security, reviewed);

        JavAIList<Tag> tagsOnSecurity = tagging.tagsOf(security);
        assertEquals(1, tagsOnSecurity.size());
        assertEquals("reviewed", tagsOnSecurity.get(0).getSlug());
    }

    @Test
    void tagSimilarityIndexFindsInstancesRankedByTagSummaryVectorSimilarity() {
        TagSet tagSet = tagSetRepository.save(new TagSet("similarity-mongo"));
        Tag focusTag = tagRepository.save(new Tag(tagSet, "en", "Focus Alpha Mongo"));
        Tag unrelatedTag = tagRepository.save(new Tag(tagSet, "en", "Unrelated Beta Mongo"));
        TestThing onlyThing = thingRepository.save(new TestThing("onlyThingMongo"));
        TestThing otherThing = thingRepository.save(new TestThing("otherThingMongo"));

        tagging.addTag(onlyThing, focusTag);
        tagging.addTag(otherThing, unrelatedTag);

        EmbeddingVector reference = ((JavAIVectorizable) focusTag).summaryVector();
        VectorIndex<TaggableRef> index = tagging.tagSimilarityIndex();
        // mongot indexes near-real-time, not synchronously with the write -- same eventual-consistency
        // gap RepositoryBackendSpringDataMongoTest's own awaitContainsId works around, for the same reason.
        JavAIList<TaggableRef> nearest = awaitContainsId(() -> index.nearestN(reference, 1), onlyThing.getId());
        assertEquals(1, nearest.size());
        assertEquals(onlyThing.getId(), nearest.get(0).taggableId());

        // mongot's own ANN index is approximate, like Neo4j's -- see that backend's own equivalent test for
        // why this threshold is looser than the exact pgvector WHERE-clause comparison Postgres affords.
        JavAIList<TaggableRef> almostIdentical = index.filterByMinSimilarity(reference, 0.9);
        assertTrue(almostIdentical.stream().anyMatch(ref -> ref.taggableId().equals(onlyThing.getId())));
        assertFalse(almostIdentical.stream().anyMatch(ref -> ref.taggableId().equals(otherThing.getId())));

        tagging.removeTag(onlyThing, focusTag);
        JavAIList<TaggableRef> afterRemoval = awaitNotContainsId(() -> index.nearestN(reference, 5), onlyThing.getId());
        assertFalse(afterRemoval.stream().anyMatch(ref -> ref.taggableId().equals(onlyThing.getId())),
                "tag-summary vector must be removed from the index once an instance has zero remaining Taggings");
    }

    /** See {@code RepositoryBackendSpringDataMongoTest.awaitContainsId}'s own identical javadoc. */
    private static JavAIList<TaggableRef> awaitContainsId(Supplier<JavAIList<TaggableRef>> query, java.util.UUID expectedId) {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(15));
        JavAIList<TaggableRef> result;
        while (true) {
            result = query.get();
            if (result.stream().anyMatch(ref -> ref.taggableId().equals(expectedId)) || Instant.now().isAfter(deadline)) {
                return result;
            }
            sleep();
        }
    }

    private static JavAIList<TaggableRef> awaitNotContainsId(Supplier<JavAIList<TaggableRef>> query, java.util.UUID excludedId) {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(15));
        JavAIList<TaggableRef> result;
        while (true) {
            result = query.get();
            if (result.stream().noneMatch(ref -> ref.taggableId().equals(excludedId)) || Instant.now().isAfter(deadline)) {
                return result;
            }
            sleep();
        }
    }

    private static void sleep() {
        try {
            Thread.sleep(200);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
