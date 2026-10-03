package dev.xtrafe.javai.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import dev.xtrafe.javai.model.JavAIRuntime;
import dev.xtrafe.javai.vector.testsupport.FakeEmbeddingProvider;

/**
 * OMI-613: saving an entity whose element collections hold enums declared outside java.* walked into the enum's
 * fields and threw, since {@code java.lang} is not opened to it. An enum is a leaf.
 */
@Testcontainers
class EnumElementCollectionTest {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    private static TestPaletteRepository palettes;

    @BeforeAll
    static void configure() {
        JavAIRuntime.configureEmbeddingProvider(new FakeEmbeddingProvider());
        palettes = JavAIPI.repository(TestPaletteRepository.class, JavAIPersistenceConfig.builder()
                .backend(JavAIPersistenceConfig.Backend.POSTGRES)
                .postgresUrl(postgres.getJdbcUrl())
                .postgresUsername(postgres.getUsername())
                .postgresPassword(postgres.getPassword())
                .build());
    }

    @Test
    void enumElementCollectionsSaveAndRoundTrip() {
        TestPalette palette = new TestPalette("autumn");
        palette.getShades().put(TestColor.RED, TestColor.GREEN);
        palette.getColors().add(TestColor.GREEN);

        TestPalette saved = palettes.save(palette);
        TestPalette reloaded = palettes.findById(saved.getId()).orElseThrow();

        assertEquals(Map.of(TestColor.RED, TestColor.GREEN), reloaded.getShades());
        assertEquals(Set.of(TestColor.GREEN), reloaded.getColors());

        reloaded.getShades().put(TestColor.GREEN, TestColor.RED);
        palettes.save(reloaded);
        assertEquals(2, palettes.findById(saved.getId()).orElseThrow().getShades().size());
    }
}
