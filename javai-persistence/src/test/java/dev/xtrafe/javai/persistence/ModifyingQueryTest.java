package dev.xtrafe.javai.persistence;

import dev.xtrafe.javai.model.JavAIRuntime;
import dev.xtrafe.javai.vector.EmbeddingVector;
import dev.xtrafe.javai.vector.testsupport.FakeEmbeddingProvider;
import org.junit.jupiter.api.BeforeAll;
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
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * OMI-398 / OMI-406: targeted writes, and the refusals that keep one from breaking Vector Core's mutation rule.
 *
 * <p>Two things are being proved together here, and they pull in opposite directions. A bulk update must
 * actually work -- it is the only way to express "set this column and touch nothing else" or an atomic
 * {@code SET c = c + 1}, and it must keep working on an entity that happens to carry an embedding. And it must
 * be refused whenever the column it assigns is one JavAI derives something from, because nothing would tell
 * Vector Core the value moved. A guard that only did the first would be the durable inconsistency
 * {@code SPEC.md} warns about; a guard that only did the second would have blocked the feature.
 */
@Testcontainers
class ModifyingQueryTest {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    private static JavAIPersistenceConfig config;
    private static TestCounterRowRepository counters;
    private static TestVectorizedCounterRepository vectorized;
    private static TestVersionedCounterDeclaredRepository versioned;
    private static TestBookDeclaredRepository books;
    private static TestShelfRepository shelves;

    private String label;

    @BeforeAll
    static void configure() {
        JavAIRuntime.configureEmbeddingProvider(new FakeEmbeddingProvider());
        config = JavAIPersistenceConfig.builder()
                .backend(JavAIPersistenceConfig.Backend.POSTGRES)
                .postgresUrl(postgres.getJdbcUrl())
                .postgresUsername(postgres.getUsername())
                .postgresPassword(postgres.getPassword())
                // Named here because the only thing that mentions them is a @Query's text, and a query is not
                // a registration -- see the refusal in DeclaredQueryTest for what happens when a type is
                // reachable no other way.
                .entityType(TestTaggregateOwner.class)
                .entityType(TestImageAsset.class)
                .build();
        counters = JavAIPI.repository(TestCounterRowRepository.class, config);
        vectorized = JavAIPI.repository(TestVectorizedCounterRepository.class, config);
        versioned = JavAIPI.repository(TestVersionedCounterDeclaredRepository.class, config);
        books = JavAIPI.repository(TestBookDeclaredRepository.class, config);
        shelves = JavAIPI.repository(TestShelfRepository.class, config);
    }

    @BeforeEach
    void freshLabel() {
        JavAIRuntime.configureEmbeddingProvider(new FakeEmbeddingProvider());
        label = "M" + UUID.randomUUID().toString().substring(0, 8);
    }

    private static Connection connect() throws Exception {
        return DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }

    private static long readTally(UUID id) throws Exception {
        try (Connection connection = connect();
                PreparedStatement statement = connection.prepareStatement(
                        "select tally from test_counter_row where id = ?")) {
            statement.setObject(1, id);
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                return rows.getLong(1);
            }
        }
    }

    private static int countVectorRows(String table, UUID id, String fieldName) throws Exception {
        try (Connection connection = connect();
                PreparedStatement statement = connection.prepareStatement(
                        "select count(*) from " + table + " where owner_id = ? and field_name = ?")) {
            statement.setObject(1, id);
            statement.setString(2, fieldName);
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                return rows.getInt(1);
            }
        }
    }

    // ---- the writes themselves ---------------------------------------------------------------------

    @Test
    void performsAnAtomicReadModifyWrite() throws Exception {
        TestCounterRow row = counters.save(new TestCounterRow(label, 0, 5));

        assertEquals(1, counters.incrementTally(row.getId()), "one row affected");
        assertEquals(6, readTally(row.getId()));
    }

    /**
     * The reason {@code SET c = c + 1} has to be expressible at all: read-modify-write through {@code save()}
     * loses increments under concurrency, and no amount of care at the call site fixes that.
     */
    @Test
    void concurrentIncrementsAllLand() throws Exception {
        TestCounterRow row = counters.save(new TestCounterRow(label, 0, 0));
        int writers = 8;
        int perWriter = 12;

        ExecutorService pool = Executors.newFixedThreadPool(writers);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger failures = new AtomicInteger();
        try {
            for (int i = 0; i < writers; i++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        for (int n = 0; n < perWriter; n++) {
                            counters.incrementTally(row.getId());
                        }
                    } catch (Exception e) {
                        failures.incrementAndGet();
                    }
                });
            }
            start.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS), "writers must finish");
        } finally {
            pool.shutdownNow();
        }

        assertEquals(0, failures.get(), "no writer may fail");
        assertEquals((long) writers * perWriter, readTally(row.getId()),
                "every increment must survive -- this is the guarantee a load-modify-save cannot give");
    }

    @Test
    void adaptsIntLongAndVoidReturns() throws Exception {
        TestCounterRow row = counters.save(new TestCounterRow(label, 0, 0));

        assertEquals(1, counters.incrementTally(row.getId()));
        assertEquals(1L, counters.setTallyForLabel(label, 40));
        counters.bumpVotes(row.getId()); // void: no return to assert, must simply not throw
        assertEquals(40, readTally(row.getId()));
        assertEquals(1, counters.findById(row.getId()).orElseThrow().getVotes());
    }

    @Test
    void runsANativeWriteOnAnEntityJavAIKeepsNoStorageFor() throws Exception {
        TestCounterRow row = counters.save(new TestCounterRow(label, 0, 3));

        assertEquals(1, counters.nativeIncrementTally(row.getId()));
        assertEquals(4, readTally(row.getId()));
    }

    // ---- the column ordinary save() must not clobber (parent ticket, point 5) ----------------------

    /**
     * Measured rather than assumed, and the measurement is what made this need no new annotation: JPA's own
     * {@code updatable = false} already means "no ordinary entity update writes this", while a targeted query
     * writes it anyway. The ticket asked for a way to have both; it turns out to exist already.
     */
    @Test
    void anUpdatableFalseColumnSurvivesAnUnrelatedSaveAndIsStillWritableByATargetedQuery() throws Exception {
        TestCounterRow row = counters.save(new TestCounterRow(label, 0, 7));

        counters.incrementTally(row.getId());
        assertEquals(8, readTally(row.getId()));

        // The clobber this protects against: a detached row loaded before the count moved, edited elsewhere.
        TestCounterRow stale = counters.findById(row.getId()).orElseThrow();
        stale.setTally(0);
        stale.setLabel(label + "-edited");
        counters.save(stale);

        assertEquals(8, readTally(row.getId()),
                "an ordinary save must not carry a stale counter back over the value the counter's own path set");
        assertEquals(label + "-edited", counters.findById(row.getId()).orElseThrow().getLabel(),
                "while everything the save was actually for still lands");
    }

    @Test
    void theSameHoldsOnAVectorizedEntity() throws Exception {
        TestVectorizedCounter row = vectorized.save(new TestVectorizedCounter(label + " headline", 0, 2));

        vectorized.incrementProtectedTally(row.getId());

        assertEquals(3, vectorized.findById(row.getId()).orElseThrow().getProtectedTally());
    }

    /** The half a refusal must not break: an ordinary column on a vectorized entity, and the vector it does
     *  not touch. A summary is arithmetic over vectors, so a column no vector reads cannot move one. */
    @Test
    void anOrdinaryColumnOnAVectorizedEntityIsWritableAndLeavesTheVectorAlone() {
        TestVectorizedCounter row = vectorized.save(new TestVectorizedCounter(label + " headline", 0, 0));
        EmbeddingVector before = vectorized.findById(row.getId()).orElseThrow().fieldVector("headline");

        assertEquals(1, vectorized.incrementTally(row.getId()));

        TestVectorizedCounter reloaded = vectorized.findById(row.getId()).orElseThrow();
        assertEquals(1, reloaded.getTally());
        assertArrayEquals(before.values(), reloaded.fieldVector("headline").values(),
                "the stored vector must be exactly what it was -- untouched, not recomputed");
    }

    // ---- flush / clear / transactions --------------------------------------------------------------

    @Test
    void clearAutomaticallyDetachesSoALaterReadSeesTheWrite() throws Exception {
        TestCounterRow row = counters.save(new TestCounterRow(label, 0, 0));

        JavAIPI.inTransaction(config, () -> {
            TestCounterRow loaded = counters.findById(row.getId()).orElseThrow();
            assertEquals(0, loaded.getTally());
            counters.incrementTallyAndClear(row.getId());
            assertEquals(1, counters.findById(row.getId()).orElseThrow().getTally(),
                    "after clearing, the next read comes from the database rather than the stale first-level cache");
            return null;
        });

        assertEquals(1, readTally(row.getId()));
    }

    @Test
    void flushAutomaticallyMakesPendingChangesVisibleToTheStatement() throws Exception {
        TestCounterRow row = counters.save(new TestCounterRow(label, 0, 0));

        JavAIPI.inTransaction(config, () -> {
            counters.incrementTallyForLabelAfterFlush(label);
            return null;
        });

        assertEquals(1, readTally(row.getId()));
    }

    /** A targeted write is an ordinary participant in the caller's unit of work, not a side channel. */
    @Test
    void rollsBackWithTheCallersTransaction() throws Exception {
        TestCounterRow row = counters.save(new TestCounterRow(label, 0, 0));

        assertThrows(IllegalStateException.class, () -> JavAIPI.inTransaction(config, () -> {
            counters.incrementTally(row.getId());
            throw new IllegalStateException("deliberate");
        }));

        assertEquals(0, readTally(row.getId()),
                "the increment must roll back with everything else the failed transaction did");
    }

    // ---- @Version ----------------------------------------------------------------------------------

    @Test
    void aBulkUpdateLeavesTheVersionAloneUnlessTheQuerySaysVersioned() {
        TestVersionedCounter plain = versioned.save(new TestVersionedCounter(0));
        long versionBefore = versioned.findById(plain.getId()).orElseThrow().getVersion();

        versioned.bump(plain.getId());
        assertEquals(versionBefore, versioned.findById(plain.getId()).orElseThrow().getVersion(),
                "an ordinary bulk update does not increment @Version -- stated rather than discovered later");

        versioned.bumpVersioned(plain.getId());
        assertEquals(versionBefore + 1, versioned.findById(plain.getId()).orElseThrow().getVersion(),
                "'update versioned' is the spelling that does");
    }

    // ---- delete ------------------------------------------------------------------------------------

    /**
     * The three things a bulk {@code delete} statement would each get wrong, asserted at once because they
     * co-occur in the shape a real application has: a vectorized entity held in a container.
     */
    @Test
    void aDeclaredDeleteDetachesFromContainersAndTakesItsVectorRowsWithIt() throws Exception {
        TestBook book = new TestBook(label + " title");
        TestShelf shelf = new TestShelf(label + " shelf");
        shelf.getBooks().add(book);
        TestShelf savedShelf = shelves.save(shelf);
        TestBook savedBook = savedShelf.getBooks().get(0);
        UUID bookId = savedBook.getId();
        String table = "javai_vectors__" + ModelIds.sanitize(savedBook.fieldVector("title").modelId());
        assertEquals(1, countVectorRows(table, bookId, "title"), "the book must start with a vector row");

        long deleted = books.deleteByTitleDeclared(label + " title");

        assertEquals(1, deleted);
        assertTrue(books.findById(bookId).isEmpty(), "the row is gone");
        assertEquals(0, countVectorRows(table, bookId, "title"),
                "and so is its vector row -- a stale one would keep matching similarity searches for an "
                        + "entity that no longer exists");
        assertNotNull(shelves.findById(savedShelf.getId()).orElseThrow(),
                "the container survives; only the membership was removed");
    }

    @Test
    void aDeclaredDeleteWithNoWhereClauseResolvesEveryId() {
        shelves.save(newShelfHolding(label + " a"));
        shelves.save(newShelfHolding(label + " b"));

        long deleted = books.deleteEveryBook();

        assertTrue(deleted >= 2, "a whole-table delete must resolve every id, not none: " + deleted);
        assertTrue(books.findAll().isEmpty());
    }

    private static TestShelf newShelfHolding(String bookTitle) {
        TestShelf shelf = new TestShelf(bookTitle + " shelf");
        shelf.getBooks().add(new TestBook(bookTitle));
        return shelf;
    }

    @Test
    void aDeclaredDeleteReturnsHowManyRowsItRemoved() {
        counters.save(new TestCounterRow(label, 1, 0));
        counters.save(new TestCounterRow(label, 2, 0));
        counters.save(new TestCounterRow(label + "-keep", 3, 0));

        assertEquals(2, counters.deleteByLabelDeclared(label));
        assertEquals(0, counters.byLabel(label).size());
        assertEquals(1, counters.byLabel(label + "-keep").size());
    }

    @Test
    void aDeclaredDeleteOnAVectorizedEntityClearsItsVectorRow() throws Exception {
        TestVectorizedCounter row = vectorized.save(new TestVectorizedCounter(label + " headline", 0, 0));
        String table = "javai_vectors__" + ModelIds.sanitize(row.fieldVector("headline").modelId());
        assertEquals(1, countVectorRows(table, row.getId(), "headline"));

        assertEquals(1, vectorized.deleteByHeadline(label + " headline"));

        assertEquals(0, countVectorRows(table, row.getId(), "headline"));
    }

    // ---- refusals: writes that would break the mutation rule ---------------------------------------

    private String refusalMessage(Class<? extends JavAIRepository<?>> repositoryInterface) {
        return assertThrows(IllegalArgumentException.class,
                () -> JavAIPI.repository(repositoryInterface, config)).getMessage();
    }

    @Test
    void refusesAssigningToAVectorizeField() {
        String message = refusalMessage(BogusDeclaredQueryRepositories.UpdatesAVectorizeField.class);

        assertTrue(message.contains("headline"), message);
        assertTrue(message.contains("@Vectorize"), message);
        assertTrue(message.contains("outlives this process"),
                "the message must say why this is worse than an ordinary staleness: " + message);
    }

    /**
     * The check reads the statement's own target, not the repository's type parameter -- otherwise declaring
     * the same update on a plain entity's repository would be a way around it.
     */
    @Test
    void refusesAVectorizedTargetEvenFromAPlainEntitysRepository() {
        String message = refusalMessage(
                BogusDeclaredQueryRepositories.PlainRepositoryUpdatingAVectorizedEntity.class);

        assertTrue(message.contains("TestVectorizedCounter.headline"),
                "resolved from the statement, not from the repository: " + message);
    }

    @Test
    void refusesAssigningToASummaryField() {
        String message = refusalMessage(BogusDeclaredQueryRepositories.UpdatesASummaryField.class);

        assertTrue(message.contains("@Summary"), message);
        assertTrue(message.contains("no recomputation queued"), message);
    }

    @Test
    void refusesAssigningToATaggregateField() {
        String message = refusalMessage(BogusDeclaredQueryRepositories.UpdatesATaggregateField.class);

        assertTrue(message.contains("@Taggregate"), message);
    }

    @Test
    void refusesAssigningToAnExternalVectorsKeyField() {
        String message = refusalMessage(BogusDeclaredQueryRepositories.UpdatesAnExternalVectorKeyField.class);

        assertTrue(message.contains("@ExternalVector"), message);
        assertTrue(message.contains("pixels"), "naming which one: " + message);
    }

    @Test
    void refusesANativeWriteOnAVectorizedEntity() {
        String message = refusalMessage(BogusDeclaredQueryRepositories.NativeModifyingOnAVectorizedEntity.class);

        assertTrue(message.contains("vectors"), message);
        assertTrue(message.contains("JPQL"), "and points at the form that can be checked: " + message);
    }

    @Test
    void refusesANativeWriteOnAnEntityWithGeoStorage() {
        String message = refusalMessage(BogusDeclaredQueryRepositories.NativeModifyingOnAGeoEntity.class);

        assertTrue(message.contains("geo points"), message);
    }

    /** All of the above are refused when the repository is realized, never on the call -- so a write that
     *  would corrupt derived state cannot reach production behind an untaken branch. */
    @Test
    void everyRefusalHappensAtRepositoryCreationTime() {
        for (Class<? extends JavAIRepository<?>> bogus : List.of(
                BogusDeclaredQueryRepositories.UpdatesAVectorizeField.class,
                BogusDeclaredQueryRepositories.UpdatesASummaryField.class,
                BogusDeclaredQueryRepositories.UpdatesATaggregateField.class,
                BogusDeclaredQueryRepositories.UpdatesAnExternalVectorKeyField.class,
                BogusDeclaredQueryRepositories.NativeModifyingOnAVectorizedEntity.class,
                BogusDeclaredQueryRepositories.NativeModifyingOnAGeoEntity.class)) {
            assertThrows(IllegalArgumentException.class, () -> JavAIPI.repository(bogus, config),
                    bogus.getSimpleName() + " must be refused before it can be called");
        }
    }
}
