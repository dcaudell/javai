package dev.xtrafe.javai.collections;

import dev.xtrafe.javai.runtime.EmbeddingVector;
import dev.xtrafe.javai.runtime.JavAIList;
import dev.xtrafe.javai.runtime.JavAISet;
import dev.xtrafe.javai.runtime.JavAISortable;
import dev.xtrafe.javai.runtime.JavAIVectorizable;

import java.util.function.Predicate;

/**
 * Native graph type: nodes and edges plus hybrid pattern-match + similarity queries in one call. See
 * doc/spec/vector-collections.md for the full contract; {@link JavAIKnowledgeGraph} is the concrete
 * implementation.
 *
 * <p><b>{@code persisted(JavAIRepository<N> backing)} is deliberately not part of this interface yet.</b>
 * The whitepaper's own signature references {@code JavAIRepository}, which belongs to Persistence Bridge
 * (javai-persistence) -- a module that depends on Vector Collections, not the reverse (SPEC.md's
 * dependency graph). Referencing it here would mean either a forward reference to a module that doesn't
 * exist, or moving {@code JavAIRepository} someplace it doesn't belong just to satisfy a type signature
 * that has no implementation behind it yet. Add it when javai-persistence exists to back it for real.
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
