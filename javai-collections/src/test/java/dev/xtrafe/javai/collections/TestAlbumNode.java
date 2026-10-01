package dev.xtrafe.javai.collections;

/** One of two distinct runtime classes {@link NarrowedVectorIndexTest} narrows a {@link JavAIVectorIndex}
 *  between -- see {@link TestVectorNode} for why subclassing is how they are made. */
final class TestAlbumNode extends TestVectorNode {

    TestAlbumNode(String text) {
        super(text);
    }
}
