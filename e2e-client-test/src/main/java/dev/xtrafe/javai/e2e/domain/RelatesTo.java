package dev.xtrafe.javai.e2e.domain;

import dev.xtrafe.javai.collections.JavAIEdge;

/**
 * A {@code KnowledgeGraph} edge between two {@link Article} nodes. Declared directly, per
 * {@code javai-collections}' own advisory: {@code @JavAIEdge} is never woven, since it's an empty marker
 * interface with no method bodies to synthesize.
 */
public record RelatesTo(String reason) implements JavAIEdge {
}
