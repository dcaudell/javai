package dev.xtrafe.javai.persistence;

import dev.xtrafe.javai.model.JavAIRuntime;
import dev.xtrafe.javai.vector.testsupport.FakeEmbeddingProvider;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * {@code JavAIPersistenceConfig.Builder.entityType(...)}, the escape hatch added with OMI-212.
 *
 * <p>Registering {@code @Any} discriminator targets fixed the reported bug; this fixes the class it belongs
 * to. JavAI discovers related types by walking declared field types, which is complete for ordinary
 * associations and blind to anything reached another way -- and before this there was no way at all to say
 * "also register this type", so such a type was simply unreachable.
 *
 * <p>{@link TestOrphanEntity} exists to be unreachable: nothing declares it as a field and it is named by no
 * other fixture, so it is registered here only because the builder was told to.
 */
@Testcontainers
class ExplicitEntityRegistrationTest {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    private static TestOrphanEntityRepository orphans;

    @BeforeAll
    static void configure() {
        JavAIRuntime.configureEmbeddingProvider(new FakeEmbeddingProvider());
        JavAIPersistenceConfig config = JavAIPersistenceConfig.builder()
                .backend(JavAIPersistenceConfig.Backend.POSTGRES)
                .postgresUrl(postgres.getJdbcUrl())
                .postgresUsername(postgres.getUsername())
                .postgresPassword(postgres.getPassword())
                .entityType(TestOrphanEntity.class)
                .build();
        orphans = JavAIPI.repository(TestOrphanEntityRepository.class, config);
    }

    @Test
    void anExplicitlyRegisteredTypeIsUsable() {
        TestOrphanEntity saved = orphans.save(new TestOrphanEntity("registered by hand"));

        TestOrphanEntity reloaded = orphans.findById(saved.getId()).orElseThrow();
        assertNotNull(reloaded);
        assertEquals("registered by hand", reloaded.getNote());
    }

    @Test
    void theBuilderCarriesEveryNamedType() {
        JavAIPersistenceConfig config = JavAIPersistenceConfig.builder()
                .entityType(TestOrphanEntity.class)
                .entityTypes(List.of(TestAlbum.class, TestImageCover.class))
                .build();

        assertEquals(List.of(TestOrphanEntity.class, TestAlbum.class, TestImageCover.class),
                List.copyOf(config.additionalEntityTypes()),
                "named types are kept in the order given, and both spellings contribute");
    }

    @Test
    void namingTheSameTypeTwiceIsHarmless() {
        JavAIPersistenceConfig config = JavAIPersistenceConfig.builder()
                .entityType(TestOrphanEntity.class)
                .entityType(TestOrphanEntity.class)
                .build();

        assertEquals(1, config.additionalEntityTypes().size(),
                "registration is idempotent, exactly as it is for a discovered type");
    }
}
