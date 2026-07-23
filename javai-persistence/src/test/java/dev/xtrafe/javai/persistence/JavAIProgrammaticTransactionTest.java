package dev.xtrafe.javai.persistence;

import dev.xtrafe.javai.model.JavAIRuntime;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * OMI-146, the programmatic half: {@link JavAIPI#inTransaction} composes several repository calls into one
 * unit of work for callers who are not running under Spring. Before it, each call opened its own session and
 * committed on its own, so a failure partway through left the earlier writes permanently committed.
 *
 * <p>Every assertion reads back over a <em>separate JDBC connection</em>, never through the repository:
 * what's being proven is what actually committed to the database, and a read issued on the same session
 * would happily show uncommitted work and prove nothing.
 */
@Testcontainers
class JavAIProgrammaticTransactionTest {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    private static JavAIPersistenceConfig config;
    private static TestTxRecordRepository repository;

    @BeforeAll
    static void configure() {
        JavAIRuntime.configureEmbeddingProvider(new FakeEmbeddingProvider());
        config = JavAIPersistenceConfig.builder()
                .backend(JavAIPersistenceConfig.Backend.POSTGRES)
                .postgresUrl(postgres.getJdbcUrl())
                .postgresUsername(postgres.getUsername())
                .postgresPassword(postgres.getPassword())
                .build();
        repository = JavAIPI.repository(TestTxRecordRepository.class, config);
    }

    @BeforeEach
    void resetProvider() {
        JavAIRuntime.configureEmbeddingProvider(new FakeEmbeddingProvider());
    }

    @Test
    void severalWritesInOneBodyCommitTogether() {
        String label = "committed-" + UUID.randomUUID();

        JavAIPI.inTransaction(config, () -> {
            repository.save(new TestTxRecord(label));
            repository.save(new TestTxRecord(label));
        });

        assertEquals(2, committedRowsLabelled(label), "both writes must be visible after the body returns");
    }

    /** The motivating scenario from the ticket: a failure on the last write must not leave the earlier ones
     *  permanently committed, which is exactly what per-call transactions used to do. */
    @Test
    void aFailureRollsBackEveryWriteInTheBody() {
        String label = "rolled-back-" + UUID.randomUUID();

        assertThrows(IllegalStateException.class, () -> JavAIPI.inTransaction(config, () -> {
            repository.save(new TestTxRecord(label));
            repository.save(new TestTxRecord(label));
            throw new IllegalStateException("boom, after two successful writes");
        }));

        assertEquals(0, committedRowsLabelled(label),
                "a write made before the failure must roll back with it, not survive independently");
    }

    /** Read-your-own-writes: a read inside the body runs on the same session, so it sees work the body has
     *  written but not yet committed -- which a separate per-call session could not. */
    @Test
    void readsInsideTheBodySeeTheBodysOwnUncommittedWrites() {
        String label = "read-your-writes-" + UUID.randomUUID();

        boolean foundInside = JavAIPI.inTransaction(config, () -> {
            TestTxRecord saved = repository.save(new TestTxRecord(label));
            assertEquals(0, committedRowsLabelled(label), "nothing is committed yet, by construction");
            return repository.findById(saved.getId()).isPresent();
        });

        assertTrue(foundInside, "the body's own read must see the body's own write");
        assertEquals(1, committedRowsLabelled(label), "and it commits when the body returns");
    }

    /**
     * A nested body joins the transaction already in progress rather than starting its own -- Spring's
     * {@code PROPAGATION_REQUIRED} semantics, which is what lets two methods that each wrap their own work
     * this way be called from one another. Proven the only way that matters: the inner body's write does not
     * survive the outer body's failure.
     */
    @Test
    void aNestedBodyJoinsTheOuterTransactionRatherThanCommittingOnItsOwn() {
        String label = "nested-" + UUID.randomUUID();

        assertThrows(IllegalStateException.class, () -> JavAIPI.inTransaction(config, () -> {
            JavAIPI.inTransaction(config, () -> repository.save(new TestTxRecord(label)));
            assertEquals(0, committedRowsLabelled(label),
                    "the inner body must not have committed on its own when it returned");
            throw new IllegalStateException("outer body fails after the inner one returned");
        }));

        assertEquals(0, committedRowsLabelled(label),
                "the inner body's write must roll back with the outer transaction it joined");
    }

    /** The scope is per-thread and per-factory, so it must not leak into the next call on this thread. */
    @Test
    void theScopeIsClearedAfterTheBodyEvenWhenItThrows() {
        assertThrows(RuntimeException.class,
                () -> JavAIPI.inTransaction(config, () -> {
                    throw new RuntimeException("boom");
                }));

        String label = "after-failure-" + UUID.randomUUID();
        repository.save(new TestTxRecord(label));
        assertEquals(1, committedRowsLabelled(label),
                "an ordinary call after a failed inTransaction must commit on its own again");
    }

    private static int committedRowsLabelled(String label) {
        try (Connection connection = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT count(*) FROM test_tx_record WHERE label = ?")) {
            statement.setString(1, label);
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getInt(1);
            }
        } catch (Exception e) {
            throw new IllegalStateException("could not count committed rows for " + label, e);
        }
    }

    interface TestTxRecordRepository extends JavAIRepository<TestTxRecord> {
    }
}
