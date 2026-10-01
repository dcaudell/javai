package dev.xtrafe.javai.persistence;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import dev.xtrafe.javai.model.JavAIRuntime;
import dev.xtrafe.javai.vector.testsupport.FakeEmbeddingProvider;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * OMI-410: an application can hand JavAI its own pool, and can release what JavAI opened. Connections are
 * counted in {@code pg_stat_activity} by {@code application_name}, unique per test, so each assertion sees
 * exactly the connections it is about.
 */
@Testcontainers
class ConnectionLifecycleTest {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    @BeforeAll
    static void configureProvider() {
        JavAIRuntime.configureEmbeddingProvider(new FakeEmbeddingProvider());
    }

    @Test
    void aSuppliedDataSourceCarriesJavAIsQueries() throws SQLException {
        String appName = uniqueName("pool");
        try (HikariDataSource pool = pool(appName)) {
            JavAIPersistenceConfig config = JavAIPersistenceConfig.builder().dataSource(pool).build();
            TestArticleRepository articles = JavAIPI.repository(TestArticleRepository.class, config);
            assertEquals(0, pool.getHikariPoolMXBean().getTotalConnections(), "nothing has run yet");

            articles.save(new TestArticle("pooled", "body"));

            assertEquals(1, articles.findAll().size());
            assertTrue(pool.getHikariPoolMXBean().getTotalConnections() > 0,
                    "JavAI's queries must show up on the application's own pool");
            assertTrue(connections(appName) > 0);
            JavAIPI.release(config);
        }
    }

    @Test
    void releaseReturnsConnectionsToTheApplicationsPoolAndLeavesItOpen() throws Exception {
        try (HikariDataSource pool = pool(uniqueName("pool"))) {
            JavAIPersistenceConfig config = JavAIPersistenceConfig.builder().dataSource(pool).build();
            JavAIPI.repository(TestArticleRepository.class, config).findAll();

            JavAIPI.release(config);

            assertFalse(pool.isClosed(), "the application owns its pool; JavAI must never close it");
            assertEquals(0, pool.getHikariPoolMXBean().getActiveConnections());
            assertDoesNotThrow(() -> JavAIPI.repository(TestArticleRepository.class, config).findAll());
            JavAIPI.release(config);
        }
    }

    /** The URL path is unchanged -- Hibernate's built-in pool -- and release closes that pool outright. */
    @Test
    void releaseClosesAUrlBuiltPoolAndTheNextCallRebuildsRatherThanReturningADeadOne() throws Exception {
        String appName = uniqueName("url");
        JavAIPersistenceConfig config = urlConfig(appName);
        TestArticleRepository before = JavAIPI.repository(TestArticleRepository.class, config);
        before.save(new TestArticle("before release", "body"));
        assertTrue(connections(appName) > 0);

        JavAIPI.release(config);

        awaitConnections(appName, 0);
        IllegalStateException stale = assertThrows(IllegalStateException.class, before::findAll);
        assertTrue(stale.getMessage().contains("released"), stale.getMessage());

        TestArticleRepository after = JavAIPI.repository(TestArticleRepository.class, config);
        assertFalse(after.findAll().isEmpty(), "the same config must rebuild a working backend");
        assertTrue(connections(appName) > 0);
        JavAIPI.release(config);
        awaitConnections(appName, 0);
    }

    @Test
    void releasingAConfigNothingWasBuiltForOpensNothing() throws Exception {
        String appName = uniqueName("idle");
        JavAIPersistenceConfig config = urlConfig(appName);
        JavAIPI.repository(TestArticleRepository.class, config); // realized, never called

        assertDoesNotThrow(() -> JavAIPI.release(config));
        assertDoesNotThrow(() -> JavAIPI.release(urlConfig(uniqueName("never-seen"))));
        assertEquals(0, connections(appName));
    }

    @Test
    void aDataSourceExcludesAUrlAndAnyNonPostgresBackend() {
        try (HikariDataSource pool = pool(uniqueName("pool"))) {
            assertThrows(IllegalStateException.class, () -> JavAIPersistenceConfig.builder()
                    .dataSource(pool).postgresUrl(postgres.getJdbcUrl()).build());
            assertThrows(IllegalStateException.class, () -> JavAIPersistenceConfig.builder()
                    .backend(JavAIPersistenceConfig.Backend.NEO4J).dataSource(pool).build());
        }
    }

    private static JavAIPersistenceConfig urlConfig(String appName) {
        return JavAIPersistenceConfig.builder()
                .postgresUrl(postgres.getJdbcUrl())
                .postgresUsername(postgres.getUsername())
                .postgresPassword(postgres.getPassword())
                .hibernateProperty("hibernate.connection.ApplicationName", appName)
                .build();
    }

    private static HikariDataSource pool(String appName) {
        HikariConfig hikari = new HikariConfig();
        hikari.setJdbcUrl(postgres.getJdbcUrl());
        hikari.setUsername(postgres.getUsername());
        hikari.setPassword(postgres.getPassword());
        hikari.setMinimumIdle(0); // so a connection exists only because something used one
        hikari.setMaximumPoolSize(4);
        hikari.addDataSourceProperty("ApplicationName", appName);
        return new HikariDataSource(hikari);
    }

    private static String uniqueName(String prefix) {
        return prefix + "-" + System.nanoTime();
    }

    private static int connections(String appName) throws SQLException {
        try (Connection probe = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             PreparedStatement count = probe.prepareStatement(
                     "SELECT count(*) FROM pg_stat_activity WHERE application_name = ?")) {
            count.setString(1, appName);
            try (ResultSet rows = count.executeQuery()) {
                rows.next();
                return rows.getInt(1);
            }
        }
    }

    /** A closed client connection leaves {@code pg_stat_activity} as its server process exits, not instantly. */
    private static void awaitConnections(String appName, int expected) throws Exception {
        long deadline = System.nanoTime() + 5_000_000_000L;
        int seen = connections(appName);
        while (seen != expected && System.nanoTime() < deadline) {
            Thread.sleep(50);
            seen = connections(appName);
        }
        assertEquals(expected, seen, "connections named " + appName);
    }
}
