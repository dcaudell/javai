# javai-collections

Extension area: **Vector Collections**. Whitepaper: §5.4, §6.4–§6.7. Full detail:
[`doc/spec/vector-collections.md`](../doc/spec/vector-collections.md).

Depends on `javai-vector` + `javai-model` (Vector Core). New, parallel collection types that carry the same
vector-search behavior Vector Core gives individual objects, plus a native knowledge-graph type. These are
what Persistence Bridge maps onto a store and what Completion Fabric consumes via `toContext()`.

**Module-placement note (discovered while scaffolding, not in the whitepaper):** `JavAISortable<T>`,
`JavAIList<T>`, `JavAISet<T>`, and `JavAIMap<K,V>` — conceptually part of this extension area — physically
live in `javai-model`, not here. Reason: `JavAIVectorizable.query()` returns `JavAIList<T>`, and this
module depends on `javai-model`, not the reverse; keeping `JavAIList` here would create a circular module
dependency. What's actually in `javai-collections` is `KnowledgeGraph`, `SubgraphResult`, `VectorIndex`, and
the `JavAIGraphNode`/`JavAIEdge` interfaces — the types that depend on `javai-model`'s (and `javai-vector`'s),
not the reverse. See `javai-model/README.md` for where the collection-supertype pieces actually live.

## Types

| Type | Description | Lives in |
|---|---|---|
| `JavAIList<T>` / `JavAISet<T>` / `JavAIMap<K,V>` | Cosine-similarity-aware standard-collection replacements | `javai-model` (see note above) |
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

    // -- persistence-facing: NOT part of the interface yet. The whitepaper's own signature is
    // `persisted(JavAIRepository<N> backing)`, but JavAIRepository belongs to Persistence Bridge
    // (javai-persistence), which depends on this module, not the reverse -- see package-info.
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
| `JavAIGraphNode` / `JavAIEdge` | Interfaces (this module) | Native graph node/edge contract for `KnowledgeGraph` participants -- **declare directly**, e.g. `class Article implements JavAIGraphNode`; see below |
| `@JavAIGraphNode` / `@JavAIEdge` | Annotations (`javai-annotations`, class/record) | Documentation/intent-signaling only -- **not woven**, carries no runtime behavior |

### Why `@JavAIGraphNode`/`@JavAIEdge` are never woven

Two independent reasons, either one sufficient on its own:

1. **Both interfaces are empty markers.** Weaving exists to save hand-writing method bodies
   (`@JavAIVectorizable` implements a dozen-plus real methods this way); an interface with zero methods
   has no bodies to save. `class Article implements JavAIGraphNode` costs exactly what
   `@JavAIGraphNode class Article` would, with none of the ByteBuddy machinery behind it.
2. **Weaving them would need `javai-substrate` to depend on `javai-collections`**, to reference these
   interfaces for `.implement(...)`. That's backwards from the documented build order (`javai-substrate`
   depends on `javai-annotations` + `javai-vector` + `javai-model`; this module depends on those two, which
   in turn assume `javai-substrate`'s weaving already works). The only way around it would be relocating
   `JavAIGraphNode`/`JavAIEdge` into `javai-model`, mirroring `JavAIList`/`Set`/`Map`'s own placement
   precedent above -- pure churn for annotations that don't need it.

## Which one do I reach for?

See whitepaper §6.7 for the full comparison table (`JavAIList` vs. `VectorIndex` vs. `KnowledgeGraph` vs. a
JPA-style repository query).

## What's actually implemented

`JavAIGraphNode`/`JavAIEdge` (empty marker interfaces, declared directly, never woven -- see above).
`VectorIndex`/`JavAIVectorIndex` (hand-written, reuses `javai-model`'s `CollectionVectorSupport`).
`KnowledgeGraph`/`SubgraphResult` via `JavAIKnowledgeGraph`/`JavAIKnowledgeSubgraphResult` (hand-written,
not woven -- concrete, user-instantiated containers like `javai-model`'s `JavAIArrayList`): `addNode`/
`addEdge`/`nodes`/`edges`/`neighbors`/`match` for construction and pattern-match traversal,
`nearestSubgraph` for the hybrid similarity + hop-expansion query (BFS from each of the k nearest nodes,
tracking per-origin hop counts for `hopsFrom`), `vector()`/`summaryVector()` over the graph's own nodes
(reusing `CollectionVectorSupport`, same as `JavAIArrayList`), and dependents wiring so mutating a node
propagates dirty up through the graph. `SubgraphResult` gets "narrow again" for free by extending
`JavAIKnowledgeGraph` rather than reimplementing it. All covered by hermetic tests (`JavAIVectorIndexTest`,
`JavAIKnowledgeGraphTest`) using a fake embedding provider, no Docker required.

`persisted(JavAIRepository<N> backing)` is not part of the interface yet -- see the code excerpt above and
package-info for why.
