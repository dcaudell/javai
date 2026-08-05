package dev.xtrafe.javai.persistence;

import dev.xtrafe.javai.model.JavAIRuntime;
import dev.xtrafe.javai.vector.testsupport.FakeEmbeddingProvider;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * OMI-255: two writers beneath one {@code @Summary} container, and the write amplification that made them
 * collide on rows neither of them changed.
 *
 * <h2>What this fixes, stated as two separate defects</h2>
 *
 * <p><b>Write amplification.</b> {@code writeVectors} used to upsert every field row, the combined
 * {@code $vector} row and the summary row on every flush, whether or not the value had changed --
 * and {@code $vector} is recombined arithmetically on each save, so it always arrived carrying a fresh
 * {@code computed_at}. At {@code REPEATABLE READ} a value-identical UPDATE still creates a row version, so
 * two writers touching entirely different children refused each other on a row that was, semantically, not
 * theirs. This is what the ticket's evidence actually showed: the failing statement named
 * {@code javai_vectors__<model>} with {@code field_name = '$vector'}, which is the container's <em>own</em>
 * combined vector and does not depend on its {@code @Summary} children at all.
 *
 * <p><b>The shared summary row.</b> Separately and genuinely, a container's summary row <em>is</em> one row
 * per owner and it <em>does</em> change whenever any descendant changes. That contention is real, cannot be
 * annotated away, and cannot be fixed with a lock taken inside the writer's transaction: at
 * {@code REPEATABLE READ} the snapshot is taken at the first statement, long before the owner is known, so
 * waiting for a lock and then updating a row a concurrent transaction has since committed still raises
 * {@code could not serialize access}. It is fixed by taking the derived write out of the caller's
 * transaction entirely -- enqueue inside it, recompute from committed state after it.
 *
 * <p>⚠️ Every test here asserts <b>both</b> that nothing was refused <b>and</b> that the summary is current
 * afterwards. A fix that only silences the error passes the first and fails the second, which is the failure
 * mode worth catching: nothing crashes, and similarity search over containers quietly drifts.
 */
@Testcontainers
class SummaryPropagationConcurrencyTest {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    private static final String VECTOR_TABLE =
            "javai_vectors__" + ModelIds.sanitize(FakeEmbeddingProvider.MODEL_ID);
    private static final String SUMMARY_TABLE =
            "javai_summary_vectors__" + ModelIds.sanitize(FakeEmbeddingProvider.MODEL_ID);

    private static JavAIPersistenceConfig config;
    private static TestLibraryRepository libraries;
    private static TestShelfRepository shelves;
    private static TestArticleRepository articles;
    private static TestVaultRepository vaults;

    @BeforeAll
    static void configurePersistenceAndProvider() {
        JavAIRuntime.configureEmbeddingProvider(new FakeEmbeddingProvider());
        config = JavAIPersistenceConfig.builder()
                .backend(JavAIPersistenceConfig.Backend.POSTGRES)
                .postgresUrl(postgres.getJdbcUrl())
                .postgresUsername(postgres.getUsername())
                .postgresPassword(postgres.getPassword())
                // The production wiring this was reported against: REPEATABLE READ, with the connection held
                // for the transaction so the level can be applied at all.
                .hibernateProperty("hibernate.connection.handling_mode", "DELAYED_ACQUISITION_AND_HOLD")
                .hibernateProperty("hibernate.connection.isolation", "4")
                .build();
        libraries = JavAIPI.repository(TestLibraryRepository.class, config);
        shelves = JavAIPI.repository(TestShelfRepository.class, config);
        JavAIPI.repository(TestBookRepository.class, config);
        // Realized here rather than in the test that uses it: registration closes once the SessionFactory is
        // built, so a type introduced after the first save would be refused outright.
        articles = JavAIPI.repository(TestArticleRepository.class, config);
        vaults = JavAIPI.repository(TestVaultRepository.class, config);
    }

    @BeforeEach
    void resetProvider() {
        JavAIRuntime.configureEmbeddingProvider(new FakeEmbeddingProvider());
    }

    // ---- 1. write amplification -------------------------------------------------------------------

    /**
     * The direct cause of the reported failure, isolated single-threaded so it is unambiguous.
     *
     * <p>Asserts on {@code xmin} -- Postgres's own row-version column -- rather than on {@code computed_at},
     * deliberately: a row rewritten with identical content still gets a new {@code xmin}, and that new
     * version is precisely what a concurrent writer collides with. A {@code computed_at} assertion would
     * have missed the per-field rows entirely, since those carry the hydrated timestamp and so were being
     * rewritten with the value they already had.
     */
    @Test
    @DisplayName("re-saving an unchanged entity rewrites none of its vector rows")
    void resavingAnUnchangedEntityRewritesNoVectorRows() {
        TestShelf shelf = shelves.save(new TestShelf("unchanged"));
        Map<String, Long> before = vectorRowVersions(TestShelf.class.getName(), shelf.getId());
        assertTrue(before.containsKey("label") && before.containsKey("$vector"),
                "fixture must actually have written both a field row and a combined row: " + before);
        long summaryBefore = summaryRowVersion(TestShelf.class.getName(), shelf.getId());

        shelves.save(shelves.findById(shelf.getId()).orElseThrow());

        assertEquals(before, vectorRowVersions(TestShelf.class.getName(), shelf.getId()),
                "an entity whose vectors are unchanged must not rewrite its rows -- every rewrite is a row "
                        + "version a concurrent writer can collide with, for no semantic gain");
        assertEquals(summaryBefore, summaryRowVersion(TestShelf.class.getName(), shelf.getId()),
                "and the same holds for the entity-grain summary row");
    }

    /** The other half of the same rule, and the guard against "fixing" contention by simply not writing:
     *  a field that genuinely changed must still reach the database. */
    @Test
    @DisplayName("a genuinely changed vector is still written")
    void aGenuinelyChangedVectorIsStillWritten() {
        TestShelf shelf = shelves.save(new TestShelf("before"));
        Map<String, Long> before = vectorRowVersions(TestShelf.class.getName(), shelf.getId());

        TestShelf reloaded = shelves.findById(shelf.getId()).orElseThrow();
        reloaded.setLabel("after");
        shelves.save(reloaded);

        Map<String, Long> after = vectorRowVersions(TestShelf.class.getName(), shelf.getId());
        assertNotEquals(before.get("label"), after.get("label"),
                "the label genuinely changed, so its row must be rewritten");
        assertNotEquals(before.get("$vector"), after.get("$vector"),
                "and so must the combined row it feeds");
    }

    /** Adding a child changes the container's <em>summary</em> and nothing else about it -- its own combined
     *  vector is arithmetic over its own {@code @Vectorize} fields, which the child never touches. That
     *  distinction is the whole of why the reported failure was avoidable. */
    @Test
    @DisplayName("adding a child rewrites the container's summary row but not its own vector rows")
    void addingAChildRewritesOnlyTheSummaryRow() {
        TestShelf shelf = shelves.save(new TestShelf("owner"));
        Map<String, Long> before = vectorRowVersions(TestShelf.class.getName(), shelf.getId());
        Instant summaryBefore = summaryComputedAt(TestShelf.class.getName(), shelf.getId());

        TestShelf reloaded = shelves.findById(shelf.getId()).orElseThrow();
        reloaded.getBooks().add(new TestBook("a new arrival"));
        shelves.save(reloaded);

        assertEquals(before, vectorRowVersions(TestShelf.class.getName(), shelf.getId()),
                "the container's own vectors do not depend on its @Summary children, so adding one must "
                        + "not rewrite them -- this is the row two concurrent adders were colliding on");
        assertNotEquals(summaryBefore, summaryComputedAt(TestShelf.class.getName(), shelf.getId()),
                "the summary, by contrast, genuinely changed and must be rewritten");
        assertSummaryMatchesCommittedGraph(shelf.getId());
    }

    // ---- 2. the reported reproduction -------------------------------------------------------------

    /**
     * ⚠️ <b>The reproduction.</b> Two adds to one container, forced to overlap with a latch rather than a
     * sleep. Both must land, and the container's summary must reflect both afterwards.
     */
    @Test
    @DisplayName("two simultaneous adds beneath one @Summary container both land, and the summary is current")
    void twoConcurrentAddsBeneathOneContainerBothLand() throws Exception {
        TestShelf shelf = shelves.save(new TestShelf("contended"));
        UUID shelfId = shelf.getId();
        Instant summaryBefore = summaryComputedAt(TestShelf.class.getName(), shelfId);

        List<String> outcomes = concurrentAdds(shelfId, "concurrent-a", "concurrent-b");

        assertEquals(List.of("added", "added"), outcomes,
                "neither add may be refused -- two users adding two different things is not a conflict");
        assertEquals(2, membershipCount(shelfId), "both books are on the shelf");
        Instant summaryAfter = summaryComputedAt(TestShelf.class.getName(), shelfId);
        assertNotNull(summaryAfter, "the container must still have a summary");
        assertNotEquals(summaryBefore, summaryAfter,
                "⚠️ the summary must reflect what the container now holds -- a fix that only silences the "
                        + "error leaves this stale and similarity search drifts silently");
        assertSummaryMatchesCommittedGraph(shelfId);
    }

    // ---- 3. the ancestor no session loaded --------------------------------------------------------

    /**
     * The multi-pod case: a caller that reaches the shelf through its own repository holds no
     * {@link TestLibrary} at all, so an in-memory back-edge walk can never reach it. The library's summary
     * must still be recomputed, which is what forces the reverse mapping to come from the database.
     */
    @Test
    @DisplayName("a @Summary ancestor outside the saved graph is still recomputed")
    void aSummaryAncestorOutsideTheSavedGraphIsStillRecomputed() {
        TestShelf shelf = new TestShelf("nested");
        TestLibrary library = new TestLibrary("grandparent");
        library.getShelves().add(shelf);
        libraries.save(library);
        Instant libraryBefore = summaryComputedAt(TestLibrary.class.getName(), library.getId());
        assertNotNull(libraryBefore, "the library must have a summary to begin with");

        // Reached through the shelf's own repository: this caller never loads, mentions, or holds a library.
        TestShelf held = shelves.findById(shelf.getId()).orElseThrow();
        held.getBooks().add(new TestBook("two levels down"));
        shelves.save(held);

        assertNotEquals(libraryBefore, summaryComputedAt(TestLibrary.class.getName(), library.getId()),
                "the grandparent container's summary must be recomputed even though nothing in the saving "
                        + "session ever referenced it");
        assertSummaryMatchesCommittedGraph(shelf.getId());
    }

    /**
     * The reverse lookup names the {@code @Id} field rather than assuming it is called {@code id}. Every
     * other fixture here happens to call it {@code id}, so nothing else can catch this.
     */
    @Test
    @DisplayName("containment is resolved for a container whose @Id field is not named \"id\"")
    void containmentWorksForAContainerKeyedByAnUnconventionallyNamedField() {
        TestVault vault = vaults.save(new TestVault("oddly keyed"));
        Instant before = summaryComputedAt(TestVault.class.getName(), vault.getVaultKey());

        TestVault held = vaults.findById(vault.getVaultKey()).orElseThrow();
        held.getBooks().add(new TestBook("filed in a vault"));
        vaults.save(held);

        assertNotEquals(before, summaryComputedAt(TestVault.class.getName(), vault.getVaultKey()),
                "the container's summary must be recomputed whatever its key field is called");
    }

    // ---- 4. removal, which drifts the same way additions do ----------------------------------------

    /**
     * The mirror of the reported defect, and the one the ticket names in passing: a container that keeps
     * summarising something it no longer holds.
     *
     * <p>Deletion is the harder direction, because the evidence of who held the entity is destroyed by the
     * very operation that makes it stale -- once the join rows are gone there is nothing left to ask.
     */
    @Test
    @DisplayName("removing a child recomputes the container that held it")
    void removingAChildRecomputesItsContainer() {
        TestShelf shelf = shelves.save(new TestShelf("losing a book"));
        TestShelf held = shelves.findById(shelf.getId()).orElseThrow();
        held.getBooks().add(new TestBook("about to be removed"));
        held.getBooks().add(new TestBook("staying put"));
        shelves.save(held);
        Instant withBothBooks = summaryComputedAt(TestShelf.class.getName(), shelf.getId());

        TestShelf reloaded = shelves.findById(shelf.getId()).orElseThrow();
        reloaded.getBooks().removeIf(book -> book.getTitle().startsWith("about to be"));
        shelves.save(reloaded);

        assertEquals(1, membershipCount(shelf.getId()), "one book left");
        assertNotEquals(withBothBooks, summaryComputedAt(TestShelf.class.getName(), shelf.getId()),
                "the shelf must stop summarising a book it no longer holds -- nothing fails when it does "
                        + "not, the shelf simply keeps matching searches for content that is gone");
        assertSummaryMatchesCommittedGraph(shelf.getId());
    }

    /**
     * Deleting an entity that a container still holds.
     *
     * <p>This used to fail outright on a natively-mapped association -- Hibernate deletes the row and the
     * join row referencing it is left behind, so the database refuses:
     * {@code update or delete on table "test_book" violates foreign key constraint … on table
     * "test_shelf_test_book"}. JavAI already removed the equivalent rows for its own collection storage, so
     * whether {@code deleteById} worked depended on which of the two shapes the container happened to use.
     */
    @Test
    @DisplayName("deleting an entity a container still holds detaches it first, and recomputes the container")
    void deletingAnEntityStillHeldByAContainerSucceeds() {
        TestShelf shelf = shelves.save(new TestShelf("holding a doomed book"));
        TestShelf held = shelves.findById(shelf.getId()).orElseThrow();
        TestBook doomed = new TestBook("about to be deleted outright");
        held.getBooks().add(doomed);
        held.getBooks().add(new TestBook("surviving"));
        shelves.save(held);
        Instant withBothBooks = summaryComputedAt(TestShelf.class.getName(), shelf.getId());

        JavAIPI.repository(TestBookRepository.class, config).deleteById(doomed.getId());

        assertEquals(1, membershipCount(shelf.getId()),
                "the membership must go with the entity -- a join row pointing at a deleted row is exactly "
                        + "what the foreign key refuses");
        assertNotEquals(withBothBooks, summaryComputedAt(TestShelf.class.getName(), shelf.getId()),
                "and the container must stop summarising what it no longer holds");
        assertSummaryMatchesCommittedGraph(shelf.getId());
    }

    // ---- 5. the per-write opt-out ------------------------------------------------------------------

    /**
     * {@link SummaryPolicy#QUEUE_ONLY} trades currency for throughput, and this pins <b>both</b> halves of
     * that trade: the summary really is left behind, and the work really is not lost.
     */
    @Test
    @DisplayName("QUEUE_ONLY defers the recomputation, and a later drain still performs it")
    void queueOnlyDefersTheRecomputationWithoutLosingIt() {
        TestShelf shelf = shelves.save(new TestShelf("deferred"));
        Instant before = summaryComputedAt(TestShelf.class.getName(), shelf.getId());

        TestShelf held = shelves.findById(shelf.getId()).orElseThrow();
        held.getBooks().add(new TestBook("queued, not yet folded in"));
        shelves.save(held, SummaryPolicy.QUEUE_ONLY);

        assertEquals(before, summaryComputedAt(TestShelf.class.getName(), shelf.getId()),
                "QUEUE_ONLY must not recompute -- that is the entire point of asking for it");

        JavAIPI.drainPendingSummaries(config);

        assertNotEquals(before, summaryComputedAt(TestShelf.class.getName(), shelf.getId()),
                "and the queued work must survive to be done later, or QUEUE_ONLY would be data loss");
        assertSummaryMatchesCommittedGraph(shelf.getId());
    }

    /** The default is unchanged behaviour for every existing caller: {@code save(entity)} leaves nothing
     *  outstanding, so nobody who never heard of this ticket has to start draining anything. */
    @Test
    @DisplayName("a plain save() leaves nothing queued")
    void aPlainSaveLeavesNothingQueued() {
        TestShelf shelf = shelves.save(new TestShelf("default policy"));
        TestShelf held = shelves.findById(shelf.getId()).orElseThrow();
        held.getBooks().add(new TestBook("folded in before save returns"));
        shelves.save(held);

        assertEquals(0, pendingRowCount(), "the default policy settles its own work before returning");
        assertSummaryMatchesCommittedGraph(shelf.getId());
    }

    // ---- 6. entities that have nothing to do with @Summary ------------------------------------------

    /**
     * The constraint this had to satisfy: an entity type outside {@code @Summary} containment must be
     * completely unaffected -- same inline write, nothing queued, none of this machinery in its path.
     *
     * <p>{@link TestArticle} is vectorized but declares no {@code @Summary} field and is held in nobody
     * else's, which is the ordinary case for most of an application's entities.
     */
    @Test
    @DisplayName("an entity type in no @Summary relationship queues nothing and keeps its inline summary row")
    void anEntityTypeOutsideSummaryContainmentIsUntouched() {
        TestArticle article = articles.save(new TestArticle("outside containment", "body"));

        assertEquals(0, pendingRowCount(),
                "a type that neither contains nor is contained has nothing that could need recomputing");
        assertNotNull(summaryComputedAt(TestArticle.class.getName(), article.getId()),
                "and its entity-grain row is still written inline, exactly as before OMI-255");
    }

    /** Rows are cleared as they are drained, so a count of what is outstanding is meaningful. Tolerates the
     *  table not existing at all -- which is itself the correct state for a model with no {@code @Summary}
     *  anywhere, and must not read as a failure. */
    private static int pendingRowCount() {
        Integer count = query("SELECT CASE WHEN to_regclass('" + PendingSummaries.TABLE + "') IS NULL THEN 0"
                + " ELSE (SELECT count(*) FROM " + PendingSummaries.TABLE + ") END", null, null, rows -> {
            rows.next();
            return rows.getInt(1);
        });
        return count == null ? 0 : count;
    }

    // ---- helpers ----------------------------------------------------------------------------------

    /** Two threads, each adding one distinct book to {@code shelfId} inside its own transaction, overlap
     *  forced with a latch so neither can complete before the other has started. */
    private static List<String> concurrentAdds(UUID shelfId, String... titles) throws InterruptedException {
        List<String> outcomes = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch bothIn = new CountDownLatch(titles.length);
        ExecutorService threads = Executors.newFixedThreadPool(titles.length);
        try {
            for (String title : titles) {
                threads.submit(() -> {
                    try {
                        JavAIPI.inTransaction(config, () -> {
                            TestShelf held = shelves.findById(shelfId).orElseThrow();
                            held.getBooks().add(new TestBook(title));
                            bothIn.countDown();
                            try {
                                bothIn.await(30, TimeUnit.SECONDS);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                            shelves.save(held);
                            return null;
                        });
                        outcomes.add("added");
                    } catch (RuntimeException refused) {
                        // The root cause is reported, not just the wrapper type: "LockAcquisitionException"
                        // alone cannot distinguish a serialisation failure on a vector row from a deadlock
                        // on a join table, and those are different defects with different fixes.
                        outcomes.add("refused: " + refused.getClass().getSimpleName()
                                + " <- " + rootCauseOf(refused));
                    }
                });
            }
            threads.shutdown();
            assertTrue(threads.awaitTermination(120, TimeUnit.SECONDS), "both writers must finish");
        } finally {
            threads.shutdownNow();
        }
        return outcomes;
    }

    private static String rootCauseOf(Throwable thrown) {
        Throwable cause = thrown;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        return cause.getClass().getSimpleName() + ": "
                + (message == null ? "(no message)" : message.replace('\n', ' '));
    }

    /**
     * The assertion a "stop the error" fix cannot pass: the stored summary must equal what the formula
     * produces from the graph as committed, recomputed here independently of whatever the write path
     * believed at the time.
     */
    private static void assertSummaryMatchesCommittedGraph(UUID shelfId) {
        TestShelf reloaded = shelves.findById(shelfId).orElseThrow();
        float[] expected = reloaded.summaryVector().values();
        float[] stored = storedSummaryVector(TestShelf.class.getName(), shelfId);
        assertNotNull(stored, "the container must have a stored summary");
        assertEquals(expected.length, stored.length, "dimension mismatch between stored and recomputed summary");
        for (int i = 0; i < expected.length; i++) {
            assertEquals(expected[i], stored[i], 1e-5f,
                    "stored summary diverges from the committed graph at dimension " + i
                            + " -- the container is summarizing contents it no longer has, or is missing some");
        }
    }

    /** {@code xmin} per field name: Postgres's own row version, so "was this row rewritten" is observable
     *  directly rather than inferred from a timestamp the write path controls. */
    private static Map<String, Long> vectorRowVersions(String ownerType, UUID ownerId) {
        Map<String, Long> versions = new LinkedHashMap<>();
        query("SELECT field_name, xmin::text::bigint FROM " + VECTOR_TABLE
                + " WHERE owner_type = ? AND owner_id = ? ORDER BY field_name", ownerType, ownerId, rows -> {
            while (rows.next()) {
                versions.put(rows.getString(1), rows.getLong(2));
            }
            return null;
        });
        return versions;
    }

    private static long summaryRowVersion(String ownerType, UUID ownerId) {
        Long version = query("SELECT xmin::text::bigint FROM " + SUMMARY_TABLE
                + " WHERE owner_type = ? AND owner_id = ?", ownerType, ownerId,
                rows -> rows.next() ? rows.getLong(1) : -1L);
        return version == null ? -1L : version;
    }

    private static Instant summaryComputedAt(String ownerType, UUID ownerId) {
        return query("SELECT computed_at FROM " + SUMMARY_TABLE + " WHERE owner_type = ? AND owner_id = ?",
                ownerType, ownerId, rows -> rows.next() ? rows.getTimestamp(1).toInstant() : null);
    }

    private static float[] storedSummaryVector(String ownerType, UUID ownerId) {
        return query("SELECT vector::text FROM " + SUMMARY_TABLE + " WHERE owner_type = ? AND owner_id = ?",
                ownerType, ownerId, rows -> {
                    if (!rows.next()) {
                        return null;
                    }
                    String literal = rows.getString(1);
                    String body = literal.substring(1, literal.length() - 1);
                    String[] parts = body.split(",");
                    float[] values = new float[parts.length];
                    for (int i = 0; i < parts.length; i++) {
                        values[i] = Float.parseFloat(parts[i].trim());
                    }
                    return values;
                });
    }

    /** Read back through the repository rather than against a guessed join-table name: Hibernate owns that
     *  mapping and its naming is not this test's business. What matters is that both adds survived. */
    private static int membershipCount(UUID shelfId) {
        return shelves.findById(shelfId).orElseThrow().getBooks().size();
    }

    private interface RowReader<T> {
        T read(ResultSet rows) throws SQLException;
    }

    /** One-off JDBC outside JavAI entirely -- what the database actually holds, not what the library thinks
     *  it holds. {@code ownerType} may be null for a single-parameter query. */
    private static <T> T query(String sql, String ownerType, UUID ownerId, RowReader<T> reader) {
        try (Connection connection = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             PreparedStatement statement = connection.prepareStatement(sql)) {
            int index = 1;
            if (ownerType != null) {
                statement.setString(index++, ownerType);
            }
            if (ownerId != null) {
                statement.setObject(index, ownerId);
            }
            try (ResultSet rows = statement.executeQuery()) {
                return reader.read(rows);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("probe query failed: " + sql, e);
        }
    }
}
