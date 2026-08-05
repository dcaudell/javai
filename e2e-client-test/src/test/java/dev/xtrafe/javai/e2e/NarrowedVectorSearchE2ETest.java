package dev.xtrafe.javai.e2e;

import dev.xtrafe.javai.e2e.domain.MediaNote;
import dev.xtrafe.javai.e2e.domain.MediaNoteRepository;
import dev.xtrafe.javai.e2e.environment.JavAIEnvironment;
import dev.xtrafe.javai.model.JavAIVectorizable;
import dev.xtrafe.javai.persistence.Ranked;
import dev.xtrafe.javai.vector.EmbeddingVector;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

import static dev.xtrafe.javai.e2e.domain.MediaNote.Kind.AUDIO;
import static dev.xtrafe.javai.e2e.domain.MediaNote.Kind.VIDEO;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Narrowed vector search against a <b>real embedding model</b> (OMI-230).
 *
 * <h2>What this adds over the hermetic suites</h2>
 *
 * {@code javai-persistence}'s own tests script the vectors, so they can assert exact orderings and exact
 * similarities -- but they prove the plumbing, not that the plumbing carries meaning. Here the vectors come
 * from the same model an application would use, and the question is whether <b>semantic ranking survives
 * narrowing</b>: does restricting to a kind still return the semantically nearest members of that kind, or
 * merely some members of it?
 *
 * <h2>The fixture, and the trap it sets</h2>
 *
 * Two clearly separated topics, arranged so the wanted kind is the <em>losing</em> one:
 *
 * <ul>
 *   <li>Four <b>cooking</b> notes, all {@code AUDIO} -- these dominate an unnarrowed cooking query.</li>
 *   <li>Two <b>cooking</b> notes as {@code VIDEO} -- the semantically right answers once narrowed.</li>
 *   <li>Four <b>astronomy</b> notes as {@code VIDEO} -- the wrong answers that are nonetheless the right
 *       kind.</li>
 * </ul>
 *
 * A cooking query narrowed to {@code VIDEO} therefore has two things to get right at once, and failing
 * either is visible: it must return a <em>full</em> page (proving the limit applied after the predicate, not
 * before), and the cooking videos must come first (proving the ranking is still semantic and not just
 * whatever the filter happened to yield).
 *
 * <p>Assertions are deliberately about topic membership and result counts rather than exact orderings
 * between two notes on the same topic -- a real model's ranking among near-synonyms is not something a test
 * should pin, and pinning it is how a suite becomes flaky against a model upgrade.
 */
class NarrowedVectorSearchE2ETest {

    private static final List<String> COOKING = List.of(
            "Slow braised short ribs with red wine and root vegetables",
            "How to laminate dough for croissants at home",
            "Knife skills: dicing onions without tears",
            "Fermenting sourdough starter in a cold kitchen");

    private static final List<String> COOKING_VIDEO = List.of(
            "Filleting a whole fish, step by step in the kitchen",
            "Tempering chocolate for glossy homemade truffles");

    private static final List<String> ASTRONOMY_VIDEO = List.of(
            "Observing Jupiter's moons through a backyard telescope",
            "Why supernova remnants glow in radio wavelengths",
            "Mapping the cosmic microwave background radiation",
            "Tracking near-Earth asteroids across the night sky");

    private static MediaNoteRepository notes;
    private static EmbeddingVector cookingReference;

    @BeforeAll
    static void seed() {
        notes = JavAIEnvironment.postgresMediaNoteRepository();
        for (String caption : COOKING) {
            notes.save(new MediaNote(caption, AUDIO, true));
        }
        for (String caption : COOKING_VIDEO) {
            notes.save(new MediaNote(caption, VIDEO, true));
        }
        for (int i = 0; i < ASTRONOMY_VIDEO.size(); i++) {
            // The last astronomy note is unpublished, so the published-flag case below has something to cut.
            notes.save(new MediaNote(ASTRONOMY_VIDEO.get(i), VIDEO, i < ASTRONOMY_VIDEO.size() - 1));
        }

        MediaNote probe = notes.save(new MediaNote(
                "Braising tough cuts of meat low and slow for a rich dinner", AUDIO, true));
        cookingReference = ((JavAIVectorizable) probe).fieldVector("caption");
    }

    /** Polls until {@code query} yields at least {@code expected} hits, or a generous deadline passes --
     *  the same shape (and the same reasoning about a loaded machine) as {@code PersistenceE2ETest}'s own
     *  {@code awaitNearest}. Returns whatever the last attempt produced, so the assertion that follows
     *  reports the real shortfall rather than a timeout. */
    private static List<MediaNote> awaitAtLeast(Supplier<List<MediaNote>> query, int expected) {
        Instant deadline = Instant.now().plus(Duration.ofMinutes(2));
        while (true) {
            List<MediaNote> result = query.get();
            if (result.size() >= expected || Instant.now().isAfter(deadline)) {
                return result;
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return result;
            }
        }
    }

    private static boolean isCooking(MediaNote note) {
        return COOKING.contains(note.getCaption()) || COOKING_VIDEO.contains(note.getCaption())
                || note.getCaption().startsWith("Braising tough cuts");
    }

    /** Establishes the trap: unnarrowed, a cooking query is answered entirely by cooking AUDIO notes. */
    @Test
    @DisplayName("unnarrowed, a cooking query returns cooking notes -- and they are the AUDIO ones")
    void unnarrowedQueryIsDominatedByOneKind() {
        List<MediaNote> hits = notes.findNearestByCaptionVector(cookingReference, 4);

        assertEquals(4, hits.size());
        assertTrue(hits.stream().allMatch(NarrowedVectorSearchE2ETest::isCooking),
                "a real model must rank cooking captions above astronomy ones for a cooking query: "
                        + hits.stream().map(MediaNote::getCaption).toList());
        assertTrue(hits.stream().anyMatch(note -> note.getKind() == AUDIO),
                "the fixture intends the nearest to be AUDIO, which is what makes narrowing to VIDEO a test");
    }

    /**
     * The ticket, against a real model: a full page of the requested kind, semantically ordered within it.
     */
    @Test
    @DisplayName("narrowing to VIDEO returns a full page of VIDEOs, cooking ones first")
    void narrowingReturnsAFullPageOfTheRightKindInSemanticOrder() {
        List<MediaNote> hits = notes.findNearestByCaptionVectorAndKindIs(cookingReference, 3, VIDEO);

        assertEquals(3, hits.size(), "asking for 3 VIDEOs must yield 3 -- fewer would mean the predicate was "
                + "applied to an already-limited ranking, which is the over-fetch this feature removes");
        assertTrue(hits.stream().allMatch(note -> note.getKind() == VIDEO),
                "every hit must be the requested kind");

        List<MediaNote> firstTwo = hits.subList(0, 2);
        assertTrue(firstTwo.stream().allMatch(NarrowedVectorSearchE2ETest::isCooking),
                "narrowing must not cost semantic ordering: the two cooking VIDEOs should outrank the "
                        + "astronomy ones for a cooking query, but got "
                        + hits.stream().map(MediaNote::getCaption).toList());
    }

    /**
     * Narrowing must change <em>which</em> notes come back, not merely reorder them.
     *
     * <p>Asserted as "the two pages differ, and specifically the unnarrowed one contains something the
     * narrowed one structurally cannot" rather than as "the two pages are disjoint". Disjointness is the
     * stronger claim and it is not true here, for a good reason discovered by measurement rather than
     * assumed: the model ranks caption text, and a cooking VIDEO's caption is every bit as cooking-related
     * as a cooking AUDIO's, so a cooking video legitimately places inside the unnarrowed top 3. Pinning
     * disjointness would have been pinning an accident of the fixture, and would break on any model that
     * ranked the overlapping note one place differently.
     */
    @Test
    @DisplayName("narrowing genuinely changes which notes come back, not just their order")
    void narrowingChangesTheResultSet() {
        List<MediaNote> unnarrowed = notes.findNearestByCaptionVector(cookingReference, 3);
        List<MediaNote> narrowed = notes.findNearestByCaptionVectorAndKindIs(cookingReference, 3, VIDEO);

        assertEquals(3, narrowed.size());
        assertTrue(unnarrowed.stream().anyMatch(note -> note.getKind() == AUDIO),
                "the unnarrowed page must contain an AUDIO note -- otherwise narrowing to VIDEO could not "
                        + "be shown to have changed anything");
        assertTrue(narrowed.stream().allMatch(note -> note.getKind() == VIDEO));

        Set<String> unnarrowedCaptions = Set.copyOf(unnarrowed.stream().map(MediaNote::getCaption).toList());
        Set<String> narrowedCaptions = Set.copyOf(narrowed.stream().map(MediaNote::getCaption).toList());
        assertFalse(unnarrowedCaptions.equals(narrowedCaptions),
                "the two pages must not be the same set of notes");
        assertTrue(narrowedCaptions.stream().anyMatch(caption -> !unnarrowedCaptions.contains(caption)),
                "and the narrowed page must surface at least one note the unnarrowed page never reached -- "
                        + "which is the whole point: those notes were previously unreachable without "
                        + "over-fetching");
    }

    @Test
    @DisplayName("two conditions compose against a real model")
    void twoConditionsCompose() {
        List<MediaNote> hits = notes.findNearestByCaptionVectorAndKindInAndPublishedTrue(
                cookingReference, 10, Set.of(VIDEO));

        assertTrue(hits.stream().allMatch(note -> note.getKind() == VIDEO && note.isPublished()));
        assertFalse(hits.stream().anyMatch(note -> note.getCaption().equals(ASTRONOMY_VIDEO.get(3))),
                "the unpublished astronomy note must be excluded by the published condition");
    }

    @Test
    @DisplayName("ranked results carry a plausible cosine similarity, ordered nearest first")
    void rankedResultsCarrySimilarity() {
        List<Ranked<MediaNote>> hits = notes.findNearestByCaptionVectorAndKindIs(
                cookingReference, VIDEO, PageRequest.of(0, 3));

        assertEquals(3, hits.size());
        for (Ranked<MediaNote> hit : hits) {
            assertTrue(hit.similarity() >= -1.0 && hit.similarity() <= 1.0,
                    "a cosine similarity must be in [-1, 1], but was " + hit.similarity()
                            + " -- a value outside it means a backend's own score reached the caller "
                            + "unconverted");
        }
        for (int i = 1; i < hits.size(); i++) {
            assertTrue(hits.get(i - 1).similarity() >= hits.get(i).similarity(),
                    "hits must be ordered nearest first");
        }
        assertTrue(hits.get(0).similarity() > 0.0,
                "the nearest cooking video must be positively similar to a cooking query, but scored "
                        + hits.get(0).similarity());
    }

    @Test
    @DisplayName("paging a narrowed search walks the matches without repeating them")
    void pagingWalksTheMatches() {
        List<String> firstPage = notes.findNearestByCaptionVectorAndKindIs(
                        cookingReference, VIDEO, PageRequest.of(0, 2)).stream()
                .map(hit -> hit.entity().getCaption()).toList();
        List<String> secondPage = notes.findNearestByCaptionVectorAndKindIs(
                        cookingReference, VIDEO, PageRequest.of(1, 2)).stream()
                .map(hit -> hit.entity().getCaption()).toList();

        assertEquals(2, firstPage.size());
        assertEquals(2, secondPage.size());
        assertTrue(java.util.Collections.disjoint(firstPage, secondPage),
                "consecutive pages must not repeat a hit: " + firstPage + " vs " + secondPage);
    }

    @Test
    @DisplayName("the builder idiom answers identically against a real model")
    void builderAgreesWithTheMethodName() {
        List<String> viaMethodName = notes.findNearestByCaptionVectorAndKindIs(cookingReference, 3, VIDEO)
                .stream().map(MediaNote::getCaption).toList();
        List<String> viaBuilder = notes.nearestBy("caption")
                .to(cookingReference)
                .where("kind").is(VIDEO)
                .limit(3)
                .results()
                .stream().map(MediaNote::getCaption).toList();

        assertEquals(viaMethodName, viaBuilder);
    }

    @Test
    @DisplayName("the same query narrows identically on MongoDB")
    void mongoNarrowsTheSameWay() {
        MediaNoteRepository mongoNotes = JavAIEnvironment.mongoMediaNoteRepository();
        for (String caption : COOKING) {
            mongoNotes.save(new MediaNote(caption, AUDIO, true));
        }
        for (String caption : COOKING_VIDEO) {
            mongoNotes.save(new MediaNote(caption, VIDEO, true));
        }
        for (String caption : ASTRONOMY_VIDEO) {
            mongoNotes.save(new MediaNote(caption, VIDEO, true));
        }

        // MongoDB Search indexes newly-written documents asynchronously, so a just-saved note is briefly
        // absent from the index -- the same lag PersistenceE2ETest polls through. Without this, an empty
        // result reads as "narrowing is broken on Mongo" rather than "the index has not caught up yet".
        List<MediaNote> hits = awaitAtLeast(
                () -> mongoNotes.findNearestByCaptionVectorAndKindIs(cookingReference, 3, VIDEO), 3);

        assertEquals(3, hits.size(), "the Mongo Search index never caught up with the seeded notes");
        assertTrue(hits.stream().allMatch(note -> note.getKind() == VIDEO));
        assertTrue(hits.subList(0, 2).stream().allMatch(NarrowedVectorSearchE2ETest::isCooking),
                hits.stream().map(MediaNote::getCaption).toList().toString());
    }
}
