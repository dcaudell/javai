package dev.xtrafe.javai.persistence;

import dev.xtrafe.javai.model.JavAIRuntime;
import org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy;
import org.hibernate.boot.model.naming.PhysicalNamingStrategyStandardImpl;
import org.hibernate.cfg.AvailableSettings;
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
import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * OMI-145: which physical naming the {@code SessionFactory} this module builds actually applies, and how a
 * consumer overrides it. Asserts the generated <em>schema shape</em> against a real Postgres rather than
 * configuration state, since the configuration only matters insofar as it changes the DDL.
 *
 * <p>Each test builds its own {@link JavAIPersistenceConfig}: {@code JavAIPI} caches backends by config
 * <em>reference identity</em>, so a distinct config instance means a distinct {@code SessionFactory} with
 * its own naming strategy. Entity types are shared between tests only where the naming they expect is
 * identical -- a type mapped under two different strategies would fight over one table in this database.
 *
 * <p>The fixtures are deliberately top-level classes, not nested ones: Hibernate derives the implicit table
 * name from the binary class name, so a nested entity produces {@code outer_class$inner_class} (measured,
 * not assumed) and buries the naming behavior under test in noise.
 *
 * <p>Postgres-only by nature -- the Neo4j and MongoDB backends classify fields by declared type and have no
 * equivalent of JPA column naming, so there is nothing there for this to configure.
 */
@Testcontainers
class PhysicalNamingStrategyTest {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    @BeforeEach
    void configureProvider() {
        JavAIRuntime.configureEmbeddingProvider(new FakeEmbeddingProvider());
    }

    /** The OMI-145 default: {@code emailVerified} maps to {@code email_verified}, matching Spring Boot's own
     *  default and ordinary SQL convention, rather than Hibernate's bare-default {@code emailverified}. */
    @Test
    void defaultStrategyMapsCamelCaseToSnakeCase() {
        JavAIPersistenceConfig config = baseConfig().build();
        JavAIPI.repository(CamelRepository.class, config).save(new TestNamingCamel("dom@example.com"));

        Set<String> columns = columnsOf("test_naming_camel");
        assertTrue(columns.contains("email_verified"), "expected snake_case columns, got " + columns);
        assertTrue(columns.contains("google_subject_id"), "expected snake_case columns, got " + columns);
        assertTrue(columns.contains("created_at"), "expected snake_case columns, got " + columns);
        assertFalse(columns.contains("emailverified"),
                "the pre-0.1.5 all-lowercase column must not also be present -- a table carrying both "
                        + "conventions at once is the defect OMI-145 fixes");
    }

    /** The documented way for a schema created before 0.1.5 to keep its column names instead of migrating. */
    @Test
    void explicitStrategyPinsHibernatesBareDefault() {
        JavAIPersistenceConfig config = baseConfig()
                .physicalNamingStrategy(new PhysicalNamingStrategyStandardImpl())
                .build();
        JavAIPI.repository(LegacyRepository.class, config).save(new TestNamingLegacy("dom@example.com"));

        Set<String> columns = columnsOf("testnaminglegacy");
        assertTrue(columns.contains("emailverified"),
                "pinning PhysicalNamingStrategyStandardImpl must reproduce the pre-0.1.5 naming, got " + columns);
        assertFalse(columns.contains("email_verified"), "the new default must not leak through the override");
    }

    /** The general passthrough reaches settings this builder exposes no typed method for -- proven with the
     *  naming key itself, whose effect is directly visible in the DDL. */
    @Test
    void hibernatePropertyPassthroughCanSetTheNamingStrategy() {
        JavAIPersistenceConfig config = baseConfig()
                .hibernateProperty(AvailableSettings.PHYSICAL_NAMING_STRATEGY,
                        PhysicalNamingStrategyStandardImpl.class.getName())
                .build();
        JavAIPI.repository(LegacyRepository.class, config).save(new TestNamingLegacy("dom@example.com"));

        Set<String> columns = columnsOf("testnaminglegacy");
        assertTrue(columns.contains("emailverified"),
                "a raw hibernate.physical_naming_strategy property must be honored, got " + columns);
    }

    /** Precedence, as documented on {@code JavAIPersistenceConfig.Builder#physicalNamingStrategy}: the typed
     *  setter is the more specific instruction, so it beats the same key passed as a raw property. */
    @Test
    void explicitStrategyWinsOverTheSameKeyPassedAsAProperty() {
        JavAIPersistenceConfig config = baseConfig()
                .hibernateProperty(AvailableSettings.PHYSICAL_NAMING_STRATEGY,
                        PhysicalNamingStrategyStandardImpl.class.getName())
                .physicalNamingStrategy(new CamelCaseToUnderscoresNamingStrategy())
                .build();
        JavAIPI.repository(CamelRepository.class, config).save(new TestNamingCamel("dom@example.com"));

        Set<String> columns = columnsOf("test_naming_camel");
        assertTrue(columns.contains("email_verified"),
                "the typed setter must beat the raw property for the same key, got " + columns);
        assertFalse(columns.contains("emailverified"), "the losing property must have no effect at all");
    }

    /**
     * The passthrough is applied <em>after</em> this module's own settings, so it can override them --
     * deliberate, and proven here against {@code hibernate.hbm2ddl.auto}, the setting this module owns whose
     * effect is unmistakable: with schema export off, the entity's table is never created and the write
     * fails, rather than succeeding against a table JavAI created regardless of what the caller asked for.
     */
    @Test
    void passthroughOverridesThisModulesOwnSettings() {
        JavAIPersistenceConfig config = baseConfig()
                .hibernateProperty("hibernate.hbm2ddl.auto", "none")
                .build();
        NoDdlRepository repository = JavAIPI.repository(NoDdlRepository.class, config);

        assertThrows(RuntimeException.class, () -> repository.save(new TestNamingNoDdl("dom@example.com")),
                "with hbm2ddl.auto=none there is no table to write to");
        assertTrue(columnsOf("test_naming_no_ddl").isEmpty(), "no table should have been created");
    }

    private JavAIPersistenceConfig.Builder baseConfig() {
        return JavAIPersistenceConfig.builder()
                .backend(JavAIPersistenceConfig.Backend.POSTGRES)
                .postgresUrl(postgres.getJdbcUrl())
                .postgresUsername(postgres.getUsername())
                .postgresPassword(postgres.getPassword());
    }

    /** Every column of {@code table}, or an empty set if the table doesn't exist at all. */
    private static Set<String> columnsOf(String table) {
        Set<String> columns = new LinkedHashSet<>();
        try (Connection connection = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT column_name FROM information_schema.columns "
                                + "WHERE table_schema = current_schema() AND table_name = ?")) {
            statement.setString(1, table);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    columns.add(resultSet.getString(1));
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("could not read the columns of " + table, e);
        }
        return columns;
    }

    interface CamelRepository extends JavAIRepository<TestNamingCamel> {
    }

    interface LegacyRepository extends JavAIRepository<TestNamingLegacy> {
    }

    interface NoDdlRepository extends JavAIRepository<TestNamingNoDdl> {
    }
}
