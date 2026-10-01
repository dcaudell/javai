package dev.xtrafe.javai.persistence;

import dev.xtrafe.javai.model.JavAIRuntime;
import dev.xtrafe.javai.vector.testsupport.FakeEmbeddingProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.NestedTransactionNotSupportedException;
import org.testcontainers.containers.PostgreSQLContainer;
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
 * Every claim OMI-146 makes about {@code @Transactional}, asserted identically against <b>both</b> supported
 * topologies (OMI-275, Topic 2).
 *
 * <h2>Why this is a base class rather than one test</h2>
 *
 * JavAI can be wired to Spring two ways, and until now only one of them was tested:
 *
 * <ul>
 *   <li><b>Spring builds the factory</b>, JavAI is handed it via {@code Builder.sessionFactory(...)} --
 *       {@link SpringTransactionalIntegrationTest}.</li>
 *   <li><b>JavAI builds the factory</b>, Spring's {@code JpaTransactionManager} is pointed at it --
 *       {@link JavAIOwnedFactoryTransactionalTest}.</li>
 * </ul>
 *
 * <p>The second is not an exotic variant: a consumer whose entities have JavAI collection fields <em>cannot
 * use the first</em>, because only JavAI's own {@code buildSessionFactory} attaches the collection types and
 * the transient-override. So the topology that had no coverage is the one every real vectorized consumer is
 * obliged to use.
 *
 * <p>Subclassing rather than parameterising, deliberately: each topology needs its own Spring context built
 * from its own {@code @Configuration}, and a context is not a test parameter. What matters is that the
 * assertions are written once here -- <b>"works" cannot come to mean two different things in the two
 * topologies</b>, which is exactly what two independently-maintained copies would eventually allow.
 *
 * <p>One shared container for both suites: these tests are about transaction semantics, not about isolation
 * between suites, and every assertion reads committed state over its own connection filtered by a unique
 * label. Started once here rather than by the Testcontainers extension, so subclasses share it instead of
 * starting one Postgres each.
 */
abstract class SpringTransactionalConformance {

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    static {
        POSTGRES.start();
    }

    /** The method-level {@code @Transactional} fixture, from this topology's own context. */
    abstract OuterService outer();

    /** The class-level one. */
    abstract ClassLevelService classLevel();

    /** This topology's config, for the cases that reach for {@code JavAIPI} directly. */
    abstract JavAIPersistenceConfig config();

    /** This topology's repository, likewise. */
    abstract SpringTxRecordRepository repository();

    @BeforeEach
    void resetProvider() {
        JavAIRuntime.configureEmbeddingProvider(new FakeEmbeddingProvider());
    }

    // ---- the core claim ------------------------------------------------------------------------

    /** The ticket's motivating scenario: several repository calls in one {@code @Transactional} method, one
     *  of which fails. Under per-call transactions the earlier writes stayed committed. */
    @Test
    void writesInOneTransactionalMethodRollBackTogether() {
        String label = label();

        assertThrows(IllegalStateException.class, () -> outer().twoWritesThenFail(label));

        assertEquals(0, committed(label), "an earlier write must not survive a later failure in the same method");
    }

    @Test
    void writesInOneTransactionalMethodCommitTogether() {
        String label = label();

        outer().twoWrites(label);

        assertEquals(2, committed(label));
    }

    /** Class-level {@code @Transactional} must behave exactly like the method-level form -- the annotation is
     *  resolved by Spring before JavAI sees anything, but the whole point is that JavAI needs to know nothing
     *  about where it was declared. */
    @Test
    void classLevelTransactionalBehavesTheSameAsMethodLevel() {
        String label = label();

        assertThrows(IllegalStateException.class, () -> classLevel().twoWritesThenFail(label));

        assertEquals(0, committed(label), "class-level @Transactional must roll back the same way");
    }

    /** Read-your-own-writes across two separate repository calls in one transaction: only possible if the
     *  second call joined the first one's session rather than opening its own. */
    @Test
    void aLaterCallSeesAnEarlierCallsUncommittedWrite() {
        String label = label();

        assertTrue(outer().writeThenReadBack(label), "the second repository call must see the first one's write");
    }

    // ---- propagation --------------------------------------------------------------------------

    @Test
    void propagationRequiresNewCommitsIndependentlyOfTheOuterRollback() {
        String outerLabel = label();
        String innerLabel = label();

        assertThrows(IllegalStateException.class, () -> outer().writeThenRequiresNewThenFail(outerLabel, innerLabel));

        assertEquals(0, committed(outerLabel), "the outer transaction rolled back");
        assertEquals(1, committed(innerLabel),
                "REQUIRES_NEW is a genuinely separate transaction, so its write must survive");
    }

    @Test
    void propagationMandatoryFailsOutsideATransactionAndWorksInside() {
        assertThrows(IllegalTransactionStateException.class, () -> outer().inner().mandatoryWrite(label()));

        String label = label();
        outer().callMandatoryWithinTransaction(label);
        assertEquals(1, committed(label));
    }

    @Test
    void propagationSupportsRunsWithoutATransactionWhenThereIsNone() {
        String label = label();

        outer().inner().supportsWrite(label);

        assertEquals(1, committed(label),
                "with no transaction to join, the call falls back to committing on its own");
    }

    /** NOT_SUPPORTED suspends the caller's transaction, so JavAI must NOT join it -- proven by the write
     *  surviving the outer rollback. Joining a suspended transaction would be a real defect. */
    @Test
    void propagationNotSupportedDoesNotJoinTheSuspendedTransaction() {
        String outerLabel = label();
        String innerLabel = label();

        assertThrows(IllegalStateException.class, () -> outer().writeThenNotSupportedThenFail(outerLabel, innerLabel));

        assertEquals(0, committed(outerLabel));
        assertEquals(1, committed(innerLabel),
                "work done under NOT_SUPPORTED is outside the caller's transaction and must survive its rollback");
    }

    @Test
    void propagationNeverRejectsBeingCalledInsideATransaction() {
        assertThrows(IllegalTransactionStateException.class, () -> outer().callNeverWithinTransaction(label()));
    }

    /**
     * {@code PROPAGATION_NESTED} is refused under {@code JpaTransactionManager} -- measured, and <em>not</em>
     * a JavAI limitation: Spring's Hibernate JPA dialect exposes no {@code SavepointManager}, so the
     * transaction manager rejects the nested call before any repository code runs. Pinned here so the
     * limitation is attributed correctly if someone hits it; the sibling test below shows the same
     * propagation working under the other transaction manager.
     */
    @Test
    void propagationNestedIsUnsupportedUnderJpaTransactionManager() {
        NestedTransactionNotSupportedException thrown = assertThrows(NestedTransactionNotSupportedException.class,
                () -> outer().writeThenNestedFailureIsCaught(label(), label()));

        assertTrue(thrown.getMessage().contains("savepoints"),
                "the refusal must come from Spring's savepoint support, not from JavAI; got: "
                        + thrown.getMessage());
    }

    // ---- isolation, readOnly, timeout ----------------------------------------------------------

    @Test
    void isolationIsAppliedToTheConnectionJavAIWritesThrough() {
        String label = label();

        int isolation = outer().writeUnderSerializableAndReportIsolation(label);

        assertEquals(Connection.TRANSACTION_SERIALIZABLE, isolation,
                "JavAI must be writing through the very connection Spring configured, isolation included");
        assertEquals(1, committed(label));
    }

    @Test
    void readOnlyTransactionsStillServeReads() {
        String label = label();
        outer().twoWrites(label);

        assertEquals(2, outer().countUnderReadOnly(label), "a read-only transaction must still read");
    }

    /**
     * A write attempted inside {@code readOnly = true} fails loudly rather than being silently dropped:
     * Spring marks the JDBC connection read-only, and Postgres itself rejects the INSERT. Measured, not
     * assumed -- the plausible-sounding alternative (Hibernate's read-only {@code FlushMode.MANUAL} quietly
     * discarding the insert) is what this test was originally written to assert, and it is wrong. The loud
     * failure is the better outcome, and it comes from the caller's own transaction settings reaching JavAI's
     * writes, which is the whole point.
     */
    @Test
    void writesInsideAReadOnlyTransactionFailLoudly() {
        String label = label();

        assertThrows(RuntimeException.class, () -> outer().writeUnderReadOnly(label),
                "Postgres rejects an INSERT in a read-only transaction");

        assertEquals(0, committed(label), "and nothing is written");
    }

    @Test
    void aTransactionPastItsTimeoutFailsRatherThanCommitting() {
        String label = label();

        assertThrows(RuntimeException.class, () -> outer().writeSleepThenWriteWithTimeout(label));

        assertEquals(0, committed(label), "nothing from a timed-out transaction may commit");
    }

    // ---- rollback rules -----------------------------------------------------------------------

    /** Spring's default: a checked exception does not trigger rollback, so the write commits. */
    @Test
    void aCheckedExceptionCommitsByDefault() {
        String label = label();

        assertThrows(TestCheckedException.class, () -> outer().writeThenThrowChecked(label));

        assertEquals(1, committed(label), "a checked exception does not roll back unless asked to");
    }

    @Test
    void rollbackForMakesACheckedExceptionRollBack() {
        String label = label();

        assertThrows(TestCheckedException.class, () -> outer().writeThenThrowCheckedWithRollbackFor(label));

        assertEquals(0, committed(label), "rollbackFor must extend rollback to the checked exception");
    }

    @Test
    void noRollbackForKeepsAWriteDespiteARuntimeException() {
        String label = label();

        assertThrows(IllegalStateException.class, () -> outer().writeThenThrowWithNoRollbackFor(label));

        assertEquals(1, committed(label), "noRollbackFor must suppress the default runtime-exception rollback");
    }


    // ---- JavAIPI.inTransaction meeting a Spring transaction ------------------------------------

    /**
     * A {@code JavAIPI.inTransaction} body <em>inside</em> a Spring transaction must join it, not open a
     * second one underneath it. If it opened its own, its writes would commit when its own block ended and
     * survive the outer rollback -- the split unit of work OMI-146 exists to prevent, reintroduced by the
     * one API that should know better.
     */
    @Test
    void javAiInTransactionInsideASpringTransactionJoinsIt() {
        String label = label();

        assertThrows(IllegalStateException.class, () -> outer().springThenJavAiInTransactionThenFail(label));

        assertEquals(0, committed(label),
                "a JavAI transaction block nested in a Spring one must roll back with it, not commit early");
    }

    /**
     * The reverse nesting, which is the one with a real chance of surprising someone: a
     * {@code @Transactional} method called from <em>inside</em> a {@code JavAIPI.inTransaction} body.
     *
     * <p>JavAI resolves its ambient session from its own scope first, so the repository write lands on
     * JavAI's session and is governed by JavAI's transaction -- Spring's transaction is real but has nothing
     * of JavAI's in it. Whatever the answer, it must be one answer: the write commits or rolls back with
     * exactly one of the two, never partially with both.
     */
    @Test
    void aSpringTransactionInsideAJavAiInTransactionBodyResolvesToOneUnitOfWork() {
        String label = label();

        assertThrows(IllegalStateException.class, () -> JavAIPI.inTransaction(config(), () -> {
            outer().twoWrites(label);
            throw new IllegalStateException("the JavAI-owned transaction fails after the Spring one returned");
        }));

        assertEquals(0, committed(label),
                "the enclosing JavAI transaction owns the write, so its rollback must take it");
    }

    /** And the committing counterpart, so the case above cannot pass merely because nothing was ever written. */
    @Test
    void aSpringTransactionInsideAJavAiInTransactionBodyCommitsOnce() {
        String label = label();

        JavAIPI.inTransaction(config(), () -> {
            outer().twoWrites(label);
            return null;
        });

        assertEquals(2, committed(label));
    }


    static String label() {
        return "spring-" + UUID.randomUUID();
    }

    /** Committed rows only: a separate connection, so nothing in-flight can be mistaken for committed. */
    static int committed(String label) {
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
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

    static class TestCheckedException extends Exception {
        TestCheckedException(String message) {
            super(message);
        }
    }
}
