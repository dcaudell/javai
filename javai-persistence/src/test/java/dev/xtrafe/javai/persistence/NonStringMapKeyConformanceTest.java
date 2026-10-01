package dev.xtrafe.javai.persistence;

import dev.xtrafe.javai.model.JavAIRuntime;
import dev.xtrafe.javai.vector.testsupport.FakeEmbeddingProvider;
import dev.xtrafe.javai.vector.testsupport.RecordingEmbeddingProvider;
import org.hibernate.Hibernate;
import org.hibernate.stat.Statistics;
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
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A {@code JavAIMap} keyed by something other than {@code String}, on Postgres (OMI-279).
 *
 * <h2>Why this cell needed measuring rather than assuming</h2>
 *
 * Postgres <em>refused</em> a non-{@code String} map key until OMI-277, and the refusal was right: the key
 * lived in the membership table's {@code varchar} column, so any other type could only be stored stringified
 * and could never round-trip back as itself. OMI-277 deleted that table, the validator went with it, and the
 * key became Hibernate's business.
 *
 * <p>That left the cell in the worst available state -- <b>no longer refused, and never exercised</b>. "We
 * stopped rejecting it" is not the same claim as "it works", and shipping the first while a reader hears the
 * second is exactly the failure mode {@code OMI-275}/{@code OMI-279} exist to prevent. Documenting it as
 * unmeasured was honest but temporary; this makes it a fact in one direction or the other.
 *
 * <h2>What is actually checked</h2>
 *
 * The old validator named a specific harm, so the tests answer that harm directly rather than settling for a
 * round trip: the key must come back <b>as its own type</b>, and the column it lives in must have that type
 * in the database. A stringified key that happened to parse back would pass a naive round-trip assertion and
 * fail both of these.
 *
 * <p>Three key types across the two JPA key-mapping mechanisms -- see {@link TestKeyedCatalogue}. Plus the
 * standing OMI-275 criterion: no over-fetch, and no vector recomputed for a key that is not a vectorizable
 * thing in the first place.
 *
 * <h2>The answer</h2>
 *
 * <b>Supported.</b> Keys round-trip as {@code Integer}/{@code UUID}/enum, in {@code integer}/{@code uuid}/
 * {@code varchar} columns respectively, with JavAI's own {@code PersistentJavAIMap} substituted in and the
 * association lazy like any other. {@code String} remains the portable choice only because Neo4j and MongoDB
 * still refuse everything else, for the reason Postgres no longer has.
 */
@Testcontainers
class NonStringMapKeyConformanceTest {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    private static JavAIPersistenceConfig config;
    private static TestKeyedCatalogueRepository catalogues;
    private static Statistics statistics;

    @BeforeAll
    static void configure() {
        JavAIRuntime.configureEmbeddingProvider(new FakeEmbeddingProvider());
        config = JavAIPersistenceConfig.builder()
                .backend(JavAIPersistenceConfig.Backend.POSTGRES)
                .postgresUrl(postgres.getJdbcUrl())
                .postgresUsername(postgres.getUsername())
                .postgresPassword(postgres.getPassword())
                .build();
        // Realizing the repository at all is the first assertion: registration validates every field of
        // every reachable type, and until OMI-277 this fixture could not get past it.
        catalogues = JavAIPI.repository(TestKeyedCatalogueRepository.class, config);
        JavAIPI.repository(TestBookRepository.class, config);
        statistics = JavAIPI.sessionFactory(config).getStatistics();
        statistics.setStatisticsEnabled(true);
    }

    /**
     * The load-bearing test. Each key is looked up with an instance of its <em>own</em> type, which a
     * stringified key could not answer -- {@code Map.get} would miss on {@code "3"} vs {@code 3}.
     */
    @Test
    void everyKeyTypeRoundTripsAsItsOwnType() {
        UUID edition = UUID.randomUUID();
        UUID id = saved(edition).getId();

        JavAIPI.inTransaction(config, () -> {
            TestKeyedCatalogue loaded = catalogues.findById(id).orElseThrow();

            assertEquals("ranked-book", loaded.getByRank().get(3).getTitle(),
                    "an Integer key must answer an Integer lookup");
            assertEquals("edition-book", loaded.getByEdition().get(edition).getTitle(),
                    "a UUID key must answer a UUID lookup -- the type the old refusal named by example");
            assertEquals("graded-book", loaded.getByGrade().get(TestKeyedCatalogue.Grade.GOLD).getTitle(),
                    "an enum key must answer an enum lookup");
            return null;
        });
    }

    /**
     * The same claim from the other side: not merely that lookup works, but that what came back <em>is</em>
     * the declared type. A key stringified on write and parsed on read would satisfy the test above while
     * failing this one, so the pair separates "round-trips" from "round-trips as itself".
     */
    @Test
    void theKeysThemselvesComeBackAsTheDeclaredTypeNotAsStrings() {
        UUID edition = UUID.randomUUID();
        UUID id = saved(edition).getId();

        JavAIPI.inTransaction(config, () -> {
            TestKeyedCatalogue loaded = catalogues.findById(id).orElseThrow();

            assertInstanceOf(Integer.class, loaded.getByRank().keySet().iterator().next());
            assertInstanceOf(UUID.class, loaded.getByEdition().keySet().iterator().next());
            assertInstanceOf(TestKeyedCatalogue.Grade.class, loaded.getByGrade().keySet().iterator().next());
            return null;
        });
    }

    /**
     * And from the database's side, which is the one a stringifying implementation could not fake. The old
     * storage's key column was a {@code varchar} whatever the declared type; these are the column types JPA
     * asks for.
     */
    @Test
    void theKeyColumnHasTheKeysOwnTypeInTheDatabase() throws Exception {
        saved(UUID.randomUUID());

        assertEquals("integer", keyColumnType("test_keyed_by_rank", "rank_key"));
        assertEquals("uuid", keyColumnType("test_keyed_by_edition", "edition_key"));
        // An enum mapped @MapKeyEnumerated(STRING) is legitimately character data -- the assertion is that
        // it is character data *because the mapping asked for it*, not because everything ends up that way.
        assertTrue(keyColumnType("test_keyed_by_grade", "grade_key").contains("character"),
                "an enum key mapped STRING is character data by request");
    }

    /** The JavAI half: a non-{@code String} key must not cost the field its JavAI collection behaviour. */
    @Test
    void javAIsOwnPersistentMapIsStillSubstitutedForANonStringKey() {
        UUID id = saved(UUID.randomUUID()).getId();

        JavAIPI.inTransaction(config, () -> {
            TestKeyedCatalogue loaded = catalogues.findById(id).orElseThrow();
            Hibernate.initialize(loaded.getByRank());

            assertInstanceOf(PersistentJavAIMap.class, loaded.getByRank());
            assertTrue(loaded.getByRank().centroid().dims() > 0,
                    "the JavAI behaviour must work on the instance Hibernate substituted");
            return null;
        });
    }

    /** Ordinary association laziness, unaffected by what the key is. */
    @Test
    void aNonStringKeyedMapIsLazyLikeAnyOtherAssociation() {
        UUID id = saved(UUID.randomUUID()).getId();

        TestKeyedCatalogue detached = catalogues.findById(id).orElseThrow();

        assertFalse(Hibernate.isInitialized(detached.getByRank()), "an Integer-keyed map is lazy");
        assertFalse(Hibernate.isInitialized(detached.getByEdition()), "a UUID-keyed map is lazy");
        assertFalse(Hibernate.isInitialized(detached.getByGrade()), "an enum-keyed map is lazy");
    }

    /**
     * OMI-275's standing criterion, on the two costs a map key could plausibly move: reading the root must
     * still load one entity, and nothing about a key may reach the embedding provider. A key is not a
     * vectorizable thing, and a non-{@code String} one going through a conversion step is exactly where a
     * stray {@code embed(key.toString())} would hide.
     */
    @Test
    void readingTheRootCostsOneEntityAndNoEmbeddings() {
        UUID id = saved(UUID.randomUUID()).getId();

        RecordingEmbeddingProvider recorder = new RecordingEmbeddingProvider(new FakeEmbeddingProvider());
        JavAIRuntime.configureEmbeddingProvider(recorder);
        statistics.clear();
        try {
            TestKeyedCatalogue loaded = catalogues.findById(id).orElseThrow();
            assertNotNull(loaded.getName());
        } finally {
            JavAIRuntime.configureEmbeddingProvider(new FakeEmbeddingProvider());
        }

        assertEquals(1, statistics.getEntityLoadCount(),
                "reading the root must not pull three maps' worth of books in behind it");
        assertEquals(0, recorder.ledger().totalCalls(),
                "a map key is not a vectorizable thing -- reading one must embed nothing");
    }

    private static TestKeyedCatalogue saved(UUID edition) {
        TestKeyedCatalogue catalogue = new TestKeyedCatalogue("keyed-" + UUID.randomUUID());
        catalogue.getByRank().put(3, new TestBook("ranked-book"));
        catalogue.getByEdition().put(edition, new TestBook("edition-book"));
        catalogue.getByGrade().put(TestKeyedCatalogue.Grade.GOLD, new TestBook("graded-book"));
        return catalogues.save(catalogue);
    }

    /** The declared SQL type of a join table's key column, straight from the catalogue. */
    private static String keyColumnType(String table, String column) throws Exception {
        try (Connection connection = DriverManager.getConnection(
                        postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT data_type FROM information_schema.columns "
                                + "WHERE table_name = ? AND column_name = ?")) {
            statement.setString(1, table);
            statement.setString(2, column);
            try (ResultSet resultSet = statement.executeQuery()) {
                assertTrue(resultSet.next(), "no column " + table + "." + column + " -- mapping changed?");
                return resultSet.getString(1);
            }
        }
    }
}
