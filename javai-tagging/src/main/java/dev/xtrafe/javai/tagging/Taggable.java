package dev.xtrafe.javai.tagging;

/**
 * Empty on purpose -- see doc/spec/tagging.md's "Orthogonality: Taggable is not Vectorizable" for why there
 * is nothing to weave. Hand-implemented alongside {@code @Taggable} purely so generic tagging APIs can
 * bound {@code <T extends Taggable>}, mirroring {@code <N extends JavAIGraphNode>} on {@code KnowledgeGraph}.
 */
public interface Taggable {
}
