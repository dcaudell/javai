package dev.xtrafe.javai.model;

import dev.xtrafe.javai.vector.EmbeddingVector;

/**
 * Adds its own field ({@code label}) on top of {@link InheritanceBaseNode}'s inherited {@code child} --
 * {@code summaryVector()} below references both a field declared here and one declared on the
 * superclass, exercising {@link JavAIRuntime}'s hierarchy-aware {@code findField}/{@code allFields}.
 */
final class InheritanceLeafNode extends InheritanceBaseNode {

    private String label;

    void setLabel(String label) {
        this.label = label;
        JavAIRuntime.markFieldDirty(this);
        JavAIRuntime.propagateDirty(this);
    }

    @Override
    public EmbeddingVector vector() {
        return JavAIRuntime.vector(this, "label");
    }

    @Override
    public EmbeddingVector summaryVector() {
        return JavAIRuntime.summaryVector(this, "child", "label");
    }

    @Override
    public double similarityTo(JavAIVectorizable other) {
        return JavAIRuntime.similarityToVectorizable(this, "label", other);
    }

    @Override
    public double similarityTo(EmbeddingVector reference) {
        return JavAIRuntime.similarityToReference(this, "label", reference);
    }
}
