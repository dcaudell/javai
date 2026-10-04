package dev.xtrafe.javai.tagging;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import dev.xtrafe.javai.model.JavAIRuntime;
import dev.xtrafe.javai.persistence.JavAIPI;
import dev.xtrafe.javai.persistence.JavAIPersistenceConfig;
import dev.xtrafe.javai.vector.testsupport.FakeEmbeddingProvider;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * OMI-410, as it reaches tagging: one {@code JavAIPI.release(config)} must also close the connection
 * {@link JavAITagRepository}'s backend opened, and tagging must run on a supplied {@code DataSource} too.
 * Connections are counted in {@code pg_stat_activity} by an {@code application_name} unique per test.
 */
@Testcontainers
class TaggingConnectionLifecycleTest {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    @BeforeAll
    static void configureProvider() {
        JavAIRuntime.configureEmbeddingProvider(new FakeEmbeddingProvider());
    }

    @Test
    void releaseClosesTaggingsConnectionWithPersistencesAndTheSameConfigRebuilds() throws Exception {
        String appName = "tagging-url-" + System.nanoTime();
        JavAIPersistenceConfig config = JavAIPersistenceConfig.builder()
                .postgresUrl(postgres.getJdbcUrl() + "&ApplicationName=" + appName)
                .postgresUsername(postgres.getUsername())
                .postgresPassword(postgres.getPassword())
                .build();
        Tagged before = tagSomething(config, "before");
        assertTrue(connections(appName) > 0);

        JavAIPI.release(config);

        awaitConnections(appName, 0);
        IllegalStateException stale = assertThrows(IllegalStateException.class,
                () -> before.tagging().tagsOf(before.thing()));
        assertTrue(stale.getMessage().contains("released"), stale.getMessage());
        tagSomething(config, "after");
        JavAIPI.release(config);
        awaitConnections(appName, 0);
    }

    @Test
    void taggingRunsOnASuppliedDataSource() throws Exception {
        String appName = "tagging-pool-" + System.nanoTime();
        HikariConfig hikari = new HikariConfig();
        hikari.setJdbcUrl(postgres.getJdbcUrl());
        hikari.setUsername(postgres.getUsername());
        hikari.setPassword(postgres.getPassword());
        hikari.setMinimumIdle(0);
        hikari.setMaximumPoolSize(4);
        hikari.addDataSourceProperty("ApplicationName", appName);
        try (HikariDataSource pool = new HikariDataSource(hikari)) {
            JavAIPersistenceConfig config = JavAIPersistenceConfig.builder().dataSource(pool).build();

            tagSomething(config, "pooled");

            assertEquals(connections(appName), pool.getHikariPoolMXBean().getTotalConnections(),
                    "every connection JavAI and tagging hold must be one of the pool's");
            JavAIPI.release(config);
            assertEquals(0, pool.getHikariPoolMXBean().getActiveConnections());
        }
    }

    /**
     * OMI-614: a pool that hands out connections with autocommit off (Hibernate's own pool default, which an
     * application keeps for Postgres large objects) must not leave tagging's own connection holding an open
     * transaction: its schema DDL and its writes outside a caller's transaction commit as they run.
     */
    @Test
    void taggingCommitsOnAPoolWithAutocommitOff() throws Exception {
        String appName = "tagging-manual-" + System.nanoTime();
        HikariConfig hikari = new HikariConfig();
        hikari.setJdbcUrl(postgres.getJdbcUrl());
        hikari.setUsername(postgres.getUsername());
        hikari.setPassword(postgres.getPassword());
        hikari.setMinimumIdle(0);
        hikari.setMaximumPoolSize(4);
        hikari.setAutoCommit(false);
        hikari.addDataSourceProperty("ApplicationName", appName);
        try (HikariDataSource pool = new HikariDataSource(hikari)) {
            JavAIPersistenceConfig config = JavAIPersistenceConfig.builder().dataSource(pool).build();

            Tagged tagged = tagSomething(config, "manual");

            assertEquals(0, connections(appName, "idle in transaction"),
                    "no connection of the pool may be left holding an open transaction");
            assertEquals(1, committedTaggings(tagged.thing()), "the tag is committed, visible to anyone");
            JavAIPI.release(config);
        }
    }

    private static int committedTaggings(TestThing thing) throws SQLException {
        try (Connection probe = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             PreparedStatement count = probe.prepareStatement(
                     "SET lock_timeout = '2s'; SELECT count(*) FROM taggings WHERE taggable_id = ?")) {
            count.setObject(1, thing.getId());
            count.execute();
            count.getMoreResults();
            try (ResultSet rows = count.getResultSet()) {
                rows.next();
                return rows.getInt(1);
            }
        }
    }

    private static int connections(String appName, String state) throws SQLException {
        try (Connection probe = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             PreparedStatement count = probe.prepareStatement(
                     "SELECT count(*) FROM pg_stat_activity WHERE application_name = ? AND state = ?")) {
            count.setString(1, appName);
            count.setString(2, state);
            try (ResultSet rows = count.executeQuery()) {
                rows.next();
                return rows.getInt(1);
            }
        }
    }

    private record Tagged(JavAITagRepository tagging, TestThing thing) {
    }

    private static Tagged tagSomething(JavAIPersistenceConfig config, String label) {
        TagRepository tags = JavAIPI.repository(TagRepository.class, config);
        TagSetRepository tagSets = JavAIPI.repository(TagSetRepository.class, config);
        TestThingRepository things = JavAIPI.repository(TestThingRepository.class, config);
        JavAITagRepository tagging = new JavAITagRepository(tags, config);
        TagSet tagSet = tagSets.save(new TagSet("lifecycle-" + label + "-" + System.nanoTime()));
        Tag tag = tags.save(new Tag(tagSet, "en", "Lifecycle " + label));
        TestThing thing = things.save(new TestThing(label));
        tagging.addTag(thing, tag);
        assertEquals(1, tagging.tagsOf(thing).size());
        return new Tagged(tagging, thing);
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
