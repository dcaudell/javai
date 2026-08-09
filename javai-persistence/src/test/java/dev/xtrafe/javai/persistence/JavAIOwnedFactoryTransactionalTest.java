package dev.xtrafe.javai.persistence;

import dev.xtrafe.javai.model.JavAIRuntime;
import dev.xtrafe.javai.vector.testsupport.FakeEmbeddingProvider;
import dev.xtrafe.javai.vector.testsupport.RecordingEmbeddingProvider;
import org.hibernate.SessionFactory;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.vendor.HibernateJpaDialect;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The whole {@link SpringTransactionalConformance} suite against the <b>other</b> topology: JavAI builds the
 * {@code SessionFactory}, Spring's {@code JpaTransactionManager} is pointed at that exact instance (OMI-275).
 *
 * <p>This is the arrangement a consumer with JavAI collection fields is <em>obliged</em> to use -- only
 * JavAI's own {@code buildSessionFactory} attaches the collection types -- and it is what omiai-platform
 * runs. It had no coverage at all before this class, which is how a regression report against it
 * (OMI-274) had no suite to have caught it.
 *
 * <p>Everything asserted here is inherited. What this class supplies is the wiring, and the two places the
 * topology genuinely cannot do what the other one does, each pinned below rather than left as an absence.
 */
class JavAIOwnedFactoryTransactionalTest extends SpringTransactionalConformance {

    private static AnnotationConfigApplicationContext context;
    private static OuterService outer;
    private static ClassLevelService classLevel;
    private static TransactionTemplate transactions;
    private static SpringTxRecordRepository repository;
    private static TestShelfRepository shelves;
    private static TestVenueRepository venues;

    @Override
    OuterService outer() {
        return outer;
    }

    @Override
    ClassLevelService classLevel() {
        return classLevel;
    }

    @Override
    JavAIPersistenceConfig config() {
        return context.getBean(JavAIPersistenceConfig.class);
    }

    @Override
    SpringTxRecordRepository repository() {
        return repository;
    }

    @BeforeAll
    static void startSpring() {
        JavAIRuntime.configureEmbeddingProvider(new FakeEmbeddingProvider());
        context = new AnnotationConfigApplicationContext(JavAIOwnedConfig.class);
        outer = context.getBean(OuterService.class);
        classLevel = context.getBean(ClassLevelService.class);
        transactions = context.getBean(TransactionTemplate.class);
        repository = context.getBean(SpringTxRecordRepository.class);
        shelves = context.getBean(TestShelfRepository.class);
        venues = context.getBean(TestVenueRepository.class);
    }

    @AfterAll
    static void stopSpring() {
        if (context != null) {
            context.close();
        }
    }

    /**
     * {@code TransactionTemplate}, not {@code @Transactional} -- the programmatic form needs no proxying, so
     * a failure here cannot be blamed on bean wiring. It is also the exact shape OMI-274 was reported
     * against, which is reason enough to keep it pinned.
     */
    @Test
    void aProgrammaticTransactionTemplateGovernsTheWriteToo() {
        String label = label();

        transactions.executeWithoutResult(status -> repository.save(new TestTxRecord(label)));

        assertEquals(1, committed(label));
    }

    @Test
    void aProgrammaticTransactionTemplateRollsBackTheWrite() {
        String label = label();

        assertThrows(IllegalStateException.class, () -> transactions.executeWithoutResult(status -> {
            repository.save(new TestTxRecord(label));
            throw new IllegalStateException("deliberate");
        }));

        assertEquals(0, committed(label), "the rollback must reach JavAI's write");
    }

    /** {@code saveAll} composes a batch into one unit of work of its own; inside a Spring transaction it must
     *  join that one instead, so the whole batch rolls back with it. */
    @Test
    void saveAllJoinsTheSpringTransactionRatherThanCommittingItsOwnBatch() {
        String label = label();

        assertThrows(IllegalStateException.class, () -> transactions.executeWithoutResult(status -> {
            repository.saveAll(List.of(new TestTxRecord(label), new TestTxRecord(label)));
            throw new IllegalStateException("deliberate");
        }));

        assertEquals(0, committed(label), "a batch that joined the caller's transaction rolls back with it");
    }

    /**
     * ⚠️ A genuine, permanent difference between the two topologies, asserted so it is a known property
     * rather than a surprise: {@code HibernateTransactionManager} cannot be used here at all.
     *
     * <p>Its {@code (SessionFactory)} constructor unwraps a {@code javax.sql.DataSource} so it can share the
     * connection with plain JDBC code. JavAI builds its factory from raw {@code jakarta.persistence.jdbc.*}
     * settings through Hibernate's own connection provider, so there is no {@code DataSource} to unwrap.
     * {@code JpaTransactionManager} is the supported manager for this topology, and the sibling suite covers
     * the Hibernate manager on the topology that can host it.
     */
    @Test
    void hibernateTransactionManagerIsNotAvailableOnAJavAIOwnedFactory() {
        SessionFactory factory = context.getBean(SessionFactory.class);

        assertThrows(RuntimeException.class,
                () -> new org.springframework.orm.jpa.hibernate.HibernateTransactionManager(factory),
                "no DataSource to unwrap -- use JpaTransactionManager on a JavAI-owned factory");
    }


    // ---- a rollback must take JavAI's own side writes with it ----------------------------------

    /**
     * The half of {@code @Transactional} that is JavAI's alone to get right.
     *
     * <p>A repository write is never one INSERT: alongside the entity go vector rows, side-table collection
     * members, geo points, and a summary-recomputation queue row. All of them are written on the caller's
     * connection precisely so they commit or roll back <em>with</em> the entity. A rollback that undid the
     * entity and left any of them behind would leave the store describing a row that does not exist -- and
     * would do it silently, since nothing reads those tables until a search does.
     *
     * <p>Only assertable on this topology: the sibling one maps a single plain entity, because a
     * Spring-built factory cannot map JavAI collection fields at all.
     */
    @Test
    void aRollbackTakesTheVectorAndSummaryQueueRowsWithIt() {
        TestShelf shelf = new TestShelf("rollback-shelf-" + UUID.randomUUID());
        shelf.getBooks().add(new TestBook("rollback-book-" + UUID.randomUUID()));

        assertThrows(IllegalStateException.class, () -> transactions.executeWithoutResult(status -> {
            shelves.save(shelf);
            throw new IllegalStateException("deliberate");
        }));

        assertEquals(0, rowsFor("javai_vectors__fake_test_model", shelf.getId()),
                "a rolled-back save must leave no field-vector rows");
        assertEquals(0, rowsFor("javai_summary_vectors__fake_test_model", shelf.getId()),
                "...nor an entity-grain row");
        assertEquals(0, rowsFor("javai_summary_pending", shelf.getId()),
                "...nor a queued recomputation for a mutation that never happened");
    }

    /** The other two side tables, on the fixture that has them. */
    @Test
    void aRollbackTakesTheGeoPointAndCollectionMemberRowsWithIt() {
        TestVenue venue = new TestVenue("rollback-venue-" + UUID.randomUUID(),
                new org.springframework.data.geo.Point(1.5, 2.5),
                List.of(new TestReview("rollback-reviewer", 5)));

        assertThrows(IllegalStateException.class, () -> transactions.executeWithoutResult(status -> {
            venues.save(venue);
            throw new IllegalStateException("deliberate");
        }));

        assertEquals(0, rowsFor("javai_geo_points", venue.getId()), "no orphaned geo point");
        assertEquals(0, rowsFor("javai_collection_members", venue.getId()), "no orphaned collection members");
    }

    /** ...and the committing counterpart, so the two above cannot pass by never writing anything at all. */
    @Test
    void aCommitKeepsTheSideWrites() {
        TestVenue venue = new TestVenue("commit-venue-" + UUID.randomUUID(),
                new org.springframework.data.geo.Point(3.5, 4.5),
                List.of(new TestReview("commit-reviewer", 4)));

        transactions.executeWithoutResult(status -> venues.save(venue));

        assertEquals(1, rowsFor("javai_geo_points", venue.getId()));
        assertEquals(1, rowsFor("javai_collection_members", venue.getId()));
    }

    // ---- cost: OMI-275's standing acceptance criterion ------------------------------------------

    /**
     * Joining a transaction must not cost extra embeddings.
     *
     * <p>The risk is specific rather than theoretical: inside a transaction a save runs against a session
     * that already holds entities, so a hydration or transfer step that behaves differently there would
     * re-embed content it already had -- silently, since an embedding failure is not what this would look
     * like. It would look like a slower boot.
     */
    @Test
    void writingInsideATransactionEmbedsExactlyWhatWritingOutsideOneDoes() {
        RecordingEmbeddingProvider recorder = new RecordingEmbeddingProvider(new FakeEmbeddingProvider());
        JavAIRuntime.configureEmbeddingProvider(recorder);
        try {
            String inside = "tx-cost-inside-" + UUID.randomUUID();
            transactions.executeWithoutResult(status -> shelves.save(new TestShelf(inside)));

            recorder.ledger().assertEmbeddedExactlyOnce(inside);
        } finally {
            JavAIRuntime.configureEmbeddingProvider(new FakeEmbeddingProvider());
        }
    }

    /** A re-save of unchanged content inside a transaction embeds nothing, exactly as it does outside one. */
    @Test
    void resavingUnchangedInsideATransactionEmbedsNothing() {
        TestShelf shelf = new TestShelf("tx-resave-" + UUID.randomUUID());
        shelves.save(shelf);

        RecordingEmbeddingProvider recorder = new RecordingEmbeddingProvider(new FakeEmbeddingProvider());
        JavAIRuntime.configureEmbeddingProvider(recorder);
        try {
            transactions.executeWithoutResult(status ->
                    shelves.save(shelves.findById(shelf.getId()).orElseThrow()));

            recorder.ledger().assertEmbeddedExactlyOnce();
        } finally {
            JavAIRuntime.configureEmbeddingProvider(new FakeEmbeddingProvider());
        }
    }

    /** Rows in a JavAI side table owned by {@code ownerId}, read over its own connection. */
    private static int rowsFor(String table, UUID ownerId) {
        try (java.sql.Connection connection = java.sql.DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                java.sql.PreparedStatement statement = connection.prepareStatement(
                        "SELECT count(*) FROM " + table + " WHERE owner_id = ?")) {
            statement.setObject(1, ownerId);
            try (java.sql.ResultSet rows = statement.executeQuery()) {
                rows.next();
                return rows.getInt(1);
            }
        } catch (Exception e) {
            throw new IllegalStateException("could not count " + table + " rows for " + ownerId, e);
        }
    }

    @Configuration
    @EnableTransactionManagement
    static class JavAIOwnedConfig {

        @Bean
        JavAIPersistenceConfig javAIConfig() {
            return JavAIPersistenceConfig.builder()
                    .backend(JavAIPersistenceConfig.Backend.POSTGRES)
                    .postgresUrl(POSTGRES.getJdbcUrl())
                    .postgresUsername(POSTGRES.getUsername())
                    .postgresPassword(POSTGRES.getPassword())
                    // Carried over from omiai-platform, so the topology under test is the one that actually
                    // runs rather than a simplified cousin: without it Spring's HibernateJpaDialect refuses
                    // @Transactional(isolation = ...) outright, which one inherited case exercises.
                    .hibernateProperty("hibernate.connection.handling_mode", "DELAYED_ACQUISITION_AND_HOLD")
                    .build();
        }

        @Bean
        SpringTxRecordRepository repository(JavAIPersistenceConfig config) {
            return JavAIPI.repository(SpringTxRecordRepository.class, config);
        }

        /** Takes the repository purely for ordering: realizing it registers the entity, and Hibernate's
         *  metadata freezes the moment this factory is built. A consumer gets the same effect declaratively
         *  from {@code entityPackages(...)}, which this package cannot use -- it holds fixtures that are
         *  deliberately invalid. */
        @Bean
        TestShelfRepository shelves(JavAIPersistenceConfig config) {
            return JavAIPI.repository(TestShelfRepository.class, config);
        }

        @Bean
        TestBookRepository books(JavAIPersistenceConfig config) {
            return JavAIPI.repository(TestBookRepository.class, config);
        }

        @Bean
        TestVenueRepository venues(JavAIPersistenceConfig config) {
            return JavAIPI.repository(TestVenueRepository.class, config);
        }

        @Bean
        SessionFactory javAISessionFactory(JavAIPersistenceConfig config, SpringTxRecordRepository repository,
                TestShelfRepository shelves, TestBookRepository books, TestVenueRepository venues) {
            return JavAIPI.sessionFactory(config);
        }

        @Bean
        PlatformTransactionManager transactionManager(SessionFactory javAISessionFactory) {
            JpaTransactionManager manager = new JpaTransactionManager(javAISessionFactory);
            // Required for @Transactional(isolation = ...) to be honoured rather than refused; pairs with
            // the connection handling mode on the config above.
            manager.setJpaDialect(new HibernateJpaDialect());
            // Matches the sibling topology's manager, so PROPAGATION_NESTED is refused by the same mechanism
            // in both and the inherited assertion compares like with like. Without it Spring refuses one step
            // earlier ("specify 'nestedTransactionAllowed'"), which would have made the two topologies look
            // different for a reason that is purely this configuration's.
            //
            // ⚠️ No setDataSource counterpart here, and there cannot be one: JavAI builds its factory from
            // raw jakarta.persistence.jdbc.* settings through Hibernate's own connection provider, so this
            // topology has no DataSource bean to hand over. Both managers still refuse nested transactions --
            // HibernateJpaDialect is no SavepointManager -- so the observable behaviour agrees anyway.
            manager.setNestedTransactionAllowed(true);
            return manager;
        }

        @Bean
        TransactionTemplate transactionTemplate(PlatformTransactionManager transactionManager) {
            return new TransactionTemplate(transactionManager);
        }

        @Bean
        InnerService innerService(SpringTxRecordRepository repository) {
            return new InnerService(repository);
        }

        @Bean
        OuterService outerService(SpringTxRecordRepository repository, InnerService inner,
                SessionFactory javAISessionFactory, JavAIPersistenceConfig config) {
            return new OuterService(repository, inner,
                    javAISessionFactory.unwrap(SessionFactoryImplementor.class), config);
        }

        @Bean
        ClassLevelService classLevelService(SpringTxRecordRepository repository) {
            return new ClassLevelService(repository);
        }
    }

    private static UUID unused() {
        return UUID.randomUUID();
    }
}
