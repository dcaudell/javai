package dev.xtrafe.javai.collections;

/** Minimal {@link JavAIEdge} -- a plain relationship label, not itself embeddable. */
final class TestGraphEdge implements JavAIEdge {

    private final String label;

    TestGraphEdge(String label) {
        this.label = label;
    }

    @Override
    public String toString() {
        return label;
    }
}
