package dev.xtrafe.javai.persistence;

import dev.xtrafe.javai.model.JavAIRuntime;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.event.service.spi.EventListenerRegistry;
import org.hibernate.event.spi.EventType;
import org.hibernate.event.spi.PreInsertEvent;
import org.hibernate.event.spi.PreInsertEventListener;
import org.hibernate.event.spi.PreUpdateEvent;
import org.hibernate.event.spi.PreUpdateEventListener;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * PHASE 0 SPIKE (OMI-142) — three go/no-go gates for Option B. Prints observations and asserts the specific
 * claims the plan depends on. Not a shipped test: this exists to retire risk before Phases 1-3 are built.
 *
 * <p>Gate 1 (proving a JavAI-aware {@code PersistentBag} subclass round-trips through a real
 * {@code @OneToMany}) has since been superseded by the shipped implementation and its own stronger test,
 * {@code RepositoryBackendHibernatePostgresTest#javaiInterfaceTypedCollectionBecomesANativeHibernateAssociation},
 * which additionally proves the collection type is attached with no consumer annotation at all.
 * <br>Gate 2: JavAI can register Hibernate event listeners on a SessionFactory it did <em>not</em> build.
 * <br>Gate 3: whether a {@code PreUpdate} listener actually fires when {@code reindexAll()} re-saves a
 * relationally-unchanged entity -- if it doesn't, listener-driven vector writes would silently break reindex.
 */
@Testcontainers
class PhaseZeroSpikeTest {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    @BeforeAll
    static void configure() {
        JavAIRuntime.configureEmbeddingProvider(new FakeEmbeddingProvider());
    }

    // ---- Gate 2 -------------------------------------------------------------------------------

    @Test
    void gate2_eventListenersCanBeRegisteredOnAnExternallySuppliedSessionFactory() {
        System.out.println("=== GATE 2: event-listener registration on a foreign SessionFactory ===");
        List<String> observed = new CopyOnWriteArrayList<>();

        // A SessionFactory JavAI did NOT build -- the shared-factory case omiai-platform relies on.
        StandardServiceRegistry registry = new StandardServiceRegistryBuilder()
                .applySetting("jakarta.persistence.jdbc.url", postgres.getJdbcUrl())
                .applySetting("jakarta.persistence.jdbc.user", postgres.getUsername())
                .applySetting("jakarta.persistence.jdbc.password", postgres.getPassword())
                .applySetting("hibernate.hbm2ddl.auto", "update")
                .build();
        try (SessionFactory external = new MetadataSources(registry)
                .addAnnotatedClass(TestMember.class)
                .addAnnotatedClass(TestProfile.class)
                .buildMetadata()
                .buildSessionFactory()) {

            EventListenerRegistry listeners = ((SessionFactoryImplementor) external)
                    .getServiceRegistry().getService(EventListenerRegistry.class);
            org.junit.jupiter.api.Assertions.assertNotNull(listeners,
                    "EventListenerRegistry must be reachable from a SessionFactory JavAI didn't build");

            listeners.appendListeners(EventType.PRE_INSERT, (PreInsertEventListener) event -> {
                observed.add("PRE_INSERT:" + event.getEntity().getClass().getSimpleName());
                return false;
            });
            listeners.appendListeners(EventType.PRE_UPDATE, (PreUpdateEventListener) event -> {
                observed.add("PRE_UPDATE:" + event.getEntity().getClass().getSimpleName());
                return false;
            });

            TestMember member = new TestMember("listener-probe");
            EntityReflection.writeId(member, java.util.UUID.randomUUID());
            try (Session session = external.openSession()) {
                var tx = session.beginTransaction();
                session.persist(member);
                tx.commit();
            }
            System.out.println("GATE2 observed after insert=" + observed);
            org.junit.jupiter.api.Assertions.assertTrue(
                    observed.stream().anyMatch(e -> e.startsWith("PRE_INSERT")),
                    "a PreInsert listener registered post-hoc must actually fire");
            System.out.println("GATE2 RESULT: PASS");
        }
    }

    // ---- Gate 3 -------------------------------------------------------------------------------

    /**
     * The hazard: {@code reindexAll()} is a {@code findAll()} + {@code save()} loop. If vector writes moved
     * onto {@code PreUpdate}, and Hibernate skips the UPDATE for an entity whose mapped columns are unchanged,
     * no {@code PreUpdate} fires and reindex silently becomes a no-op. This measures whether that happens.
     */
    @Test
    void gate3_doesPreUpdateFireWhenReSavingARelationallyUnchangedEntity() {
        System.out.println("=== GATE 3: PreUpdate on re-save of an unchanged entity ===");
        List<String> observed = new CopyOnWriteArrayList<>();

        StandardServiceRegistry registry = new StandardServiceRegistryBuilder()
                .applySetting("jakarta.persistence.jdbc.url", postgres.getJdbcUrl())
                .applySetting("jakarta.persistence.jdbc.user", postgres.getUsername())
                .applySetting("jakarta.persistence.jdbc.password", postgres.getPassword())
                .applySetting("hibernate.hbm2ddl.auto", "update")
                .build();
        try (SessionFactory factory = new MetadataSources(registry)
                .addAnnotatedClass(TestMember.class)
                .addAnnotatedClass(TestProfile.class)
                .buildMetadata()
                .buildSessionFactory()) {

            EventListenerRegistry listeners = ((SessionFactoryImplementor) factory)
                    .getServiceRegistry().getService(EventListenerRegistry.class);
            listeners.appendListeners(EventType.PRE_UPDATE, (PreUpdateEventListener) event -> {
                observed.add("PRE_UPDATE");
                return false;
            });

            TestMember member = new TestMember("reindex-probe-" + Instant.now().toEpochMilli());
            EntityReflection.writeId(member, java.util.UUID.randomUUID());
            try (Session session = factory.openSession()) {
                var tx = session.beginTransaction();
                session.persist(member);
                tx.commit();
            }
            observed.clear();

            // Simulate reindexAll(): load it, merge it back UNCHANGED, flush.
            try (Session session = factory.openSession()) {
                var tx = session.beginTransaction();
                TestMember loaded = session.find(TestMember.class, member.getId());
                session.merge(loaded);
                session.flush();
                tx.commit();
            }
            System.out.println("GATE3 PreUpdate events on unchanged re-save = " + observed.size());
            System.out.println("GATE3 VERDICT: " + (observed.isEmpty()
                    ? "PreUpdate DID NOT fire -> listener-only vector writes WOULD BREAK reindexAll()"
                    : "PreUpdate fired -> listener-driven reindex is viable"));

            // Pins the assumption Phase 1's design depends on. Hibernate skips the UPDATE entirely when no
            // mapped column changed, so no PreUpdate fires -- which means reindexAll() must keep an explicit
            // vector-write path and can never rely on flush events alone. If a future Hibernate changes this,
            // this assertion fails and the design decision gets revisited deliberately rather than silently.
            org.junit.jupiter.api.Assertions.assertTrue(observed.isEmpty(),
                    "expected no PreUpdate on a relationally-unchanged re-save; if this now fires, revisit "
                            + "whether reindexAll() can be driven by flush events after all");
        }
    }
}
