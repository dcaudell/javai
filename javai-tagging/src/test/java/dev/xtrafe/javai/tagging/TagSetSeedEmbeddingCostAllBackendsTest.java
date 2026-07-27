package dev.xtrafe.javai.tagging;

import dev.xtrafe.javai.model.EmbeddingConsistencyMode;
import dev.xtrafe.javai.model.JavAIRuntime;
import dev.xtrafe.javai.persistence.JavAIPI;
import dev.xtrafe.javai.persistence.JavAIPersistenceConfig;
import dev.xtrafe.javai.vector.testsupport.RecordingEmbeddingProvider;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Neo4jContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * OMI-187's seeding invariant on the two backends {@code TagSetSeedEmbeddingCostTest} does not cover:
 * <b>seeding N tags into one TagSet costs exactly N embeddings, on Neo4j and MongoDB too.</b>
 *
 * <h2>Why this is a real test and not a formality</h2>
 *
 * Defect B -- an owner's unchanged field re-embedded once per child save -- turned out to be caused by
 * {@code merge()} handing back a different instance whose woven {@code $javai$state} (and therefore whose
 * vector caches) was empty. Neither of these backends merges: {@code RepositoryBackendNeo4j.save()} calls
 * {@code saveNode(tx, entity, ...)} and {@code RepositoryBackendSpringDataMongo.save()} calls
 * {@code saveDocument(entity, ...)}, both operating on the caller's own object. So the reasoning says they
 * were never affected and need no equivalent of Postgres's state transfer.
 *
 * <p>That reasoning is exactly the kind that was wrong twice on this ticket already, and "this backend is
 * structurally immune" is precisely the claim that stops being true without anyone noticing. Measured here
 * instead.
 */
@Testcontainers
class TagSetSeedEmbeddingCostAllBackendsTest {

    private static final AtomicLong UNIQUE = new AtomicLong();
    private static final String NEO4J_PASSWORD = "seed-cost-password";
    private static final String DATABASE = "seedcost";
    private static final int TAG_COUNT = 12;

    @Container
    static final Neo4jContainer<?> neo4j = new Neo4jContainer<>(DockerImageName.parse("neo4j:5.26-community"))
            .withAdminPassword(NEO4J_PASSWORD);

    @Container
    static final GenericContainer<?> mongo =
            new GenericContainer<>(DockerImageName.parse("mongodb/mongodb-atlas-local:8.2"))
                    .withExposedPorts(27017)
                    .waitingFor(Wait.forHealthcheck())
                    .withStartupTimeout(Duration.ofMinutes(3));

    private static TagRepository neo4jTags;
    private static TagSetRepository neo4jTagSets;
    private static TagRepository mongoTags;
    private static TagSetRepository mongoTagSets;

    private RecordingEmbeddingProvider provider;

    @BeforeAll
    static void configureRepositories() {
        JavAIPersistenceConfig neo4jConfig = JavAIPersistenceConfig.builder()
                .backend(JavAIPersistenceConfig.Backend.NEO4J)
                .neo4jUri(neo4j.getBoltUrl())
                .neo4jUsername("neo4j")
                .neo4jPassword(NEO4J_PASSWORD)
                .build();
        neo4jTags = JavAIPI.repository(TagRepository.class, neo4jConfig);
        neo4jTagSets = JavAIPI.repository(TagSetRepository.class, neo4jConfig);

        JavAIPersistenceConfig mongoConfig = JavAIPersistenceConfig.builder()
                .backend(JavAIPersistenceConfig.Backend.MONGODB)
                .mongoUri("mongodb://" + mongo.getHost() + ":" + mongo.getMappedPort(27017)
                        + "/?directConnection=true")
                .mongoDatabase(DATABASE)
                .build();
        mongoTags = JavAIPI.repository(TagRepository.class, mongoConfig);
        mongoTagSets = JavAIPI.repository(TagSetRepository.class, mongoConfig);
    }

    @BeforeEach
    void installRecordingProvider() {
        JavAIRuntime.configureConsistencyMode(EmbeddingConsistencyMode.IMMEDIATE_CONSISTENCY);
        provider = new RecordingEmbeddingProvider();
        JavAIRuntime.configureEmbeddingProvider(provider);
    }

    private static String unique(String label) {
        return label + "-" + UNIQUE.incrementAndGet();
    }

    @Test
    void seedingTagsOneAtATimeOnNeo4jEmbedsEachSlugExactlyOnce() {
        assertSeedingCostsOneEmbeddingPerTag(neo4jTagSets, neo4jTags, "neo4j-set");
    }

    @Test
    void seedingTagsOneAtATimeOnMongoEmbedsEachSlugExactlyOnce() {
        assertSeedingCostsOneEmbeddingPerTag(mongoTagSets, mongoTags, "mongo-set");
    }

    private void assertSeedingCostsOneEmbeddingPerTag(TagSetRepository tagSets, TagRepository tags,
            String setLabel) {
        TagSet tagSet = tagSets.save(new TagSet(unique(setLabel)));

        // The set's own slug is legitimately embedded by the save above; measure only the seeding loop.
        provider.reset();

        List<String> expectedSlugs = new ArrayList<>();
        for (int i = 0; i < TAG_COUNT; i++) {
            String displayName = unique("Probe Tag");
            expectedSlugs.add(displayName.toLowerCase().replace(' ', '-'));
            tags.save(new Tag(tagSet, "en", displayName));
        }

        provider.ledger().assertEmbeddedExactlyOnce(expectedSlugs);
        assertEquals(TAG_COUNT, provider.ledger().totalCalls(),
                "seeding " + TAG_COUNT + " tags must cost exactly " + TAG_COUNT + " embeddings");
    }
}
