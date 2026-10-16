package dev.xtrafe.javai.persistence;

import dev.xtrafe.javai.model.JavAIRuntime;
import dev.xtrafe.javai.vector.testsupport.ScriptedEmbeddingProvider;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link NearestQuery#breakingTiesById()} (OMI-658): pages of a nearest search over entities at the <b>same</b>
 * distance. Identical content embeds identically -- fifteen Profiles with empty bios did -- and ranked by distance
 * alone, each page's {@code LIMIT} may order those ties its own way: sixty such hits paged by four came back as
 * fifty-four distinct ones. Ordering ties by id is optional, and without it nothing changes.
 */
@Testcontainers
class NearestTieBreakTest {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    private static final int TIED = 60;
    private static final int PAGE = 4;

    private static TestAssetRepository assets;
    private static ScriptedEmbeddingProvider provider;

    @BeforeAll
    static void seed() {
        provider = new ScriptedEmbeddingProvider();
        for (int i = 0; i < TIED; i++) {
            provider.at("tied " + i, 30);
        }
        JavAIRuntime.configureEmbeddingProvider(provider);
        assets = JavAIPI.repository(TestAssetRepository.class, JavAIPersistenceConfig.builder()
                .backend(JavAIPersistenceConfig.Backend.POSTGRES)
                .postgresUrl(postgres.getJdbcUrl())
                .postgresUsername(postgres.getUsername())
                .postgresPassword(postgres.getPassword())
                .build());
        for (int i = 0; i < TIED; i++) {
            assets.save(new TestAsset("tied " + i, TestAsset.Kind.IMAGE, true, 1, null));
        }
    }

    @Test
    @DisplayName("⚠️ breaking ties by id, pages through tied hits return each exactly once, in id order")
    void tiedHitsPageOnceEachInIdOrder() {
        List<TestAsset> seen = new ArrayList<>();
        for (int offset = 0; offset < TIED; offset += PAGE) {
            seen.addAll(assets.nearestBy("caption").to(provider.reference()).breakingTiesById()
                    .offset(offset).limit(PAGE).results());
        }

        assertEquals(TIED, seen.size(), "every page full");
        assertEquals(TIED, new HashSet<>(seen.stream().map(TestAsset::getId).toList()).size(),
                "a tied hit appeared on two pages, so another appeared on none");
        List<UUID> ids = seen.stream().map(TestAsset::getId).toList();
        assertEquals(ids.stream().sorted(Comparator.comparing(UUID::toString)).toList(), ids,
                "tied hits in Postgres's uuid order: unsigned, which is the order of the hex text");
    }

    @Test
    @DisplayName("a backend that cannot order ties by id refuses the search rather than ignoring the request")
    void unsupportedBackendRefuses() {
        InvocationHandler defaultsOnly = (proxy, method, args) -> {
            if (method.isDefault()) {
                return InvocationHandler.invokeDefault(proxy, method, args);
            }
            throw new AssertionError(method.getName() + " should not be reached");
        };
        RepositoryBackend backend = (RepositoryBackend) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] {RepositoryBackend.class}, defaultsOnly);
        NearestQuery<TestAsset> query = new NearestQuery<TestAsset>(backend, TestAsset.class,
                DerivedQueryMethods.Kind.FIELD, "caption").to(provider.reference()).breakingTiesById().limit(1);

        assertThrows(UnsupportedOperationException.class, query::ranked);
    }
}
