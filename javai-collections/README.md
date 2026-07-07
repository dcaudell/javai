# javai-collections

Extension area: **Vector Collections**. Whitepaper: §5.4, §6.4–§6.7. Full detail:
[`doc/spec/vector-collections.md`](../doc/spec/vector-collections.md).

Depends on `javai-runtime` (Vector Core). New, parallel collection types that carry the same
vector-search behavior Vector Core gives individual objects, plus a native knowledge-graph type. These are
what Persistence Bridge maps onto a store and what Completion Fabric consumes via `toContext()`.

**Module-placement note (discovered while scaffolding, not in the whitepaper):** `JavAISortable<T>`,
`JavAIList<T>`, `JavAISet<T>`, and `JavAIMap<K,V>` — conceptually part of this extension area — physically
live in `javai-runtime`, not here. Reason: `JavAIVectorizable.query()` returns `JavAIList<T>`, and this
module depends on `javai-runtime`, not the reverse; keeping `JavAIList` here would create a circular module
dependency. What's actually in `javai-collections` is `KnowledgeGraph`, `SubgraphResult`, `VectorIndex`, and
the `JavAIGraphNode`/`JavAIEdge` interfaces — the types that depend on `javai-runtime`'s, not the reverse.
See `javai-runtime/README.md` for where the collection-supertype pieces actually live.

## Types

| Type | Description | Lives in |
|---|---|---|
| `JavAIList<T>` / `JavAISet<T>` / `JavAIMap<K,V>` | Cosine-similarity-aware standard-collection replacements | `javai-runtime` (see note above) |
| `KnowledgeGraph<N, E>` | Native graph type: nodes and edges plus hybrid pattern-match + similarity queries in one call | here |
| `VectorIndex<T>` | Bare similarity-search container for cases that don't need full graph semantics | here |

## `KnowledgeGraph<N, E>`, in full

The type every graph-shaped collection in this project (in-memory or persisted, via Persistence Bridge's
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
| `JavAIGraphNode` / `JavAIEdge` | Interfaces (this module) | Native graph node/edge contract for `KnowledgeGraph` participants |
| `@JavAIGraphNode` / `@JavAIEdge` | Annotations (`javai-annotations`, class/record) | Declares knowledge-graph participation — same name as the interface it causes to be implemented |

## Which one do I reach for?

See whitepaper §6.7 for the full comparison table (`JavAIList` vs. `VectorIndex` vs. `KnowledgeGraph` vs. a
JPA-style repository query) — reproduce it here once the types below actually have implementations to
document.

## What's actually implemented

`JavAIGraphNode` and `JavAIEdge` exist as real, empty marker interfaces. `KnowledgeGraph`, `SubgraphResult`,
and `VectorIndex` are not written yet — only specified above and in `doc/spec/vector-collections.md`.
`DependencyWiringTest` proves the classpath (this module + `javai-runtime`, including the `JavAIList`
relocation) resolves correctly; it doesn't exercise any graph logic.
