package dev.xtrafe.javai.persistence;

import dev.xtrafe.javai.model.JavAIRuntime;
import dev.xtrafe.javai.vector.EmbeddingVector;
import dev.xtrafe.javai.vector.Ranked;
import dev.xtrafe.javai.vector.testsupport.ScriptedEmbeddingProvider;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
import org.testcontainers.containers.Neo4jContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.List;

import static dev.xtrafe.javai.persistence.TestAsset.Kind.AUDIO;
import static dev.xtrafe.javai.persistence.TestAsset.Kind.IMAGE;
import static dev.xtrafe.javai.persistence.TestAsset.Kind.SHORT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What Neo4j does and does not do with OMI-230's vector search.
 *
 * <p><b>Narrowing is refused, and that refusal is the feature.</b>
 * {@code db.index.vector.queryNodes} is a top-K call: it picks its K nearest nodes before Cypher can see
 * them, so a predicate could only ever be applied to what the index already chose. Doing that would return
 * fewer than the requested limit whenever the predicate excludes anything -- the exact over-fetch-and-discard
 * this feature removes, relocated inside the library where the caller can no longer see it. So the backend
 * says so, at repository-creation time.
 *
 * <p>Everything that does not depend on pre-filtering works normally, and is asserted here so the refusal
 * cannot quietly widen into "vector search is degraded on Neo4j": ranking, {@link Ranked} similarities
 * (converted out of Neo4j's own rescaled score), and paging.
 */
@Testcontainers
class NarrowedVectorSearchNeo4jTest {

    private static final String NEO4J_PASSWORD = "javai12345";

    @Container
    static final Neo4jContainer<?> neo4j = new Neo4jContainer<>(DockerImageName.parse("neo4j:5.26-community"))
            .withAdminPassword(NEO4J_PASSWORD);

    private static ScriptedEmbeddingProvider provider;
    private static JavAIPersistenceConfig config;
    private static TestAssetUnnarrowedRepository assets;

    @BeforeAll
    static void seed() {
        provider = new ScriptedEmbeddingProvider()
                .at("audio nearest", 2).at("audio second", 4).at("audio third", 6)
                .at("image nearest", 20).at("image second", 30).at("short nearest", 50);
        JavAIRuntime.configureEmbeddingProvider(provider);

        config = JavAIPersistenceConfig.builder()
                .backend(JavAIPersistenceConfig.Backend.NEO4J)
                .neo4jUri(neo4j.getBoltUrl())
                .neo4jUsername("neo4j")
                .neo4jPassword(NEO4J_PASSWORD)
                .build();
        assets = JavAIPI.repository(TestAssetUnnarrowedRepository.class, config);

        assets.save(new TestAsset("audio nearest", AUDIO, true, 5, "ana"));
        assets.save(new TestAsset("audio second", AUDIO, false, 4, "ben"));
        assets.save(new TestAsset("audio third", AUDIO, true, 3, null));
        assets.save(new TestAsset("image nearest", IMAGE, true, 5, "ana"));
        assets.save(new TestAsset("image second", IMAGE, false, 4, null));
        assets.save(new TestAsset("short nearest", SHORT, true, 2, "cara"));
    }

    private static EmbeddingVector reference() {
        return provider.reference();
    }

    // ---- what Neo4j refuses -----------------------------------------------------------------------

    /**
     * At <em>repository-creation</em> time, not on the call -- the same bar the rest of this module holds
     * itself to. The message has to be actionable, so it is asserted rather than merely being non-empty:
     * it must say why the store cannot do this, and what to do instead.
     */
    @Test
    @DisplayName("a narrowed method-name query is refused when the repository is created")
    void narrowedMethodIsRefusedAtCreationTime() {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> JavAIPI.repository(TestAssetRepository.class, config));

        assertTrue(thrown.getMessage().contains("K nearest"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("Postgres") && thrown.getMessage().contains("MongoDB"),
                "the refusal must point at the backends that can serve it: " + thrown.getMessage());
    }

    /** The builder composes its predicate at runtime, so its refusal necessarily arrives at execution --
     *  but it must be the same refusal, not a different failure or a silently wrong answer. */
    @Test
    @DisplayName("a narrowed builder query is refused when it runs")
    void narrowedBuilderIsRefusedAtExecution() {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> assets.nearestBy("caption").to(reference()).where("kind").is(IMAGE).limit(3).results());

        assertTrue(thrown.getMessage().contains("K nearest"), thrown.getMessage());
    }

    // ---- what Neo4j still does --------------------------------------------------------------------

    @Test
    @DisplayName("an unnarrowed search ranks by similarity, nearest first")
    void unnarrowedSearchRanks() {
        assertEquals(List.of("audio nearest", "audio second", "audio third"),
                assets.findNearestByCaptionVector(reference(), 3).stream()
                        .map(TestAsset::getCaption).toList());
    }

    @Test
    @DisplayName("an unnarrowed builder query works, and reports plain cosine similarity")
    void builderWorksUnnarrowed() {
        List<Ranked<TestAsset>> hits = assets.nearestBy("caption").to(reference()).limit(2).ranked();

        assertEquals(List.of("audio nearest", "audio second"),
                hits.stream().map(hit -> hit.entity().getCaption()).toList());
        // Neo4j reports (1 + cos) / 2 for a cosine index; unconverted, cos 2° ~= 0.9994 would arrive as
        // ~0.9997 -- indistinguishable by eye, which is exactly why it is asserted numerically.
        assertEquals(ScriptedEmbeddingProvider.expectedSimilarity(2), hits.get(0).similarity(), 1e-3);
        assertEquals(ScriptedEmbeddingProvider.expectedSimilarity(4), hits.get(1).similarity(), 1e-3);
    }

    @Test
    @DisplayName("paging works, since skipping a total ranking needs nothing the index lacks")
    void pagingWorks() {
        List<Ranked<TestAsset>> secondPage =
                assets.findNearestByCaptionVector(reference(), PageRequest.of(1, 2));

        assertEquals(List.of("audio third", "image nearest"),
                secondPage.stream().map(hit -> hit.entity().getCaption()).toList());
    }

    @Test
    @DisplayName("the builder's offset works too")
    void builderOffsetWorks() {
        List<TestAsset> hits = assets.nearestBy("caption").to(reference()).offset(2).limit(2).results();

        assertEquals(List.of("audio third", "image nearest"),
                hits.stream().map(TestAsset::getCaption).toList());
    }
}
