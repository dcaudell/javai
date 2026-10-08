package dev.xtrafe.javai.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.xtrafe.javai.model.JavAIRuntime;
import dev.xtrafe.javai.vector.testsupport.FakeEmbeddingProvider;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/** {@link JavAIPI#pendingSummaryCount}: how many recomputations are queued, read from JavAI's own database. */
@Testcontainers
class PendingSummaryCountTest {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    private static JavAIPersistenceConfig config;
    private static TestShelfRepository shelves;

    @BeforeAll
    static void configurePersistence() {
        JavAIRuntime.configureEmbeddingProvider(new FakeEmbeddingProvider());
        config = JavAIPersistenceConfig.builder()
                .backend(JavAIPersistenceConfig.Backend.POSTGRES)
                .postgresUrl(postgres.getJdbcUrl())
                .postgresUsername(postgres.getUsername())
                .postgresPassword(postgres.getPassword())
                .build();
        shelves = JavAIPI.repository(TestShelfRepository.class, config);
        JavAIPI.repository(TestBookRepository.class, config);
    }

    @Test
    @DisplayName("counts what a QUEUE_ONLY save left, and nothing once it is drained")
    void countsTheQueue() {
        JavAIPI.drainPendingSummaries(config);
        assertEquals(0L, JavAIPI.pendingSummaryCount(config));

        TestShelf shelf = new TestShelf("pending-shelf");
        shelf.getBooks().add(new TestBook("pending-book"));
        shelves.save(shelf, SummaryPolicy.QUEUE_ONLY);

        assertTrue(JavAIPI.pendingSummaryCount(config) > 0);

        JavAIPI.drainPendingSummaries(config);
        assertEquals(0L, JavAIPI.pendingSummaryCount(config));
    }
}
