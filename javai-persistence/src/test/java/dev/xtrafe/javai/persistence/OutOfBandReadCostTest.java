package dev.xtrafe.javai.persistence;

import dev.xtrafe.javai.model.JavAIRuntime;
import dev.xtrafe.javai.vector.testsupport.FakeEmbeddingProvider;
import dev.xtrafe.javai.vector.testsupport.RecordingEmbeddingProvider;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.data.geo.Point;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * What a load costs in out-of-band reads (OMI-275's standing criterion, applied to OMI-276).
 *
 * <p>OMI-276 restored a correctness property -- a {@code Point} reached through an association is read
 * again -- and the criterion says a correctness fix must not be bought with round trips. The number to beat
 * was not the walk this replaced but what the post-load listener already cost: two statements per entity for
 * vectors, plus <b>one per {@code Point} field</b>, plus a JDBC metadata call per table per entity.
 *
 * <h2>Why two Point fields</h2>
 *
 * A single {@code Point} cannot distinguish "one read per entity" from "one read per field" -- both are one
 * statement. {@link TestTwoPointSite} has two, so the old shape costs two geo scans per entity and the
 * consolidated one costs one. That is the whole design of this fixture.
 *
 * <h2>Why it is tagged, and excluded by default</h2>
 *
 * Roughly 45 seconds for three assertions, none of it spent doing work: Postgres flushes a backend's
 * statistics on its own schedule and {@code pg_stat_force_next_flush()} reaches only the sampling
 * connection's own, so every measurement has to wait for the counters to settle on either side. That is
 * worth paying when the read path changes and worth nothing on every other PR, so it carries its own
 * {@code cost} tag rather than joining {@code performance} -- these assertions are exact numbers, not
 * comparative timings. Run it with
 * {@code mvn -pl javai-persistence test -Djavai.excludedTestGroups=performance,requires-model}.
 */
@Tag("cost")
@Testcontainers
class OutOfBandReadCostTest {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    private static TestTwoPointSiteRepository sites;
    private static TestShelfRepository shelves;

    /** Written once, before anything is measured. Keeping every write out of the sampled window is what
     *  makes the numbers below mean "what this read cost" rather than "what this read and whatever the
     *  setup was still flushing cost" -- Postgres flushes a backend's statistics on its own schedule, and
     *  {@code pg_stat_force_next_flush()} can only flush the sampling connection's own. */
    private static TestTwoPointSite site;
    private static List<TestShelf> shelves3;

    @BeforeAll
    static void configure() {
        JavAIRuntime.configureEmbeddingProvider(new FakeEmbeddingProvider());
        JavAIPersistenceConfig config = JavAIPersistenceConfig.builder()
                .backend(JavAIPersistenceConfig.Backend.POSTGRES)
                .postgresUrl(postgres.getJdbcUrl())
                .postgresUsername(postgres.getUsername())
                .postgresPassword(postgres.getPassword())
                .build();
        sites = JavAIPI.repository(TestTwoPointSiteRepository.class, config);
        shelves = JavAIPI.repository(TestShelfRepository.class, config);
        JavAIPI.repository(TestBookRepository.class, config);

        site = new TestTwoPointSite("cost-site-" + UUID.randomUUID(),
                new Point(1.5, 2.5), new Point(3.5, 4.5));
        sites.save(site);
        shelves3 = List.of(
                new TestShelf("cost-shelf-a-" + UUID.randomUUID()),
                new TestShelf("cost-shelf-b-" + UUID.randomUUID()),
                new TestShelf("cost-shelf-c-" + UUID.randomUUID()));
        shelves3.forEach(shelves::save);
    }

    /** Both fields restored, from one read -- correctness and cost in one assertion pair. */
    @Test
    void bothPointFieldsAreRestoredByASingleGeoRead() throws Exception {
        long before = geoScans();
        TestTwoPointSite loaded = sites.findById(site.getId()).orElseThrow();
        long after = geoScans();

        assertNotNull(loaded.getEntrance(), "the first Point must be restored");
        assertNotNull(loaded.getExit(), "...and so must the second");
        assertEquals(1, after - before,
                "two Point fields on one entity must cost one read, not one per field");
    }

    /**
     * The load of a vectorized entity issues one out-of-band statement, not one per stored kind.
     *
     * <p>Measured as scans across the three side tables: a UNION over the branches that apply touches each
     * of them once. What this pins is that the count tracks the number of <em>entities</em>, so a future
     * change that goes back to a statement per kind, or per field, moves it.
     */
    @Test
    void loadingNEntitiesCostsOneOutOfBandReadEach() throws Exception {
        List<TestShelf> saved = shelves3;
        // A discarded read first. The setup's writes and their post-commit summary drains flush on
        // Postgres's own schedule, and measured directly they land inside the window and are charged to the
        // reads under test -- 13 scans on the first sample, 1 on each of the next two, when this was
        // instrumented. Reading once and throwing it away moves that residue in front of the window.
        saved.forEach(shelf -> shelves.findById(shelf.getId()).orElseThrow());

        long before = scansOn("javai_vectors__fake_test_model");
        for (TestShelf shelf : saved) {
            shelves.findById(shelf.getId()).orElseThrow();
        }
        long after = scansOn("javai_vectors__fake_test_model");

        assertEquals(saved.size(), after - before,
                "one field-vector read per entity loaded, however many @Vectorize fields it has");
    }

    /** And the vectors it reads are the stored ones, so none of the above is achieved by reading less. */
    @Test
    void aReloadedEntityStillCostsNoEmbeddings() {
        TestShelf shelf = new TestShelf("cost-embed-" + UUID.randomUUID());
        shelves.save(shelf);

        RecordingEmbeddingProvider recorder = new RecordingEmbeddingProvider(new FakeEmbeddingProvider());
        JavAIRuntime.configureEmbeddingProvider(recorder);
        try {
            shelves.save(shelves.findById(shelf.getId()).orElseThrow());
            recorder.ledger().assertEmbeddedExactlyOnce();
        } finally {
            JavAIRuntime.configureEmbeddingProvider(new FakeEmbeddingProvider());
        }
    }

    private static long geoScans() throws Exception {
        return scansOn("javai_geo_points");
    }

    /**
     * {@code seq_scan + idx_scan} for one table -- the metric OMI-271 was reported in -- sampled only once
     * the counter has stopped moving.
     *
     * <p>The settle loop is not defensiveness, it is required for the number to mean anything.
     * {@code pg_stat_force_next_flush()} flushes only the calling backend's own statistics, and everything
     * being measured here happens on JavAI's connections, which flush on their own ~500ms schedule. Sampling
     * immediately attributes the setup's writes -- and any post-commit summary drain still in flight -- to
     * the read under test, which is exactly the confusion that made the first run of this test report 14
     * where it should have reported 3.
     */
    private static long scansOn(String table) throws Exception {
        long previous = -1;
        for (int attempt = 0; attempt < 8; attempt++) {
            Thread.sleep(1500);
            long current = readScans(table);
            if (current == previous) {
                return current;
            }
            previous = current;
        }
        throw new IllegalStateException("scan counters for " + table + " never settled");
    }

    private static long readScans(String table) throws Exception {
        try (Connection connection = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
                Statement statement = connection.createStatement()) {
            statement.execute("SELECT pg_stat_force_next_flush()");
            try (ResultSet rows = statement.executeQuery(
                    "SELECT coalesce(seq_scan, 0) + coalesce(idx_scan, 0) FROM pg_stat_user_tables"
                            + " WHERE relname = '" + table + "'")) {
                return rows.next() ? rows.getLong(1) : 0L;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("could not read scan counters for " + table, e);
        }
    }
}
