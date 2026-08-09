package dev.xtrafe.javai.persistence;

import dev.xtrafe.javai.model.JavAIRuntime;
import dev.xtrafe.javai.vector.testsupport.FakeEmbeddingProvider;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.persistenceunit.PersistenceManagedTypes;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.sql.Connection;
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
class SpringTransactionalIntegrationTest extends SpringTransactionalConformance {

    private static AnnotationConfigApplicationContext context;
    private static OuterService outer;
    private static ClassLevelService classLevel;

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
        return context.getBean(SpringTxRecordRepository.class);
    }

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

    // ---- topology-specific: needs a DataSource bean, which only exists when Spring owns the factory ----

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

    // ---- wiring -------------------------------------------------------------------------------

    /** JPA wiring, as a Spring Boot application would have it: LCEMFB + JpaTransactionManager. */
    @Configuration
    @EnableTransactionManagement
    static class JpaConfig {

        @Bean
        DataSource dataSource() {
            DriverManagerDataSource dataSource = new DriverManagerDataSource();
            dataSource.setUrl(POSTGRES.getJdbcUrl());
            dataSource.setUsername(POSTGRES.getUsername());
            dataSource.setPassword(POSTGRES.getPassword());
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
                EntityManagerFactory entityManagerFactory, JavAIPersistenceConfig config) {
            // The NATIVE factory, not the proxy unwrap(SessionFactory.class) yields: that is the identity
            // the backend normalizes to, and the one a Spring-managed session reports as its own.
            return new OuterService(repository, inner,
                    entityManagerFactory.unwrap(org.hibernate.engine.spi.SessionFactoryImplementor.class),
                    config);
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
                .postgresUrl(POSTGRES.getJdbcUrl())
                .postgresUsername(POSTGRES.getUsername())
                .postgresPassword(POSTGRES.getPassword())
                .sessionFactory(sessionFactory)
                .build();
    }
}
