package dev.xtrafe.javai.persistence;

import dev.xtrafe.javai.model.JavAIRuntime;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.persistenceunit.PersistenceManagedTypes;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.NestedTransactionNotSupportedException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Properties;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * OMI-146's headline claim, tested rather than asserted in prose: an ordinary Spring {@code @Transactional}
 * method governs {@code JavAIRepository} calls made inside it, with no JavAI-specific API at the call site.
 * Before this, every repository call opened its own session and committed independently, so a
 * {@code @Transactional} service composing several of them had no atomicity at all.
 *
 * <p>The wiring is the realistic one, not a convenience: a Spring {@code LocalContainerEntityManagerFactoryBean}
 * owns the {@code EntityManagerFactory}, {@code JpaTransactionManager} drives the transactions, and JavAI is
 * handed that same factory via {@code JavAIPersistenceConfig.Builder.sessionFactory}. Note this is the case
 * a keyed resource lookup would miss -- Spring binds its {@code EntityManagerHolder} under the EMF
 * <em>proxy</em> while JavAI holds the unwrapped native {@code SessionFactory} -- which is why
 * {@code SpringManagedSessions} scans the bound resources instead. A second context proves the
 * {@code HibernateTransactionManager} path works too.
 *
 * <p>Every assertion reads committed state over a <b>separate JDBC connection</b>. Reading through the
 * repository or the shared session would see uncommitted work and prove nothing about what the transaction
 * actually did.
 *
 * <p>The propagation/isolation/readOnly/timeout/rollback-rule cases below are deliberately exhaustive over
 * {@code @Transactional}'s own attributes: the value of "it just works" is entirely in whether it still
 * works at the edges, and each case here pins one attribute's real, measured behavior -- including the two
 * ({@code NOT_SUPPORTED}, {@code NEVER}) where the correct behavior is for JavAI to NOT join.
 */
@Testcontainers
class SpringTransactionalIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    private static AnnotationConfigApplicationContext context;
    private static OuterService outer;
    private static ClassLevelService classLevel;

    @BeforeAll
    static void startSpring() {
        JavAIRuntime.configureEmbeddingProvider(new FakeEmbeddingProvider());
        context = new AnnotationConfigApplicationContext(JpaConfig.class);
        outer = context.getBean(OuterService.class);
        classLevel = context.getBean(ClassLevelService.class);
    }

    @AfterAll
    static void stopSpring() {
        if (context != null) {
            context.close();
        }
    }

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

        assertThrows(IllegalStateException.class, () -> outer.twoWritesThenFail(label));

        assertEquals(0, committed(label), "an earlier write must not survive a later failure in the same method");
    }

    @Test
    void writesInOneTransactionalMethodCommitTogether() {
        String label = label();

        outer.twoWrites(label);

        assertEquals(2, committed(label));
    }

    /** Class-level {@code @Transactional} must behave exactly like the method-level form -- the annotation is
     *  resolved by Spring before JavAI sees anything, but the whole point is that JavAI needs to know nothing
     *  about where it was declared. */
    @Test
    void classLevelTransactionalBehavesTheSameAsMethodLevel() {
        String label = label();

        assertThrows(IllegalStateException.class, () -> classLevel.twoWritesThenFail(label));

        assertEquals(0, committed(label), "class-level @Transactional must roll back the same way");
    }

    /** Read-your-own-writes across two separate repository calls in one transaction: only possible if the
     *  second call joined the first one's session rather than opening its own. */
    @Test
    void aLaterCallSeesAnEarlierCallsUncommittedWrite() {
        String label = label();

        assertTrue(outer.writeThenReadBack(label), "the second repository call must see the first one's write");
    }

    // ---- propagation --------------------------------------------------------------------------

    @Test
    void propagationRequiresNewCommitsIndependentlyOfTheOuterRollback() {
        String outerLabel = label();
        String innerLabel = label();

        assertThrows(IllegalStateException.class, () -> outer.writeThenRequiresNewThenFail(outerLabel, innerLabel));

        assertEquals(0, committed(outerLabel), "the outer transaction rolled back");
        assertEquals(1, committed(innerLabel),
                "REQUIRES_NEW is a genuinely separate transaction, so its write must survive");
    }

    @Test
    void propagationMandatoryFailsOutsideATransactionAndWorksInside() {
        assertThrows(IllegalTransactionStateException.class, () -> outer.inner().mandatoryWrite(label()));

        String label = label();
        outer.callMandatoryWithinTransaction(label);
        assertEquals(1, committed(label));
    }

    @Test
    void propagationSupportsRunsWithoutATransactionWhenThereIsNone() {
        String label = label();

        outer.inner().supportsWrite(label);

        assertEquals(1, committed(label),
                "with no transaction to join, the call falls back to committing on its own");
    }

    /** NOT_SUPPORTED suspends the caller's transaction, so JavAI must NOT join it -- proven by the write
     *  surviving the outer rollback. Joining a suspended transaction would be a real defect. */
    @Test
    void propagationNotSupportedDoesNotJoinTheSuspendedTransaction() {
        String outerLabel = label();
        String innerLabel = label();

        assertThrows(IllegalStateException.class, () -> outer.writeThenNotSupportedThenFail(outerLabel, innerLabel));

        assertEquals(0, committed(outerLabel));
        assertEquals(1, committed(innerLabel),
                "work done under NOT_SUPPORTED is outside the caller's transaction and must survive its rollback");
    }

    @Test
    void propagationNeverRejectsBeingCalledInsideATransaction() {
        assertThrows(IllegalTransactionStateException.class, () -> outer.callNeverWithinTransaction(label()));
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
                () -> outer.writeThenNestedFailureIsCaught(label(), label()));

        assertTrue(thrown.getMessage().contains("savepoints"),
                "the refusal must come from Spring's savepoint support, not from JavAI; got: "
                        + thrown.getMessage());
    }

    /** The same nested call under {@code HibernateTransactionManager}, which does support savepoints: the
     *  savepoint rolls back without losing the outer transaction's own JavAI write. */
    @Test
    void propagationNestedRollsBackToTheSavepointUnderHibernateTransactionManager() {
        try (AnnotationConfigApplicationContext hibernateContext =
                new AnnotationConfigApplicationContext(HibernateConfig.class)) {
            OuterService service = hibernateContext.getBean(OuterService.class);
            String outerLabel = label();
            String innerLabel = label();

            service.writeThenNestedFailureIsCaught(outerLabel, innerLabel);

            assertEquals(1, committed(outerLabel), "the outer write survives a rolled-back savepoint");
            assertEquals(0, committed(innerLabel), "the nested write is undone by its savepoint rollback");
        }
    }

    // ---- isolation, readOnly, timeout ----------------------------------------------------------

    @Test
    void isolationIsAppliedToTheConnectionJavAIWritesThrough() {
        String label = label();

        int isolation = outer.writeUnderSerializableAndReportIsolation(label);

        assertEquals(Connection.TRANSACTION_SERIALIZABLE, isolation,
                "JavAI must be writing through the very connection Spring configured, isolation included");
        assertEquals(1, committed(label));
    }

    @Test
    void readOnlyTransactionsStillServeReads() {
        String label = label();
        outer.twoWrites(label);

        assertEquals(2, outer.countUnderReadOnly(label), "a read-only transaction must still read");
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

        assertThrows(RuntimeException.class, () -> outer.writeUnderReadOnly(label),
                "Postgres rejects an INSERT in a read-only transaction");

        assertEquals(0, committed(label), "and nothing is written");
    }

    @Test
    void aTransactionPastItsTimeoutFailsRatherThanCommitting() {
        String label = label();

        assertThrows(RuntimeException.class, () -> outer.writeSleepThenWriteWithTimeout(label));

        assertEquals(0, committed(label), "nothing from a timed-out transaction may commit");
    }

    // ---- rollback rules -----------------------------------------------------------------------

    /** Spring's default: a checked exception does not trigger rollback, so the write commits. */
    @Test
    void aCheckedExceptionCommitsByDefault() {
        String label = label();

        assertThrows(TestCheckedException.class, () -> outer.writeThenThrowChecked(label));

        assertEquals(1, committed(label), "a checked exception does not roll back unless asked to");
    }

    @Test
    void rollbackForMakesACheckedExceptionRollBack() {
        String label = label();

        assertThrows(TestCheckedException.class, () -> outer.writeThenThrowCheckedWithRollbackFor(label));

        assertEquals(0, committed(label), "rollbackFor must extend rollback to the checked exception");
    }

    @Test
    void noRollbackForKeepsAWriteDespiteARuntimeException() {
        String label = label();

        assertThrows(IllegalStateException.class, () -> outer.writeThenThrowWithNoRollbackFor(label));

        assertEquals(1, committed(label), "noRollbackFor must suppress the default runtime-exception rollback");
    }

    // ---- the other transaction manager ---------------------------------------------------------

    /**
     * {@code HibernateTransactionManager} binds a {@code SessionHolder} rather than an
     * {@code EntityManagerHolder}; since the former extends the latter, the same bridge covers it. Proven in
     * its own context so the two managers can't mask each other.
     */
    @Test
    void hibernateTransactionManagerIsAlsoJoined() {
        try (AnnotationConfigApplicationContext hibernateContext =
                new AnnotationConfigApplicationContext(HibernateConfig.class)) {
            OuterService service = hibernateContext.getBean(OuterService.class);
            String label = label();

            assertThrows(IllegalStateException.class, () -> service.twoWritesThenFail(label));

            assertEquals(0, committed(label),
                    "HibernateTransactionManager's own unit of work must govern JavAI calls too");
        }
    }

    // ---- wiring -------------------------------------------------------------------------------

    /** JPA wiring, as a Spring Boot application would have it: LCEMFB + JpaTransactionManager. */
    @Configuration
    @EnableTransactionManagement
    static class JpaConfig {

        @Bean
        DataSource dataSource() {
            DriverManagerDataSource dataSource = new DriverManagerDataSource();
            dataSource.setUrl(postgres.getJdbcUrl());
            dataSource.setUsername(postgres.getUsername());
            dataSource.setPassword(postgres.getPassword());
            return dataSource;
        }

        @Bean
        LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource dataSource) {
            LocalContainerEntityManagerFactoryBean factory = new LocalContainerEntityManagerFactoryBean();
            factory.setDataSource(dataSource);
            // Exactly one entity, never a package scan: this package also holds fixtures with JavAI
            // collection fields, which plain Hibernate cannot map without JavAI's own mapping-time
            // <transient> override -- and that override only runs in the factory JavAI builds itself, not in
            // one Spring owns. Scanning would fail the context on an entity irrelevant to these tests.
            factory.setManagedTypes(PersistenceManagedTypes.of(TestTxRecord.class.getName()));
            factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
            factory.setJpaProperties(hibernateProperties());
            return factory;
        }

        @Bean
        PlatformTransactionManager transactionManager(EntityManagerFactory entityManagerFactory,
                DataSource dataSource) {
            JpaTransactionManager manager = new JpaTransactionManager(entityManagerFactory);
            // Both lines are needed for PROPAGATION_NESTED: without the DataSource, Spring cannot reach a
            // JDBC connection to open a savepoint on and reports "JpaDialect does not support savepoints".
            manager.setDataSource(dataSource);
            manager.setNestedTransactionAllowed(true);
            return manager;
        }

        @Bean
        JavAIPersistenceConfig javAIConfig(EntityManagerFactory entityManagerFactory) {
            return sharedFactoryConfig(entityManagerFactory.unwrap(SessionFactory.class));
        }

        @Bean
        SpringTxRecordRepository repository(JavAIPersistenceConfig config) {
            return JavAIPI.repository(SpringTxRecordRepository.class, config);
        }

        @Bean
        InnerService innerService(SpringTxRecordRepository repository) {
            return new InnerService(repository);
        }

        @Bean
        OuterService outerService(SpringTxRecordRepository repository, InnerService inner,
                EntityManagerFactory entityManagerFactory) {
            // The NATIVE factory, not the proxy unwrap(SessionFactory.class) yields: that is the identity
            // the backend normalizes to, and the one a Spring-managed session reports as its own.
            return new OuterService(repository, inner,
                    entityManagerFactory.unwrap(org.hibernate.engine.spi.SessionFactoryImplementor.class));
        }

        @Bean
        ClassLevelService classLevelService(SpringTxRecordRepository repository) {
            return new ClassLevelService(repository);
        }
    }

    /** The same application, wired the other supported way: a Hibernate {@code SessionFactory} bean plus
     *  {@code HibernateTransactionManager}. */
    @Configuration
    @EnableTransactionManagement
    static class HibernateConfig extends JpaConfig {

        @Bean
        @Override
        PlatformTransactionManager transactionManager(EntityManagerFactory entityManagerFactory,
                DataSource dataSource) {
            var manager = new org.springframework.orm.jpa.hibernate.HibernateTransactionManager(
                    entityManagerFactory.unwrap(SessionFactory.class));
            manager.setNestedTransactionAllowed(true);
            return manager;
        }
    }

    private static Properties hibernateProperties() {
        Properties properties = new Properties();
        properties.setProperty("hibernate.hbm2ddl.auto", "update");
        properties.setProperty("hibernate.physical_naming_strategy",
                "org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy");
        return properties;
    }

    private static JavAIPersistenceConfig sharedFactoryConfig(SessionFactory sessionFactory) {
        return JavAIPersistenceConfig.builder()
                .backend(JavAIPersistenceConfig.Backend.POSTGRES)
                .postgresUrl(postgres.getJdbcUrl())
                .postgresUsername(postgres.getUsername())
                .postgresPassword(postgres.getPassword())
                .sessionFactory(sessionFactory)
                .build();
    }

    private static String label() {
        return "spring-" + UUID.randomUUID();
    }

    /** Committed rows only: a separate connection, so nothing in-flight can be mistaken for committed. */
    private static int committed(String label) {
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

    interface SpringTxRecordRepository extends JavAIRepository<TestTxRecord> {
    }

    static class TestCheckedException extends Exception {
        TestCheckedException(String message) {
            super(message);
        }
    }
}
