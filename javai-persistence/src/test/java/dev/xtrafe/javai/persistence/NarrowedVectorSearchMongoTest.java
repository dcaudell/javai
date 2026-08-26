package dev.xtrafe.javai.persistence;

import dev.xtrafe.javai.model.JavAIRuntime;
import dev.xtrafe.javai.vector.EmbeddingVector;
import dev.xtrafe.javai.vector.Ranked;
import dev.xtrafe.javai.vector.testsupport.ScriptedEmbeddingProvider;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.List;
import java.util.Set;

import static dev.xtrafe.javai.persistence.TestAsset.Kind.AUDIO;
import static dev.xtrafe.javai.persistence.TestAsset.Kind.IMAGE;
import static dev.xtrafe.javai.persistence.TestAsset.Kind.SHORT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The same narrowed vector search as {@link NarrowedVectorSearchTest}, against MongoDB (OMI-230).
 *
 * <h2>Why this is a separate suite rather than a parameterized backend</h2>
 *
 * The behavior must be identical, but the <em>mechanism</em> is not, and each has its own way to be wrong.
 * Postgres resolves the predicate to an id array and ranks within it; MongoDB hands the id set to
 * {@code $vectorSearch}'s own {@code filter}, which is a genuine pre-filter applied during the search --
 * and which only works because {@code ensureVectorIndex} declares {@code _id} as a filter field. If that
 * declaration were ever dropped, Postgres would carry on passing and only this suite would notice.
 *
 * <p>The fixture and its angles mirror the Postgres suite exactly -- the five nearest are all {@code AUDIO}
 * -- so "the nearest 3 IMAGEs" is the same trap here: returning fewer than 3 means the filter ran after the
 * limit rather than during the search.
 */
@Testcontainers
class NarrowedVectorSearchMongoTest {

    private static final String DATABASE = "javai_narrowed_test";

    /** See {@code RepositoryBackendSpringDataMongoTest} for why the healthcheck wait is load-bearing here. */
    @Container
    static final GenericContainer<?> mongo =
            new GenericContainer<>(DockerImageName.parse("mongodb/mongodb-atlas-local:8.2"))
                    .withExposedPorts(27017)
                    .waitingFor(Wait.forHealthcheck())
                    .withStartupTimeout(Duration.ofMinutes(3));

    private static ScriptedEmbeddingProvider provider;
    private static TestAssetRepository assets;

    @BeforeAll
    static void seed() {
        provider = new ScriptedEmbeddingProvider()
                .at("audio nearest", 2).at("audio second", 4).at("audio third", 6)
                .at("audio fourth", 8).at("audio fifth", 10)
                .at("image nearest", 20).at("image second", 30).at("image third", 40)
                .at("short nearest", 50).at("short second", 60);
        JavAIRuntime.configureEmbeddingProvider(provider);

        JavAIPersistenceConfig config = JavAIPersistenceConfig.builder()
                .backend(JavAIPersistenceConfig.Backend.MONGODB)
                .mongoUri("mongodb://" + mongo.getHost() + ":" + mongo.getMappedPort(27017)
                        + "/?directConnection=true")
                .mongoDatabase(DATABASE)
                .build();
        assets = JavAIPI.repository(TestAssetRepository.class, config);

        assets.save(new TestAsset("audio nearest", AUDIO, true, 5, "ana"));
        assets.save(new TestAsset("audio second", AUDIO, false, 4, "ben"));
        assets.save(new TestAsset("audio third", AUDIO, true, 3, null));
        assets.save(new TestAsset("audio fourth", AUDIO, false, 2, "cara"));
        assets.save(new TestAsset("audio fifth", AUDIO, true, 1, null));
        assets.save(new TestAsset("image nearest", IMAGE, true, 5, "ana"));
        assets.save(new TestAsset("image second", IMAGE, false, 4, null));
        assets.save(new TestAsset("image third", IMAGE, true, 3, "ben"));
        assets.save(new TestAsset("short nearest", SHORT, true, 2, "cara"));
        assets.save(new TestAsset("short second", SHORT, false, 1, null));
    }

    private static EmbeddingVector reference() {
        return provider.reference();
    }

    private static List<String> captions(List<TestAsset> hits) {
        return hits.stream().map(TestAsset::getCaption).toList();
    }

    @Test
    @DisplayName("an unnarrowed search still ranks by similarity, nearest first")
    void unnarrowedSearchStillWorks() {
        assertEquals(List.of("audio nearest", "audio second", "audio third"),
                captions(assets.findNearestByCaptionVector(reference(), 3)));
    }

    @Test
    @DisplayName("the limit applies AFTER the predicate, via $vectorSearch's own filter")
    void limitAppliesAfterThePredicate() {
        List<TestAsset> hits = assets.findNearestByCaptionVectorAndKindIs(reference(), 3, IMAGE);

        assertEquals(3, hits.size(), "the three nearest overall are all AUDIO, so getting fewer than three "
                + "IMAGEs here means the filter was applied to the search's output rather than during it");
        assertEquals(List.of("image nearest", "image second", "image third"), captions(hits));
    }

    @Test
    @DisplayName("the operator vocabulary narrows here too")
    void operatorVocabularyNarrows() {
        assertEquals(List.of("image nearest", "image second", "image third", "short nearest"),
                captions(assets.findNearestByCaptionVectorAndKindIn(reference(), 4, Set.of(IMAGE, SHORT))));
        assertEquals(List.of("audio nearest", "audio third", "audio fifth"),
                captions(assets.findNearestByCaptionVectorAndPublishedTrue(reference(), 3)));
        assertEquals(List.of("audio nearest", "image nearest"),
                captions(assets.findNearestByCaptionVectorAndRatingGreaterThan(reference(), 10, 4)));
        assertEquals(List.of("audio third", "audio fifth", "image second", "short second"),
                captions(assets.findNearestByCaptionVectorAndOwnerIsNull(reference(), 10)));
    }

    @Test
    @DisplayName("the builder idiom agrees with the method name, on this backend too")
    void bothIdiomsAgree() {
        List<String> viaMethodName =
                captions(assets.findNearestByCaptionVectorAndKindIn(reference(), 4, Set.of(IMAGE, SHORT)));
        List<String> viaBuilder = captions(assets.nearestBy("caption")
                .to(reference())
                .where("kind").in(IMAGE, SHORT)
                .limit(4)
                .results());

        assertEquals(viaMethodName, viaBuilder);
    }

    @Test
    @DisplayName("similarity is converted out of Atlas's rescaled score into plain cosine")
    void similarityIsPlainCosine() {
        List<Ranked<TestAsset>> hits = assets.nearestBy("caption")
                .to(reference())
                .where("kind").is(IMAGE)
                .limit(2)
                .ranked();

        assertEquals(List.of("image nearest", "image second"),
                hits.stream().map(hit -> hit.entity().getCaption()).toList());
        // Atlas reports (1 + cos) / 2; a backend that returned that unconverted would report ~0.97 here
        // rather than cos 20° ~= 0.94, which is close enough to look right and is not.
        assertEquals(ScriptedEmbeddingProvider.expectedSimilarity(20), hits.get(0).similarity(), 1e-3);
        assertEquals(ScriptedEmbeddingProvider.expectedSimilarity(30), hits.get(1).similarity(), 1e-3);
    }

    @Test
    @DisplayName("offset skips matches rather than raw hits")
    void offsetSkipsMatches() {
        List<TestAsset> hits = assets.nearestBy("caption")
                .to(reference())
                .where("kind").is(IMAGE)
                .offset(1)
                .limit(2)
                .results();

        assertEquals(List.of("image second", "image third"), captions(hits));
    }

    @Test
    @DisplayName("nothing satisfying the predicate yields nothing, not the nearest unfiltered")
    void anUnsatisfiablePredicateYieldsNothing() {
        assertTrue(assets.findNearestByCaptionVectorAndRatingGreaterThan(reference(), 10, 99).isEmpty());
    }
}
