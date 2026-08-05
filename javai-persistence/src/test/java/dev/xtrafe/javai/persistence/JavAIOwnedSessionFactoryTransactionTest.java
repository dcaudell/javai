package dev.xtrafe.javai.persistence;

import dev.xtrafe.javai.model.JavAIRuntime;
import dev.xtrafe.javai.vector.testsupport.FakeEmbeddingProvider;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.service.UnknownUnwrapTypeException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.orm.jpa.hibernate.HibernateTransactionManager;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.vendor.HibernateJpaDialect;
import org.springframework.transaction.InvalidIsolationLevelException;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * OMI-160: the <em>other</em> way to get Spring transactions, now that
 * {@link JavAIPI#sessionFactory(JavAIPersistenceConfig)} exists.
 *
 * <p>{@link SpringTransactionalIntegrationTest} covers the case where <b>Spring owns the factory</b> and
 * hands it to JavAI via {@code JavAIPersistenceConfig.Builder.sessionFactory}. That works, but it skips the
 * mapping-time hooks JavAI can only apply to a factory it builds itself -- so an interface-typed
 * {@code @OneToMany JavAIList<T>} maps as a plain Hibernate bag, and the application trades working JavAI
 * collections for working transactions.
 *
 * <p>This covers the inverse, which is what a JavAI-first application actually wants: <b>JavAI owns the
 * factory</b> (no {@code Builder.sessionFactory(...)} call anywhere below), the application asks for that
 * same instance, and wires its own transaction manager onto it. The bridge OMI-146 built already matches on
 * factory identity, so nothing else had to change -- the only thing missing was visibility, which is exactly
 * what the consumer's own reflection-based spike had demonstrated before this accessor existed.
 */
@Testcontainers
class JavAIOwnedSessionFactoryTransactionTest {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    private static JavAIPersistenceConfig config;
    private static TestArticleRepository repository;

    /** A second, independently-configured factory over the same database -- the isolation recipe needs a
     *  Hibernate property, and a property is fixed at the moment the factory is built. */
    private static JavAIPersistenceConfig isolationConfig;
    private static TestArticleRepository isolationRepository;

    @BeforeAll
    static void configure() {
        JavAIRuntime.configureEmbeddingProvider(new FakeEmbeddingProvider());
        // Deliberately no .sessionFactory(...): JavAI builds and owns this one.
        config = JavAIPersistenceConfig.builder()
                .backend(JavAIPersistenceConfig.Backend.POSTGRES)
                .postgresUrl(postgres.getJdbcUrl())
                .postgresUsername(postgres.getUsername())
                .postgresPassword(postgres.getPassword())
                .build();
        // Registration before use -- building the factory freezes the entity set, and asking for the
        // factory is one of the things that builds it.
        repository = JavAIPI.repository(TestArticleRepository.class, config);

        isolationConfig = JavAIPersistenceConfig.builder()
                .backend(JavAIPersistenceConfig.Backend.POSTGRES)
                .postgresUrl(postgres.getJdbcUrl())
                .postgresUsername(postgres.getUsername())
                .postgresPassword(postgres.getPassword())
                .hibernateProperty("hibernate.connection.handling_mode", "DELAYED_ACQUISITION_AND_HOLD")
                .build();
        isolationRepository = JavAIPI.repository(TestArticleRepository.class, isolationConfig);
    }

    @Test
    void exposesTheFactoryTheRepositoriesActuallyRunOn() {
        SessionFactory factory = JavAIPI.sessionFactory(config);

        assertNotNull(factory);
        assertTrue(factory.isOpen());
        assertSame(factory, JavAIPI.sessionFactory(config),
                "repeated calls must hand back the one instance, not build a second factory");
    }

    /** The payoff: a commit through a plain Spring {@code TransactionTemplate} is visible afterwards. */
    @Test
    void aCommittedSpringTransactionPersistsRepositoryWork() {
        TransactionTemplate template = new TransactionTemplate(new JpaTransactionManager(JavAIPI.sessionFactory(config)));

        UUID id = template.execute(status ->
                repository.save(new TestArticle("committed title", "committed body")).getId());

        assertEquals(1, rowsWithId(id), "a committed transaction's repository work must survive");
    }

    /** ...and the half that actually proves the transaction is real: a rollback takes the work with it. */
    @Test
    void aRolledBackSpringTransactionDiscardsRepositoryWork() {
        TransactionTemplate template = new TransactionTemplate(new JpaTransactionManager(JavAIPI.sessionFactory(config)));
        UUID[] captured = new UUID[1];

        assertThrows(IllegalStateException.class, () -> template.execute(status -> {
            captured[0] = repository.save(new TestArticle("doomed title", "doomed body")).getId();
            throw new IllegalStateException("forcing a rollback");
        }));

        assertEquals(0, rowsWithId(captured[0]),
                "work done inside a rolled-back transaction must not survive it");
    }

    /** Several repository calls in one template body must be one atomic unit, not one transaction each. */
    @Test
    void severalRepositoryCallsInOneTransactionRollBackTogether() {
        TransactionTemplate template = new TransactionTemplate(new JpaTransactionManager(JavAIPI.sessionFactory(config)));
        UUID[] captured = new UUID[2];

        assertThrows(IllegalStateException.class, () -> template.execute(status -> {
            captured[0] = repository.save(new TestArticle("first of two", "body")).getId();
            captured[1] = repository.save(new TestArticle("second of two", "body")).getId();
            throw new IllegalStateException("forcing a rollback after both saves");
        }));

        assertEquals(0, rowsWithId(captured[0]), "the first save must roll back with the second");
        assertEquals(0, rowsWithId(captured[1]));
    }

    /**
     * Pins the {@code HibernateTransactionManager} vs {@code JpaTransactionManager} distinction the ticket
     * asked to document, as a test rather than a sentence -- so it stays true. {@code HibernateTransactionManager}'s
     * constructor eagerly unwraps a {@code javax.sql.DataSource} to share connections with plain JDBC, and a
     * JavAI-built factory configures Hibernate's own connection provider from raw
     * {@code jakarta.persistence.jdbc.*} settings, so there is no {@code DataSource} to unwrap.
     * {@code JpaTransactionManager} takes the factory directly, since a {@code SessionFactory} <em>is</em> a
     * {@code jakarta.persistence.EntityManagerFactory}.
     */
    @Test
    void hibernateTransactionManagerIsNotUsableWithAJavAIOwnedFactory() {
        SessionFactory factory = JavAIPI.sessionFactory(config);
        assertThrows(UnknownUnwrapTypeException.class, () -> new HibernateTransactionManager(factory),
                "documented limitation: use JpaTransactionManager with a JavAI-owned factory");
    }

    /**
     * Raising isolation on a JavAI-owned factory fails on <em>every</em> annotated call unless the transaction
     * manager is given a real dialect -- which is worth pinning, because the failure looks nothing like its
     * cause. {@code JpaTransactionManager}'s default {@code DefaultJpaDialect} cannot prepare a JDBC
     * connection, so rather than degrading to the default isolation it refuses outright, and it refuses at
     * every call site that asks, not just the one that needed the stronger guarantee.
     *
     * <p>Documented in {@code JavAI_Usage_Guide.md}'s transactions section alongside the fix below.
     */
    @Test
    void raisingIsolationIsRefusedByTheDefaultJpaDialect() {
        TransactionTemplate template =
                new TransactionTemplate(new JpaTransactionManager(JavAIPI.sessionFactory(config)));
        template.setIsolationLevel(TransactionDefinition.ISOLATION_SERIALIZABLE);

        assertThrows(InvalidIsolationLevelException.class,
                () -> template.execute(status -> repository.save(new TestArticle("never written", "body"))),
                "the default JpaDialect refuses a raised isolation level rather than degrading to the default");
    }

    /**
     * ...and what actually makes it work. <b>Two</b> settings are load-bearing, and they must be applied
     * together -- either alone still fails:
     *
     * <ol>
     *   <li>{@code JpaTransactionManager.setJpaDialect(new HibernateJpaDialect())} -- replaces the dialect
     *       that refused above;</li>
     *   <li>{@code .hibernateProperty("hibernate.connection.handling_mode", "DELAYED_ACQUISITION_AND_HOLD")}
     *       -- the dialect prepares the connection once at transaction start and needs it held to the end.
     *       Hibernate's default releases it after each statement, and Spring refuses the transaction outright
     *       rather than running it at an isolation level it could not guarantee.</li>
     * </ol>
     *
     * <p>The second is the JavAI-side one, and the one with no visible connection to the symptom -- which is
     * why the recipe is documented as a set rather than as independent knobs.
     *
     * <p>{@code dialect.setPrepareConnection(true)} is kept below and is <em>not</em> a third requirement:
     * measured, it is already Spring's default, and removing this line leaves the test passing. It is written
     * out because it is the other half of what Spring's own refusal message names, so a reader who has turned
     * it off elsewhere can see that it has to be on.
     */
    @Test
    void raisingIsolationWorksWithAPreparingDialectAndAHeldConnection() {
        SessionFactory factory = JavAIPI.sessionFactory(isolationConfig);

        HibernateJpaDialect dialect = new HibernateJpaDialect();
        dialect.setPrepareConnection(true);
        JpaTransactionManager manager = new JpaTransactionManager(factory);
        manager.setJpaDialect(dialect);

        TransactionTemplate template = new TransactionTemplate(manager);
        template.setIsolationLevel(TransactionDefinition.ISOLATION_SERIALIZABLE);

        int isolation = template.execute(status -> {
            isolationRepository.save(new TestArticle("written under serializable", "body"));
            Session joined = SpringManagedSessions.current(factory);
            assertNotNull(joined, "expected to find the Spring-managed session JavAI just wrote through");
            return joined.doReturningWork(Connection::getTransactionIsolation);
        });

        assertEquals(Connection.TRANSACTION_SERIALIZABLE, isolation,
                "the isolation the caller asked for must reach the connection JavAI writes through");
    }

    /** Postgres-only, and it says so rather than returning null or failing obscurely later. */
    @Test
    void nonPostgresBackendsRejectTheRequestClearly() {
        JavAIPersistenceConfig neo4j = JavAIPersistenceConfig.builder()
                .backend(JavAIPersistenceConfig.Backend.NEO4J)
                .neo4jUri("bolt://localhost:7687")
                .neo4jUsername("neo4j")
                .neo4jPassword("unused")
                .build();

        IllegalArgumentException thrown =
                assertThrows(IllegalArgumentException.class, () -> JavAIPI.sessionFactory(neo4j));
        assertTrue(thrown.getMessage().contains("Postgres-only"), thrown.getMessage());
    }

    /** Committed state, read over a separate connection -- reading through the repository or the shared
     *  session would see uncommitted work and prove nothing. */
    private static int rowsWithId(UUID id) {
        try (Connection connection = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT COUNT(*) FROM test_article WHERE id = ?")) {
            statement.setObject(1, id);
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getInt(1);
            }
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
