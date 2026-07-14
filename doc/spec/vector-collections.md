# Vector Collections

Module: `javai-collections`. Whitepaper: §5.4, §6.4–§6.7. Depends on `javai-vector` + `javai-model` (Vector
Core).

**Module-placement note (discovered while scaffolding, not in the whitepaper):** `JavAISortable<T>`,
`JavAIList<T>`, `JavAISet<T>`, and `JavAIMap<K,V>` — described below as part of this extension area —
physically live in `javai-model`, not in this module. Reason: `JavAIVectorizable.query()` returns
`JavAIList<T>`, and this module depends on `javai-model`, not the reverse; keeping `JavAIList` here would
create a circular module dependency. What's actually in `javai-collections` is `KnowledgeGraph`,
`SubgraphResult`, `VectorIndex`, and the `JavAIGraphNode`/`JavAIEdge` interfaces — the types that depend on
`javai-model`'s (and `javai-vector`'s), not the reverse. Treat the descriptions below as the conceptual
primitive surface of this extension area; treat `doc/spec/vector-core.md` as where the code actually lives
for the collection-supertype pieces.

New, parallel collection types that carry the same vector-search behavior Vector Core gives individual
objects, plus a native knowledge-graph type. These are what Persistence Bridge maps onto a store and what
Completion Fabric consumes via `toContext()`.

## Types

| Type | Description |
|---|---|
| `JavAIList<T>` / `JavAISet<T>` / `JavAIMap<K,V>` | Cosine-similarity-aware standard-collection replacements. New types, not retrofits — `java.util` cannot be modified from outside the JDK. |
| `KnowledgeGraph<N, E>` | Native graph type: nodes and edges plus hybrid pattern-match + similarity queries in one call. |
| `VectorIndex<T>` | Bare similarity-search container for cases that don't need full graph semantics. |

## `JavAISortable<T>`, and exactly what extends it

```java
interface JavAISortable<T> {
    JavAIList<T> sortByCosineDistance(EmbeddingVector reference);   // ascending distance = descending similarity
}
```

`JavAIList<T>` and `JavAISet<T>` explicitly extend **both** their standard-library counterpart and
`JavAISortable<T>` — they are strict supersets of `java.util.List<T>`/`Set<T>`, usable anywhere existing
code expects one, not replacements for them. `JavAIMap<K,V>` implements `JavAISortable<V>`, not
`JavAISortable<K>` or `JavAISortable<Map.Entry<K,V>>`: keys are typically identifiers with no embedding of
their own, so ranking and `centroid()` operate over values.

| Type | Extends |
|---|---|
| `JavAIList<T>` | `java.util.List<T>`, `JavAISortable<T>`, `JavAIVectorizable` |
| `JavAISet<T>` | `java.util.Set<T>`, `JavAISortable<T>`, `JavAIVectorizable` |
| `JavAIMap<K,V>` | `java.util.Map<K,V>`, `JavAISortable<V>`, `JavAIVectorizable` |

`sortByCosineDistance()` isn't the only addition. Each concrete type also gets a small set of
similarity-aware convenience methods — thin wrappers over Vector Core's `similarityTo()`, not new
mechanism:

| Method | On | Purpose |
|---|---|---|
| `nearestN(EmbeddingVector, int n): JavAIList<T>` | List / Set | Top-N by similarity without materializing a full sort |
| `filterByMinSimilarity(EmbeddingVector, double threshold): JavAIList<T>` | List / Set | Threshold cutoff |
| `centroid(): EmbeddingVector` | List / Set / Map | Mean vector of all elements — treat the whole collection as one point |

```java
interface JavAIList<T> extends java.util.List<T>, JavAISortable<T>, JavAIVectorizable {
    JavAIList<T> nearestN(EmbeddingVector reference, int n);
    JavAIList<T> filterByMinSimilarity(EmbeddingVector reference, double threshold);
    EmbeddingVector centroid();
}

interface JavAISet<T> extends java.util.Set<T>, JavAISortable<T>, JavAIVectorizable {
    JavAIList<T> nearestN(EmbeddingVector reference, int n);
    JavAIList<T> filterByMinSimilarity(EmbeddingVector reference, double threshold);
    EmbeddingVector centroid();
}

interface JavAIMap<K, V> extends java.util.Map<K, V>, JavAISortable<V>, JavAIVectorizable {
    EmbeddingVector centroid();
}
```

## `KnowledgeGraph<N, E>`, in full

The type every graph-shaped collection in this document (in-memory or persisted, via Persistence Bridge's
Neo4j shim) ultimately is:

```java
public interface KnowledgeGraph<N extends JavAIGraphNode, E extends JavAIEdge>
        extends JavAIVectorizable, JavAISortable<N> {

    // -- construction / access --
    void addNode(N node);
    void addEdge(N from, N to, E edge);
    JavAISet<N> nodes();
    JavAISet<E> edges(N from, N to);
    JavAISet<N> neighbors(N node);

    // -- pattern-match traversal, no similarity involved --
    JavAIList<N> match(Class<N> type, Predicate<N> filter);

    // -- hybrid similarity + structure query --
    // The return type is the key design point: a SubgraphResult IS a
    // KnowledgeGraph, not a bare list of hits. Nodes/edges that matched
    // stay queryable, sortable, and narrowable exactly like the graph
    // they came from.
    SubgraphResult<N, E> nearestSubgraph(EmbeddingVector reference, int k, int hops);

    // -- persistence-facing --
    KnowledgeGraph<N, E> persisted(JavAIRepository<N> backing);
}

// What nearestSubgraph() actually returns. Because it extends
// KnowledgeGraph<N, E>, every method above -- including nearestSubgraph()
// itself -- is callable again, directly on the result.
public interface SubgraphResult<N extends JavAIGraphNode, E extends JavAIEdge>
        extends KnowledgeGraph<N, E> {
    double scoreOf(N node);            // similarity score that earned this node its place
    int hopsFrom(N node, N origin);    // structural distance from the query origin
}
```

The consequence of `SubgraphResult` extending `KnowledgeGraph`: a query result can be queried again,
narrowing structure and similarity together across multiple hops, without ever dropping to a plain list:

```java
KnowledgeGraph<Article, RelatesTo> graph =
    JavAIPI.knowledgeGraph(Article.class, RelatesTo.class);

SubgraphResult<Article, RelatesTo> broad =
    graph.nearestSubgraph(queryVector, 50, /* hops */ 3);

// broad IS a KnowledgeGraph -- narrow again without re-touching the graph it came from.
SubgraphResult<Article, RelatesTo> narrowed =
    broad.nearestSubgraph(queryVector, 8, /* hops */ 1);

JavAIList<Article> ranked = narrowed.sortByCosineDistance(queryVector);
```

## Related interfaces and annotations

| Element | Kind | Purpose |
|---|---|---|
| `JavAIGraphNode` / `JavAIEdge` | Interfaces | Native graph node/edge contract for `KnowledgeGraph` participants |
| `@JavAIGraphNode` / `@JavAIEdge` | Annotations (class / record) | Declares knowledge-graph participation — same name as the interface it causes to be implemented |

## Which one do I reach for?

See whitepaper §6.7 for the full comparison table (`JavAIList` vs. `VectorIndex` vs. `KnowledgeGraph`
vs. a JPA-style repository query) — reproduce it as module-level documentation once `javai-collections`
has a real package to attach it to.
