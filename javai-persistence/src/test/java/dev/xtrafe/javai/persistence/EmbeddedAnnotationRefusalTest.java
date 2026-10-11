package dev.xtrafe.javai.persistence;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.xtrafe.javai.model.JavAIRuntime;
import dev.xtrafe.javai.vector.testsupport.FakeEmbeddingProvider;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * A JavAI annotation inside an {@code @Embedded} value is refused at registration (OMI-623). ⚠️ Until it is supported,
 * refusing is the only honest answer: the weaver, the runtime and this module all walk the entity's own class
 * hierarchy and never into an embedded value, so the annotation would otherwise do nothing, silently.
 */
@Testcontainers
class EmbeddedAnnotationRefusalTest {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    @Test
    void aVectorizeFieldInsideAnEmbeddedValueIsRefusedAtRegistration() {
        JavAIRuntime.configureEmbeddingProvider(new FakeEmbeddingProvider());
        JavAIPersistenceConfig config = JavAIPersistenceConfig.builder()
                .backend(JavAIPersistenceConfig.Backend.POSTGRES)
                .postgresUrl(postgres.getJdbcUrl())
                .postgresUsername(postgres.getUsername())
                .postgresPassword(postgres.getPassword())
                .build();

        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> JavAIPI.repository(TestEmbeddingOwnerRepository.class, config));

        assertTrue(refused.getMessage().contains("TestEmbeddingOwner.part"), refused.getMessage());
        assertTrue(refused.getMessage().contains("@Vectorize"), refused.getMessage());
    }

    @Test
    void theRefusalNamesEveryPathIntoTheValue() {
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> EntityReflection.refuseJavAIAnnotationsInsideEmbeddedValues(TestEmbeddingOwner.class));

        assertTrue(refused.getMessage().contains("part.body"), refused.getMessage());
    }
}
