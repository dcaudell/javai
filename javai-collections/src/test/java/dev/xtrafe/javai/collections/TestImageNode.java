package dev.xtrafe.javai.collections;

/** The other of the two runtime classes {@link NarrowedVectorIndexTest} narrows between -- the type that
 *  <em>outranks</em> every album in that test's fixture, so a rank-then-filter implementation returns
 *  nothing where narrowing returns the albums. */
final class TestImageNode extends TestVectorNode {

    TestImageNode(String text) {
        super(text);
    }
}
