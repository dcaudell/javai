package dev.xtrafe.javai.collections;

import dev.xtrafe.javai.model.CollectionVectorSupport;
import dev.xtrafe.javai.vector.DirtyTrackingSupport;
import dev.xtrafe.javai.vector.EmbeddingVector;
import dev.xtrafe.javai.model.JavAIArrayList;
import dev.xtrafe.javai.vector.JavAIDirtyTracking;
import dev.xtrafe.javai.model.JavAILinkedHashSet;
import dev.xtrafe.javai.model.JavAIList;
import dev.xtrafe.javai.model.JavAIRuntime;
import dev.xtrafe.javai.model.JavAISet;
import dev.xtrafe.javai.model.JavAIVectorizable;
import dev.xtrafe.javai.vector.VectorMath;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * The concrete {@link KnowledgeGraph}. Hand-written, not woven -- a plain, user-instantiated container
 * (like {@code javai-model}'s {@code JavAIArrayList}): {@code new JavAIKnowledgeGraph<Article,
 * RelatesTo>()}, then {@code addNode}/{@code addEdge} imperatively. See package-info for why
 * {@code @JavAIGraphNode}/{@code @JavAIEdge} aren't woven onto node/edge classes either.
 *
 * <p>{@code vector()}/{@code summaryVector()} are the centroid/decay-weighted-sum over this graph's own
 * {@link #nodes} (via {@link CollectionVectorSupport}, exactly like {@code JavAIArrayList} treats its
 * elements) -- edges don't contribute; they're relationships, not embeddable content in their own right.
 */
public class JavAIKnowledgeGraph<N extends JavAIGraphNode, E extends JavAIEdge>
        implements KnowledgeGraph<N, E>, JavAIDirtyTracking {

    final Set<N> nodes = new LinkedHashSet<>();
    final Map<N, Map<N, Set<E>>> adjacency = new LinkedHashMap<>();
    // Named to match JavAIRuntime.STATE_FIELD exactly, not just "state" -- see JavAIArrayList's own field
    // javadoc (javai-model) for why: JavAIRuntime's reflection-based helpers (stateOf(), and everything
    // built on it, including the whole-subgraph persistence lock) look up this field by that literal name
    // on any JavAIVectorizable node, hand-written or woven alike.
    private final DirtyTrackingSupport $javai$state = new DirtyTrackingSupport();

    @Override
    public void addNode(N node) {
        if (nodes.add(node)) {
            JavAIRuntime.registerDependency(this, node);
            CollectionVectorSupport.onMutated($javai$state, this);
        }
    }

    @Override
    public void addEdge(N from, N to, E edge) {
        addNode(from);
        addNode(to);
        boolean changed = adjacency.computeIfAbsent(from, ignored -> new LinkedHashMap<>())
                .computeIfAbsent(to, ignored -> new LinkedHashSet<>())
                .add(edge);
        if (changed) {
            JavAIRuntime.registerDependency(this, edge);
            CollectionVectorSupport.onMutated($javai$state, this);
        }
    }

    @Override
    public JavAISet<N> nodes() {
        return new JavAILinkedHashSet<>(nodes);
    }

    @Override
    public JavAISet<E> edges(N from, N to) {
        Map<N, Set<E>> targets = adjacency.get(from);
        Set<E> found = targets == null ? null : targets.get(to);
        return found == null ? new JavAILinkedHashSet<>() : new JavAILinkedHashSet<>(found);
    }

    @Override
    public JavAISet<N> neighbors(N node) {
        Map<N, Set<E>> targets = adjacency.get(node);
        return targets == null ? new JavAILinkedHashSet<>() : new JavAILinkedHashSet<>(targets.keySet());
    }

    @Override
    public JavAIList<N> match(Class<N> type, Predicate<N> filter) {
        JavAIArrayList<N> result = new JavAIArrayList<>();
        for (N node : nodes) {
            if (type.isInstance(node) && filter.test(node)) {
                result.add(node);
            }
        }
        return result;
    }

    @Override
    public SubgraphResult<N, E> nearestSubgraph(EmbeddingVector reference, int k, int hops) {
        List<N> ranked = new ArrayList<>(nodes);
        ranked.removeIf(node -> !(node instanceof JavAIVectorizable));
        ranked.sort(Comparator.comparingDouble((N node) -> CollectionVectorSupport.similarityOf(node, reference))
                .reversed());
        List<N> origins = ranked.subList(0, Math.min(k, ranked.size()));

        Set<N> resultNodes = new LinkedHashSet<>();
        Map<N, Map<N, Set<E>>> resultAdjacency = new LinkedHashMap<>();
        Map<N, Map<N, Integer>> hopsByNodeThenOrigin = new LinkedHashMap<>();

        for (N origin : origins) {
            Map<N, Integer> hopsFromThisOrigin = new LinkedHashMap<>();
            hopsFromThisOrigin.put(origin, 0);
            resultNodes.add(origin);

            Deque<N> queue = new ArrayDeque<>();
            queue.add(origin);
            while (!queue.isEmpty()) {
                N current = queue.poll();
                int currentHop = hopsFromThisOrigin.get(current);
                if (currentHop >= hops) {
                    continue;
                }
                for (Map.Entry<N, Set<E>> entry : adjacency.getOrDefault(current, Map.of()).entrySet()) {
                    N neighbor = entry.getKey();
                    resultNodes.add(neighbor);
                    resultAdjacency.computeIfAbsent(current, ignored -> new LinkedHashMap<>())
                            .computeIfAbsent(neighbor, ignored -> new LinkedHashSet<>())
                            .addAll(entry.getValue());
                    if (!hopsFromThisOrigin.containsKey(neighbor)) {
                        hopsFromThisOrigin.put(neighbor, currentHop + 1);
                        queue.add(neighbor);
                    }
                }
            }

            for (Map.Entry<N, Integer> entry : hopsFromThisOrigin.entrySet()) {
                hopsByNodeThenOrigin.computeIfAbsent(entry.getKey(), ignored -> new LinkedHashMap<>())
                        .put(origin, entry.getValue());
            }
        }

        return new JavAIKnowledgeSubgraphResult<>(resultNodes, resultAdjacency, reference, hopsByNodeThenOrigin);
    }

    @Override
    public EmbeddingVector vector() {
        return CollectionVectorSupport.vector($javai$state, nodes);
    }

    @Override
    public EmbeddingVector summaryVector() {
        return CollectionVectorSupport.summaryVector($javai$state, nodes);
    }

    @Override
    public double similarityTo(JavAIVectorizable other) {
        return VectorMath.cosineSimilarity(vector(), other.vector());
    }

    @Override
    public double similarityTo(EmbeddingVector reference) {
        return VectorMath.cosineSimilarity(vector(), reference);
    }

    @Override
    public <T> JavAIList<T> query(EmbeddingVector reference, Class<T> type) {
        return JavAIRuntime.query(nodes, reference, type, Integer.MAX_VALUE);
    }

    @Override
    public <T> JavAIList<T> query(EmbeddingVector reference, Class<T> type, int maxDepth) {
        return JavAIRuntime.query(nodes, reference, type, maxDepth);
    }

    @Override
    public EmbeddingVector fieldVector(String fieldName) {
        throw new UnsupportedOperationException("JavAIKnowledgeGraph has no @Vectorize fields of its own");
    }

    @Override
    public EmbeddingVector concatenatedTextVector() {
        throw new UnsupportedOperationException("JavAIKnowledgeGraph has no @Vectorize fields of its own");
    }

    @Override
    public JavAIList<N> sortByCosineDistance(EmbeddingVector reference) {
        return new JavAILinkedHashSet<>(nodes).sortByCosineDistance(reference);
    }

    @Override
    public void addDependent(Object dependent) {
        $javai$state.addDependent(dependent);
    }

    @Override
    public Iterable<Object> dependents() {
        return $javai$state.dependents();
    }

    @Override
    public boolean isFieldDirty() {
        return $javai$state.isFieldDirty();
    }

    @Override
    public void markFieldDirty() {
        $javai$state.markFieldDirty();
    }

    @Override
    public void clearFieldDirty() {
        $javai$state.clearFieldDirty();
    }

    @Override
    public boolean isSummaryDirty() {
        return $javai$state.isSummaryDirty();
    }

    @Override
    public void markSummaryDirty() {
        $javai$state.markSummaryDirty();
    }

    @Override
    public void clearSummaryDirty() {
        $javai$state.clearSummaryDirty();
    }
}
