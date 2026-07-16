package dev.xtrafe.javai.persistence;

import dev.xtrafe.javai.collections.JavAIEdge;

/**
 * Idiomatic record-shaped {@link JavAIEdge} fixture for {@link TestOwnerWithGraph}'s
 * {@code KnowledgeGraph} field -- records can't be reflectively field-assigned after construction, so
 * hydration reconstructs them via their canonical constructor (see
 * {@code RepositoryBackendNeo4j#hydrateEdge}). {@code reason} doubles as the edge's own equality/dedup key
 * when persisted, since Neo4j MERGEs an edge relationship on its full property set.
 */
record TestGraphEdge(String reason) implements JavAIEdge {
}
