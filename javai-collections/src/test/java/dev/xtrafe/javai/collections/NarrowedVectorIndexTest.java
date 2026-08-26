package dev.xtrafe.javai.collections;

import dev.xtrafe.javai.model.JavAIList;
import dev.xtrafe.javai.model.JavAIRuntime;
import dev.xtrafe.javai.vector.EmbeddingVector;
import dev.xtrafe.javai.vector.Ranked;
import dev.xtrafe.javai.vector.testsupport.ScriptedEmbeddingProvider;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code VectorIndex} narrowing and the ranked search, in memory (OMI-460).
 *
 * <h2>The fixture is the assertion</h2>
 *
 * Every <b>image</b> is placed nearer the reference than every <b>album</b>: images at 2°/4°/6°, albums at
 * 40°/45°/50°, via {@link ScriptedEmbeddingProvider}, whose cosine similarity to the reference is exactly
 * {@code cos θ}. So "the nearest 2 albums" and "the albums among the nearest 2" are not merely different
 * answers here -- the second is <em>empty</em>. A rank-then-filter implementation cannot pass this by
 * accident, and neither can an over-fetch-by-some-multiple one once the multiplier is exceeded.
 */
class NarrowedVectorIndexTest {

    private static final double ALBUM_ONE_DEGREES = 40;
    private static final double ALBUM_TWO_DEGREES = 45;
    private static final double ALBUM_THREE_DEGREES = 50;

    private static ScriptedEmbeddingProvider provider;

    private TestImageNode imageOne;
    private TestVectorNode plainNode;
    private TestAlbumNode albumOne;
    private TestAlbumNode albumTwo;
    private TestAlbumNode albumThree;

    @BeforeAll
    static void configureProvider() {
        provider = new ScriptedEmbeddingProvider()
                .at("image-one", 2).at("image-two", 4).at("image-three", 6)
                .at("album-one", ALBUM_ONE_DEGREES)
                .at("album-two", ALBUM_TWO_DEGREES)
                .at("album-three", ALBUM_THREE_DEGREES)
                .at("plain-node", 8);
        JavAIRuntime.configureEmbeddingProvider(provider);
    }

    /** Three images ahead of three albums, plus one instance of the shared base class. */
    private JavAIVectorIndex<TestVectorNode> seeded() {
        imageOne = new TestImageNode("image-one");
        albumOne = new TestAlbumNode("album-one");
        albumTwo = new TestAlbumNode("album-two");
        albumThree = new TestAlbumNode("album-three");
        JavAIVectorIndex<TestVectorNode> index = new JavAIVectorIndex<>();
        index.add(imageOne);
        index.add(new TestImageNode("image-two"));
        index.add(new TestImageNode("image-three"));
        index.add(albumOne);
        index.add(albumTwo);
        index.add(albumThree);
        plainNode = new TestVectorNode("plain-node");
        index.add(plainNode);
        return index;
    }

    private static EmbeddingVector reference() {
        return provider.reference();
    }

    @Test
    void ofTypeNarrowsBeforeTheTopNIsChosen() {
        JavAIList<TestVectorNode> albums = seeded().ofType(TestAlbumNode.class).nearestN(reference(), 2);

        assertEquals(2, albums.size(), "the nearest 2 albums must be 2 albums -- every image outranks every "
                + "album in this fixture, so filtering the nearest 2 would have returned none");
        assertEquals(List.of(albumOne, albumTwo), List.copyOf(albums), "still nearest-first within the type");
    }

    @Test
    void nearestNWithCandidateTypesIsTheSameQueryWrittenInOneCall() {
        JavAIVectorIndex<TestVectorNode> index = seeded();

        JavAIList<TestVectorNode> chained = index.ofType(TestAlbumNode.class).nearestN(reference(), 2);
        JavAIList<TestVectorNode> direct = index.nearestN(reference(), 2, List.of(TestAlbumNode.class));

        assertEquals(List.copyOf(chained), List.copyOf(direct));
    }

    @Test
    void nearestNRankedCarriesTheSimilarityItRankedOn() {
        List<Ranked<TestVectorNode>> albums =
                seeded().ofType(TestAlbumNode.class).nearestNRanked(reference(), 3);

        assertEquals(3, albums.size());
        assertEquals(List.of(albumOne, albumTwo, albumThree),
                albums.stream().map(Ranked::entity).toList());
        // Closed form, not "some number": cos θ is what the provider's own contract says these must be, so
        // this pins the value rather than the ordering a second time.
        assertEquals(ScriptedEmbeddingProvider.expectedSimilarity(ALBUM_ONE_DEGREES),
                albums.get(0).similarity(), 1e-6);
        assertEquals(ScriptedEmbeddingProvider.expectedSimilarity(ALBUM_TWO_DEGREES),
                albums.get(1).similarity(), 1e-6);
        assertEquals(ScriptedEmbeddingProvider.expectedSimilarity(ALBUM_THREE_DEGREES),
                albums.get(2).similarity(), 1e-6);
    }

    @Test
    void nearestNRankedAgreesWithNearestNOnOrder() {
        JavAIVectorIndex<TestVectorNode> index = seeded();

        List<TestVectorNode> plain = List.copyOf(index.nearestN(reference(), 4));
        List<TestVectorNode> ranked = index.nearestNRanked(reference(), 4).stream().map(Ranked::entity).toList();

        assertEquals(plain, ranked, "one ranking backs both, so the two can never order differently");
    }

    @Test
    void nearestNRankedNarrowedIsTheOneSignatureCoveringBoth() {
        List<Ranked<TestVectorNode>> albums =
                seeded().nearestNRanked(reference(), 2, List.of(TestAlbumNode.class));

        assertEquals(List.of(albumOne, albumTwo), albums.stream().map(Ranked::entity).toList());
    }

    @Test
    void narrowingIntersectsRatherThanReplaces() {
        JavAIVectorIndex<TestVectorNode> index = seeded();

        VectorIndex<TestVectorNode> both = index.ofType(TestAlbumNode.class, TestImageNode.class);
        assertEquals(6, both.size());
        assertEquals(3, both.ofType(TestAlbumNode.class).size(), "narrowing a narrowed view keeps the "
                + "intersection");
        assertEquals(0, index.ofType(TestAlbumNode.class).ofType(TestImageNode.class).size(),
                "a type the view already excluded stays excluded, so this matches nothing");
    }

    @Test
    void narrowingToNoTypesMatchesNothing() {
        VectorIndex<TestVectorNode> nothing = seeded().ofType(List.of());

        assertEquals(0, nothing.size());
        assertTrue(nothing.nearestN(reference(), 5).isEmpty(), "naming no types is not a way to say "
                + "'everything' -- the same rule taggedWith(tag, List.of()) already follows");
        assertTrue(nothing.nearestNRanked(reference(), 5).isEmpty());
    }

    @Test
    void narrowingMatchesExactRuntimeClassNotAssignability() {
        JavAIVectorIndex<TestVectorNode> index = seeded();

        JavAIList<TestVectorNode> base = index.ofType(TestVectorNode.class).nearestN(reference(), 10);

        assertEquals(1, base.size(), "TestAlbumNode and TestImageNode are TestVectorNodes, but the rule is "
                + "exact runtime class -- only the base-class instance matches");
        assertSame(plainNode, base.get(0));
        assertFalse(base.contains(albumOne));
        assertFalse(base.contains(imageOne));
    }

    @Test
    void narrowedFilterByMinSimilarityAndSortSeeOnlyTheAdmittedItems() {
        VectorIndex<TestVectorNode> albums = seeded().ofType(TestAlbumNode.class);

        // A threshold between album-two and album-three: the two nearer albums pass, the third does not,
        // and no image is even considered -- every one of them would have passed on similarity alone.
        JavAIList<TestVectorNode> above = albums.filterByMinSimilarity(reference(),
                ScriptedEmbeddingProvider.expectedSimilarity(ALBUM_TWO_DEGREES) - 1e-9);
        assertEquals(2, above.size());
        assertTrue(above.contains(albumOne) && above.contains(albumTwo));
        assertFalse(above.contains(albumThree));
        assertFalse(above.contains(imageOne), "an image clears this threshold easily and is still excluded, "
                + "because the narrowing happens before the threshold rather than after it");

        JavAIList<TestVectorNode> sorted = albums.sortByCosineDistance(reference());
        assertEquals(List.of(albumOne, albumTwo, albumThree), List.copyOf(sorted),
                "sortByCosineDistance over a narrowed view orders exactly the admitted items");
    }

    @Test
    void aNarrowedViewIsLiveOverTheIndexItCameFrom() {
        JavAIVectorIndex<TestVectorNode> index = seeded();
        VectorIndex<TestVectorNode> albums = index.ofType(TestAlbumNode.class);
        assertEquals(3, albums.size());

        index.add(new TestAlbumNode("album-four"));

        assertEquals(4, albums.size(), "a view, not a snapshot -- what the index gains, the view gains");
    }

    @Test
    void aNarrowedViewRefusesMutation() {
        VectorIndex<TestVectorNode> albums = seeded().ofType(TestAlbumNode.class);

        assertThrows(UnsupportedOperationException.class, () -> albums.add(new TestAlbumNode("album-five")));
        assertThrows(UnsupportedOperationException.class, () -> albums.remove(albumOne));
    }

    @Test
    void theUnnarrowedIndexIsUnaffected() {
        JavAIVectorIndex<TestVectorNode> index = seeded();
        VectorIndex<TestVectorNode> narrowed = index.ofType(TestAlbumNode.class);

        assertEquals(7, index.size(), "narrowing produces a new view and leaves its source alone");
        assertSame(imageOne, index.nearestN(reference(), 1).get(0));
        assertEquals(3, narrowed.size());
    }
}
