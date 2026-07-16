package dev.xtrafe.javai.collections;

import dev.xtrafe.javai.vector.EmbeddingVector;
import dev.xtrafe.javai.model.JavAIList;
import dev.xtrafe.javai.model.JavAISet;
import dev.xtrafe.javai.model.JavAISortable;
import dev.xtrafe.javai.model.JavAIVectorizable;

import java.util.function.Predicate;

/**
 * Native graph type: nodes and edges plus hybrid pattern-match + similarity queries in one call. See
 * doc/spec/vector-collections.md for the full contract; {@link JavAIKnowledgeGraph} is the concrete
 * implementation.
 *
 * <p><b>{@code persisted(JavAIRepository<N> backing)} is deliberately not, and will not become, part of
 * this interface.</b> The whitepaper's own signature references {@code JavAIRepository}, which belongs to
 * Persistence Bridge (javai-persistence) -- a module that depends on Vector Collections, not the reverse
 * (SPEC.md's dependency graph), so referencing it here directly was never viable without an inverted
 * dependency. Now that javai-persistence exists, it turns out no such method is needed anyway: a
 * {@code KnowledgeGraph}-typed field on an owning {@code @Entity}/{@code JavAIVectorizable} type persists
 * (Neo4j-only) exactly like any other JavAI collection field, handled entirely by the backend's reflective
 * field mapper -- this interface stays exactly as pure as {@link JavAIList}/{@link JavAISet}/
 * {@code JavAIMap}. See {@code RepositoryBackendNeo4j}'s {@code saveKnowledgeGraphField}/
 * {@code hydrateKnowledgeGraphField} and doc/spec/persistence-bridge.md for the full mapping story.
 */
public interface KnowledgeGraph<N extends JavAIGraphNode, E extends JavAIEdge>
        extends JavAIVectorizable, JavAISortable<N> {

    void addNode(N node);

    void addEdge(N from, N to, E edge);

    JavAISet<N> nodes();

    JavAISet<E> edges(N from, N to);

    JavAISet<N> neighbors(N node);

    /** Pattern-match traversal, no similarity involved. */
    JavAIList<N> match(Class<N> type, Predicate<N> filter);

    /**
     * Hybrid similarity + structure query. The return type is the key design point: a
     * {@link SubgraphResult} IS a {@code KnowledgeGraph}, not a bare list of hits -- nodes/edges that
     * matched stay queryable, sortable, and narrowable exactly like the graph they came from.
     */
    SubgraphResult<N, E> nearestSubgraph(EmbeddingVector reference, int k, int hops);
}
