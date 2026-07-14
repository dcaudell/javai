package dev.xtrafe.javai.tagging;

import dev.xtrafe.javai.model.JavAIList;
import dev.xtrafe.javai.model.JavAIRuntime;
import dev.xtrafe.javai.persistence.JavAIPI;
import dev.xtrafe.javai.persistence.JavAIPersistenceConfig;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Classification's own diff/marshalling logic is what's under test here (see {@link FakeCortex}'s own
 * javadoc) -- a real Postgres container is still needed because {@link JavAITagging#classify} reads and
 * writes real {@code source = "auto"} associations through a real {@link TaggingBackend}, which is exactly
 * the diff logic this test is proving.
 */
@Testcontainers
class JavAITaggingClassificationE2ETest {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    private static TagRepository tagRepository;
    private static TagSetRepository tagSetRepository;
    private static ClassifiableThingRepository thingRepository;
    private static final FakeCortex CORTEX = new FakeCortex();

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
        thingRepository = JavAIPI.repository(ClassifiableThingRepository.class);

        JavAITagging.configureTagging(config, tagRepository);
        JavAITagging.configureClassification(CORTEX);
    }

    @BeforeEach
    void resetCortex() {
        CORTEX.reset();
    }

    @Test
    void classifyAppliesMatchedTagsWithSourceAuto() {
        TagSet tagSet = tagSetRepository.save(new TagSet("priority"));
        Tag urgent = tagRepository.save(new Tag(tagSet, "en", "Urgent"));
        tagRepository.save(new Tag(tagSet, "en", "Low Priority"));
        TestClassifiableThing thing = thingRepository.save(
                new TestClassifiableThing("Server is on fire, customers affected", "internal note, ignore"));

        CORTEX.willRespond("[{\"slug\": \"urgent\", \"affinity\": 0.95, \"reasoning\": \"critical outage\"}]");

        ClassificationResult result = JavAITagging.classify(thing, tagSet);

        assertEquals(1, result.appliedTags().size());
        assertEquals("urgent", result.appliedTags().get(0).tag().getSlug());
        assertEquals(0.95, result.appliedTags().get(0).affinity());
        assertTrue(JavAITagging.hasTag(thing, urgent));
        JavAIList<Tag> tags = JavAITagging.tagsOf(thing);
        assertEquals(1, tags.size());
    }

    @Test
    void reclassifyRemovesPreviousAutoTagsNoLongerReturned() {
        TagSet tagSet = tagSetRepository.save(new TagSet("priority2"));
        Tag urgent = tagRepository.save(new Tag(tagSet, "en", "Urgent"));
        Tag low = tagRepository.save(new Tag(tagSet, "en", "Low Priority"));
        TestClassifiableThing thing = thingRepository.save(
                new TestClassifiableThing("Some issue", "note"));

        CORTEX.willRespond("[{\"slug\": \"urgent\"}]");
        JavAITagging.classify(thing, tagSet);
        assertTrue(JavAITagging.hasTag(thing, urgent));

        CORTEX.willRespond("[{\"slug\": \"low-priority\"}]");
        ClassificationResult result = JavAITagging.classify(thing, tagSet);

        assertFalse(JavAITagging.hasTag(thing, urgent), "urgent was auto-applied last time but not returned this time");
        assertTrue(JavAITagging.hasTag(thing, low));
        assertEquals(1, result.appliedTags().size());
        assertEquals("low-priority", result.appliedTags().get(0).tag().getSlug());
    }

    @Test
    void reclassifyNeverTouchesManuallyAppliedTags() {
        TagSet tagSet = tagSetRepository.save(new TagSet("priority3"));
        Tag urgent = tagRepository.save(new Tag(tagSet, "en", "Urgent"));
        Tag manual = tagRepository.save(new Tag(tagSet, "en", "Manually Flagged"));
        TestClassifiableThing thing = thingRepository.save(
                new TestClassifiableThing("Some issue", "note"));

        JavAITagging.addTag(thing, manual);

        CORTEX.willRespond("[{\"slug\": \"urgent\"}]");
        JavAITagging.classify(thing, tagSet);
        assertTrue(JavAITagging.hasTag(thing, manual), "manual tagging must survive a classify() call");
        assertTrue(JavAITagging.hasTag(thing, urgent));

        CORTEX.willRespond("[]");
        JavAITagging.classify(thing, tagSet);
        assertTrue(JavAITagging.hasTag(thing, manual), "manual tagging must survive even when auto tags are all removed");
        assertFalse(JavAITagging.hasTag(thing, urgent));
    }

    @Test
    void hallucinatedSlugOutsideCandidatesIsSilentlyDiscarded() {
        TagSet tagSet = tagSetRepository.save(new TagSet("priority4"));
        tagRepository.save(new Tag(tagSet, "en", "Urgent"));
        TestClassifiableThing thing = thingRepository.save(
                new TestClassifiableThing("Some issue", "note"));

        CORTEX.willRespond("[{\"slug\": \"not-a-real-candidate\", \"affinity\": 0.5}]");

        ClassificationResult result = JavAITagging.classify(thing, tagSet);

        assertTrue(result.appliedTags().isEmpty());
        assertTrue(JavAITagging.tagsOf(thing).isEmpty());
    }
}
