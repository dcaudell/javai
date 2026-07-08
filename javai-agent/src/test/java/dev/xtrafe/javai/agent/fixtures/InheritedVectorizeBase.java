package dev.xtrafe.javai.agent.fixtures;

import dev.xtrafe.javai.annotations.Vectorize;

/**
 * Deliberately plain -- no {@code @JavAIVectorizable}, no woven behavior of its own. Proves the weaver can
 * pick up a {@code @Vectorize} field, and instrument its setter, when both live only on an ancestor of the
 * class actually annotated {@code @JavAIVectorizable}. See {@link InheritedVectorizeLeaf}.
 */
public class InheritedVectorizeBase {

    @Vectorize
    private String label;

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
    }
}
