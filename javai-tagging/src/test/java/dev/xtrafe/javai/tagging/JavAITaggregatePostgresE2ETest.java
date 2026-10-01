package dev.xtrafe.javai.tagging;

import dev.xtrafe.javai.model.JavAIRuntime;
import dev.xtrafe.javai.model.VectorizableString;
import dev.xtrafe.javai.persistence.JavAIPI;
import dev.xtrafe.javai.persistence.JavAIPersistenceConfig;
import dev.xtrafe.javai.vector.EmbeddingVector;
import dev.xtrafe.javai.vector.testsupport.FakeEmbeddingProvider;
import dev.xtrafe.javai.vector.testsupport.RecordingEmbeddingProvider;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Taggregate against a real pgvector container, on containment rather than a membership snapshot
 * (OMI-304) -- see doc/spec/tagging.md's "Taggregate: derived taggings for containers".
 *
 * <p><b>Every test here calls exactly one thing: {@code addTag} (or {@code applyClassification}).</b> There
 * is no reconcile, no sweep, no loader and no container instance anywhere, because none of those are API
 * any more. That is the ticket's acceptance criterion expressed as a test file: an adopter annotates a
 * field and tags a member, and the containers are right.
 */
@Testcontainers
class JavAITaggregatePostgresE2ETest {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    private static JavAIPersistenceConfig config;
    private static TagRepository tagRepository;
    private static TagSetRepository tagSetRepository;
    private static TestThingRepository thingRepository;
    private static TestAlbumRepository albumRepository;
    private static TestWovenAlbumRepository wovenAlbumRepository;
    private static TestTextedThingRepository textedThingRepository;
    private static RecordingEmbeddingProvider provider;
    private static JavAITagRepository tagging;

    @BeforeAll
    static void configure() {
        provider = new RecordingEmbeddingProvider(new FakeEmbeddingProvider());
        JavAIRuntime.configureEmbeddingProvider(provider);
        config = JavAIPersistenceConfig.builder()
                .backend(JavAIPersistenceConfig.Backend.POSTGRES)
                .postgresUrl(postgres.getJdbcUrl())
                .postgresUsername(postgres.getUsername())
                .postgresPassword(postgres.getPassword())
                .build();
        tagRepository = JavAIPI.repository(TagRepository.class, config);
        tagSetRepository = JavAIPI.repository(TagSetRepository.class, config);
        thingRepository = JavAIPI.repository(TestThingRepository.class, config);
        albumRepository = JavAIPI.repository(TestAlbumRepository.class, config);
        wovenAlbumRepository = JavAIPI.repository(TestWovenAlbumRepository.class, config);
        textedThingRepository = JavAIPI.repository(TestTextedThingRepository.class, config);
        tagging = new JavAITagRepository(tagRepository, config);
    }

    private static Tagging aggregateRowFor(List<Tagging> taggings, Tag tag) {
        return taggings.stream()
                .filter(t -> Tagging.SOURCE_AGGREGATE.equals(t.source()) && t.tag().getId().equals(tag.getId()))
                .findFirst().orElseThrow(() -> new AssertionError(
                        "no aggregate row for '" + tag.getSlug() + "' among " + taggings.size() + " taggings"));
    }

    private static boolean hasAggregateRowFor(List<Tagging> taggings, Tag tag) {
        return taggings.stream()
                .anyMatch(t -> Tagging.SOURCE_AGGREGATE.equals(t.source()) && t.tag().getId().equals(tag.getId()));
    }

    /**
     * ⚠️ <b>The single most important test in this file.</b> The album is saved and then never touched
     * again -- nothing reconciles it, nothing sweeps, no pass of any kind runs first. Tagging one of its
     * members is the only call, and the album's aggregate is correct immediately.
     *
     * <p>The snapshot design could not do this: the snapshot was written <em>by</em> reconciliation, so a
     * never-reconciled container was in no snapshot and tagging its members marked nothing at all. An
     * adopter had to run a bootstrap pass over the whole world to make the incremental path start working.
     */
    @Test
    void taggingAMemberOfANeverReconciledContainerRecomputesIt() {
        TagSet set = tagSetRepository.save(new TagSet("cold-start"));
        Tag topic = tagRepository.save(new Tag(set, "en", "Cold Start Topic"));
        TestThing member = thingRepository.save(new TestThing("cold-start-member"));
        TestAlbum album = new TestAlbum("cold-start-album");
        album.getThings().add(member);
        albumRepository.save(album);

        tagging.addTag(member, topic, 0.8);

        assertEquals(0.8, aggregateRowFor(tagging.taggingsOf(album), topic).affinity(), 1e-9,
                "a container nothing has ever reconciled must still aggregate its member's new tag");
    }

    /** Recompute needs no session held by the caller: the member is tagged through an ordinary repository
     *  call with no ambient transaction, and the container's LAZY @ManyToMany is never walked. */
    @Test
    void recomputeNeedsNoCallerHeldSession() {
        TagSet set = tagSetRepository.save(new TagSet("no-session"));
        Tag topic = tagRepository.save(new Tag(set, "en", "No Session Topic"));
        TestThing member = thingRepository.save(new TestThing("no-session-member"));
        TestAlbum album = new TestAlbum("no-session-album");
        album.getThings().add(member);
        albumRepository.save(album);

        // Detached container, no open session anywhere -- this threw LazyInitializationException before.
        tagging.addTag(member, topic);

        assertEquals(1.0, aggregateRowFor(tagging.taggingsOf(album), topic).affinity(), 1e-9);
    }

    /** Both {@code @Taggregate} placements contribute, and the mean is over their union. */
    @Test
    void singularReferenceAndCollectionBothContribute() {
        TagSet set = tagSetRepository.save(new TagSet("placements"));
        Tag topic = tagRepository.save(new Tag(set, "en", "Placement Topic"));
        TestThing cover = thingRepository.save(new TestThing("placement-cover"));
        TestThing listed = thingRepository.save(new TestThing("placement-listed"));
        TestAlbum album = new TestAlbum("placement-album");
        album.setCover(cover);
        album.getThings().add(listed);
        albumRepository.save(album);

        tagging.addTag(cover, topic, 0.9);
        tagging.addTag(listed, topic, 0.5);

        assertEquals(0.7, aggregateRowFor(tagging.taggingsOf(album), topic).affinity(), 1e-9,
                "(0.9 + 0.5) / 2 members, one reached through each placement");
    }

    /** Heterogeneous lineage, both directions: a woven container over plain members here, a plain container
     *  over woven members ({@link Tag} is build-time woven) in the same test. */
    @Test
    void wovenAndPlainContainersBehaveIdentically() {
        TagSet set = tagSetRepository.save(new TagSet("lineage"));
        Tag topic = tagRepository.save(new Tag(set, "en", "Lineage Topic"));
        TestThing member = thingRepository.save(new TestThing("lineage-member"));
        TestWovenAlbum woven = new TestWovenAlbum("lineage-woven");
        woven.getThings().add(member);
        wovenAlbumRepository.save(woven);

        tagging.addTag(member, topic, 0.6);

        assertEquals(0.6, aggregateRowFor(tagging.taggingsOf(woven), topic).affinity(), 1e-9);
    }

    /** Nesting composes one level at a time: the outer album reads the inner album's own aggregate rows. */
    @Test
    void nestingComposesThroughTheDrain() {
        TagSet set = tagSetRepository.save(new TagSet("nesting"));
        Tag topic = tagRepository.save(new Tag(set, "en", "Nesting Topic"));
        TestThing member = thingRepository.save(new TestThing("nesting-member"));
        TestAlbum inner = new TestAlbum("nesting-inner");
        inner.getThings().add(member);
        TestAlbum outer = new TestAlbum("nesting-outer");
        outer.getAlbums().add(inner);
        albumRepository.save(inner);
        albumRepository.save(outer);

        tagging.addTag(member, topic, 0.8);

        assertEquals(0.8, aggregateRowFor(tagging.taggingsOf(inner), topic).affinity(), 1e-9);
        assertEquals(0.8, aggregateRowFor(tagging.taggingsOf(outer), topic).affinity(), 1e-9,
                "the outer container follows without a second call");
    }

    /** The formula, pinned numerically, and provenance discipline: aggregate rows never disturb manual or
     *  auto rows on the container, and removal propagates. */
    @Test
    void meanAffinityDilutionAndProvenance() {
        TagSet set = tagSetRepository.save(new TagSet("formula"));
        Tag everywhere = tagRepository.save(new Tag(set, "en", "Formula Everywhere"));
        Tag rare = tagRepository.save(new Tag(set, "en", "Formula Rare"));
        Tag manual = tagRepository.save(new Tag(set, "en", "Formula Manual"));

        TestAlbum album = new TestAlbum("formula-album");
        List<TestThing> members = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            TestThing member = thingRepository.save(new TestThing("formula-" + i));
            members.add(member);
            album.getThings().add(member);
        }
        albumRepository.save(album);
        tagging.addTag(album, manual, 0.42);
        for (TestThing member : members) {
            tagging.addTag(member, everywhere, 0.99);
        }
        tagging.addTag(members.get(0), rare);   // null affinity -> 1.0

        List<Tagging> taggings = tagging.taggingsOf(album);
        assertEquals(0.99, aggregateRowFor(taggings, everywhere).affinity(), 1e-9, "10/10 at 0.99");
        assertEquals(0.1, aggregateRowFor(taggings, rare).affinity(), 1e-9, "1/10, null counting 1.0");
        Tagging manualRow = taggings.stream().filter(t -> t.tag().getId().equals(manual.getId()))
                .findFirst().orElseThrow();
        assertEquals(Tagging.SOURCE_MANUAL, manualRow.source(), "a manual row on the container is untouched");
        assertEquals(0.42, manualRow.affinity(), 1e-9);

        tagging.removeTag(members.get(0), rare);
        assertFalse(hasAggregateRowFor(tagging.taggingsOf(album), rare), "removal propagates too");
    }

    /** applyClassification is a choke point like the other two. */
    @Test
    void applyClassificationDrivesTheAggregateToo() {
        TagSet set = tagSetRepository.save(new TagSet("classified"));
        Tag topic = tagRepository.save(new Tag(set, "en", "Classified Topic"));
        TestThing member = thingRepository.save(new TestThing("classified-member"));
        TestAlbum album = new TestAlbum("classified-album");
        album.getThings().add(member);
        albumRepository.save(album);

        tagging.applyClassification(member, set,
                List.of(new ClassificationResult.AppliedTag(topic, 0.7, null)));

        assertEquals(0.7, aggregateRowFor(tagging.taggingsOf(album), topic).affinity(), 1e-9);
    }

    /**
     * The drain runs strictly after commit: a tag mutation inside a transaction that rolls back leaves no
     * tagging row, no pending row and no aggregate row.
     */
    @Test
    void aRolledBackTagMutationLeavesNothingBehind() {
        TagSet set = tagSetRepository.save(new TagSet("rollback"));
        Tag topic = tagRepository.save(new Tag(set, "en", "Rollback Topic"));
        TestThing member = thingRepository.save(new TestThing("rollback-member"));
        TestAlbum album = new TestAlbum("rollback-album");
        album.getThings().add(member);
        albumRepository.save(album);

        try {
            JavAIPI.inTransaction(config, () -> {
                tagging.addTag(member, topic, 0.9);
                throw new IllegalStateException("deliberate rollback");
            });
        } catch (IllegalStateException expected) {
            assertEquals("deliberate rollback", expected.getMessage());
        }

        assertFalse(tagging.hasTag(member, topic), "the tag row rolled back with the caller's transaction");
        assertFalse(hasAggregateRowFor(tagging.taggingsOf(album), topic),
                "and no aggregate was derived from a tagging that never committed");
        assertEquals(0, pendingRowCount(), "nor was a pending row left owing work for it");
    }

    /** The commit half of the same mechanism: work deferred to the commit callback actually happens. */
    @Test
    void aCommittedTransactionDrainsAfterItCommits() {
        TagSet set = tagSetRepository.save(new TagSet("committed"));
        Tag topic = tagRepository.save(new Tag(set, "en", "Committed Topic"));
        TestThing member = thingRepository.save(new TestThing("committed-member"));
        TestAlbum album = new TestAlbum("committed-album");
        album.getThings().add(member);
        albumRepository.save(album);

        JavAIPI.inTransaction(config, () -> {
            tagging.addTag(member, topic, 0.55);
            return null;
        });

        assertEquals(0.55, aggregateRowFor(tagging.taggingsOf(album), topic).affinity(), 1e-9);
        assertEquals(0, pendingRowCount(), "a completed drain leaves the queue empty");
    }

    /** Concurrent tag writes beneath one container converge, with the overlap forced by a barrier. */
    @Test
    void concurrentTagWritesBeneathOneContainerConverge() throws Exception {
        TagSet set = tagSetRepository.save(new TagSet("concurrent"));
        Tag topic = tagRepository.save(new Tag(set, "en", "Concurrent Topic"));
        TestAlbum album = new TestAlbum("concurrent-album");
        List<TestThing> members = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            TestThing member = thingRepository.save(new TestThing("concurrent-" + i));
            members.add(member);
            album.getThings().add(member);
        }
        albumRepository.save(album);

        CyclicBarrier barrier = new CyclicBarrier(members.size());
        List<Throwable> failures = new ArrayList<>();
        List<Thread> threads = new ArrayList<>();
        for (TestThing member : members) {
            Thread thread = new Thread(() -> {
                try {
                    barrier.await();
                    tagging.addTag(member, topic, 0.6);
                } catch (Throwable t) {
                    synchronized (failures) {
                        failures.add(t);
                    }
                }
            });
            threads.add(thread);
            thread.start();
        }
        for (Thread thread : threads) {
            thread.join();
        }
        assertTrue(failures.isEmpty(), () -> "concurrent tagging failed: " + failures);

        List<Tagging> taggings = tagging.taggingsOf(album);
        assertEquals(1, taggings.stream().filter(t -> Tagging.SOURCE_AGGREGATE.equals(t.source())).count());
        assertEquals(0.6, aggregateRowFor(taggings, topic).affinity(), 1e-9,
                "all four members at 0.6 -- whichever recompute ran last read the committed truth");
    }

    /**
     * {@code rebuildTaggregates()} is the after-a-restore repair: rows written by direct SQL are exactly
     * what the choke points cannot see, and the only thing that can put the aggregates right.
     */
    @Test
    void rebuildRepairsAggregatesAfterDirectSqlWrites() throws SQLException {
        TagSet set = tagSetRepository.save(new TagSet("rebuild"));
        Tag topic = tagRepository.save(new Tag(set, "en", "Rebuild Topic"));
        TestThing member = thingRepository.save(new TestThing("rebuild-member"));
        TestAlbum album = new TestAlbum("rebuild-album");
        album.getThings().add(member);
        albumRepository.save(album);

        insertTaggingByDirectSql(member, topic);
        assertFalse(hasAggregateRowFor(tagging.taggingsOf(album), topic),
                "nothing observed that write, so the aggregate is wrong -- which is the premise");

        assertTrue(tagging.rebuildTaggregates() > 0);
        assertEquals(1.0, aggregateRowFor(tagging.taggingsOf(album), topic).affinity(), 1e-9,
                "rebuild is what makes a restored world right again");
    }

    /** Tag text still works, and a container that opted into nothing still costs no embeds. */
    @Test
    void tagTextAndZeroEmbedTypesAreUnchanged() {
        TagSet set = tagSetRepository.save(new TagSet("text"));
        Tag lake = tagRepository.save(new Tag(set, "en", "Alpine Lake"));
        Tag road = tagRepository.save(new Tag(set, "en", "Dirt Road"));
        TestThing member = thingRepository.save(new TestThing("text-member"));
        TestAlbum album = new TestAlbum("text-album");
        album.getThings().add(member);
        albumRepository.save(album);

        tagging.addTag(member, lake, 0.9);
        tagging.addTag(member, road, 0.5);

        assertEquals("Alpine Lake, Dirt Road", tagging.tagText(album),
                "display names, affinity-descending, unchanged by OMI-304");
        assertFalse(tagging.tagTextVector(album).isAbsent());

        TestThing plain = thingRepository.save(new TestThing("text-plain"));
        provider.reset();
        tagging.addTag(plain, lake);
        assertEquals(0, provider.ledger().totalCalls(),
                "a type opting into nothing still embeds nothing: " + provider.ledger().report());
    }

    /** A texted type that is not a container at all still serves its own tag text. */
    @Test
    void aNonAggregatingTypeStillConcatenates() {
        TagSet set = tagSetRepository.save(new TagSet("texted"));
        Tag tag = tagRepository.save(new Tag(set, "en", "Texted Topic"));
        TestTextedThing thing = textedThingRepository.save(new TestTextedThing("texted"));

        tagging.addTag(thing, tag, 0.5);

        assertEquals("Texted Topic", tagging.tagText(thing));
    }

    /**
     * The multi-pod shape, mirroring {@code Containment}'s own Library/Shelf/Book reasoning: the tagging
     * call happens through a repository that never loaded the container, and holds no reference to it.
     *
     * <p>There is no back-edge to walk here -- the object that would carry one was never in this JVM's
     * memory in the state the tag was applied. Containment answers from the stored relationships, which is
     * exactly why it works for a pod that holds nothing.
     */
    @Test
    void containersAreFoundByAPodThatNeverLoadedThem() {
        TagSet set = tagSetRepository.save(new TagSet("multi-pod"));
        Tag topic = tagRepository.save(new Tag(set, "en", "Multi Pod Topic"));
        TestThing member = thingRepository.save(new TestThing("multi-pod-member"));
        UUID albumId;
        {
            TestAlbum album = new TestAlbum("multi-pod-album");
            album.getThings().add(member);
            albumRepository.save(album);
            albumId = album.getId();
        }   // the only TestAlbum instance goes out of scope here, as it would on another pod

        // Re-read the member by id, so nothing in this call stack has ever held the album.
        TestThing reloadedMember = thingRepository.findById(member.getId()).orElseThrow();
        tagging.addTag(reloadedMember, topic, 0.75);

        TestAlbum reloadedAlbum = albumRepository.findById(albumId).orElseThrow();
        assertEquals(0.75, aggregateRowFor(tagging.taggingsOf(reloadedAlbum), topic).affinity(), 1e-9,
                "a container this pod never held is still found and recomputed");
    }

    /** A tagged instance nothing contains has no container to find, and that is not an error. */
    @Test
    void anUncontainedMemberIsHarmless() {
        TagSet set = tagSetRepository.save(new TagSet("orphan"));
        Tag tag = tagRepository.save(new Tag(set, "en", "Orphan Topic"));
        TestThing orphan = thingRepository.save(new TestThing("orphan"));

        tagging.addTag(orphan, tag, 0.5);

        assertTrue(tagging.hasTag(orphan, tag));
        assertNull(tagging.tagText(orphan), "TestThing never opted into tag text");
    }

    private static void insertTaggingByDirectSql(TestThing member, Tag tag) throws SQLException {
        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            statement.execute("INSERT INTO taggings (id, tag_id, taggable_type, taggable_id, affinity, source,"
                    + " created_at) VALUES ('" + UUID.randomUUID() + "', '" + tag.getId() + "', '"
                    + TestThing.class.getName() + "', '" + member.getId() + "', NULL, '"
                    + Tagging.SOURCE_MANUAL + "', now())");
        }
    }

    private static int pendingRowCount() {
        try (Connection connection = connect(); Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(
                        "SELECT count(*) FROM javai_taggregate_pending")) {
            rows.next();
            return rows.getInt(1);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Connection connect() throws SQLException {
        return DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }
}
