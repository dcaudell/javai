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

A plain, in-memory collection type, exactly as pure as `JavAIList`/`JavAISet`/`JavAIMap` -- it has zero
persistence awareness of its own, no `persisted(...)`-style method, and never performs I/O from any of its
own methods:

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

`JavAIKnowledgeGraph<N, E>` is the one concrete, hand-written implementation. It's declared as an ordinary
field on some owning `@Entity`/`@JavAIVectorizable` type, exactly like any other JavAI collection field --
never constructed via a repository or persistence-facing factory:

```java
class ResearchTopic implements JavAIVectorizable {
    KnowledgeGraph<Article, RelatesTo> graph = new JavAIKnowledgeGraph<>();
    // ...
}
```

Persistence Bridge treats that field the same way it treats a `JavAIArrayList`/`JavAILinkedHashSet`/
`JavAILinkedHashMap` field: an ordinary field its reflective mapper auto-detects and handles, Neo4j-only in
this phase (native multi-hop traversal plus a similarity index has no efficient equivalent to build on
Postgres/MongoDB yet) -- see doc/spec/persistence-bridge.md's "`KnowledgeGraph` fields: Neo4j-only" for the
full mapping story, including why the other two backends reject such a field clearly rather than silently.

The consequence of `SubgraphResult` extending `KnowledgeGraph`: a query result can be queried again,
narrowing structure and similarity together across multiple hops, without ever dropping to a plain list:

```java
KnowledgeGraph<Article, RelatesTo> graph = new JavAIKnowledgeGraph<>();
graph.addEdge(articleA, articleB, new RelatesTo("cites"));

SubgraphResult<Article, RelatesTo> broad =
    graph.nearestSubgraph(queryVector, 50, /* hops */ 3);

// broad IS a KnowledgeGraph -- narrow again without re-touching the graph it came from.
SubgraphResult<Article, RelatesTo> narrowed =
    broad.nearestSubgraph(queryVector, 8, /* hops */ 1);

JavAIList<Article> ranked = narrowed.sortByCosineDistance(queryVector);
```

## `VectorIndex<T>`, in full

The bare similarity-search container — no graph semantics, no `JavAIVectorizable` of its own, no dirty
tracking. What distinguishes it from `JavAIList`/`JavAISet` is that it is a lookup structure rather than a
node in the object graph, and that a realization of it may be *persistence-backed* rather than in memory
(`javai-tagging`'s two tag indexes are, and are the reason the interface earns its keep).

```java
public interface VectorIndex<T> extends JavAISortable<T> {

    void add(T item);
    boolean remove(T item);
    int size();

    JavAIList<T> nearestN(EmbeddingVector reference, int n);
    JavAIList<T> filterByMinSimilarity(EmbeddingVector reference, double threshold);

    // -- the similarity each hit was ranked on, not just the order (OMI-460) --
    List<Ranked<T>> nearestNRanked(EmbeddingVector reference, int n);

    // -- narrowing, which returns another VectorIndex and therefore chains (OMI-460) --
    VectorIndex<T> ofType(Collection<? extends Class<?>> candidateTypes);
    VectorIndex<T> ofType(Class<?>... candidateTypes);              // default over the above

    // -- the same query written as one call; defaults, so they cannot drift --
    JavAIList<T> nearestN(EmbeddingVector, int n, Collection<? extends Class<?>>);
    List<Ranked<T>> nearestNRanked(EmbeddingVector, int n, Collection<? extends Class<?>>);
}
```

### Narrowing chains; searching ends the chain

`ofType` returns a `VectorIndex`, not a result — the same design point `SubgraphResult extends
KnowledgeGraph` makes above. A narrowed index is still an index, so it narrows again and answers every
query the original did; a `JavAIList` appears only when a search is actually run:

```java
JavAIList<TaggableRef> albums = tagging.tagSimilarityIndex()
        .ofType(Album.class)
        .nearestN(reference, 20);
```

**Narrowing is not filtering the result, and this is the whole of why it exists.** Every realization applies
the candidate types *before* the top-N is chosen: "the nearest N of these types", never "those of the
nearest N that happen to be of these types". The second is what a caller is forced to approximate when an
index cannot be narrowed — draw some multiple and discard — and it is silently wrong past the edge of
whatever multiplier they guessed. It is the same ordering rule `NearestSpec` states for repository vector
search, in the collection that has no repository.

Three further rules, each stated because the alternative reading is plausible:

- **Exact runtime class, not assignability.** `ofType(Animal.class)` does not match a `Dog`. This matches
  `JavAITagRepository.taggedWith`/`rankedByTags`, which have taken candidate types on these terms since
  before narrowing existed, and it is the only rule a persistence-backed realization can answer without
  enumerating every loaded subtype of the class it was handed. Where an index holds *references* to
  instances rather than the instances themselves (`VectorIndex<TaggableRef>`), the type is the referenced
  instance's.
- **Naming no types matches nothing**, rather than meaning "don't narrow" — the same rule
  `taggedWith(tag, List.of())` already follows. Call the unnarrowed query to say "everything".
- **Narrowing an already-narrowed index intersects.** `ofType(A, B).ofType(A)` is `ofType(A)`;
  `ofType(A).ofType(B)` matches nothing.

A narrowed view is read-only (`add`/`remove` refuse): an item added through a filter it does not satisfy
would have to either vanish or falsify the filter.

**Narrowing by anything other than type is not in this phase (OMI-461).** A general
`matching(Predicate<? super T>)` would be exact for the in-memory realization and unanswerable for the
persistence-backed ones, which can only push a type list into a query — honouring it there would mean
fetching the whole index and filtering in the caller, which is the over-fetch this feature exists to remove,
moved inside the library where nobody can see it. So it would have to refuse on two of the three
realizations. Deferred rather than half-delivered.

## Related interfaces and annotations

| Element | Kind | Purpose |
|---|---|---|
| `JavAIGraphNode` / `JavAIEdge` | Interfaces | Native graph node/edge contract for `KnowledgeGraph` participants |
| `@JavAIGraphNode` / `@JavAIEdge` | Annotations (class / record) | Declares knowledge-graph participation — same name as the interface it causes to be implemented |

## Which one do I reach for?

See whitepaper §6.7 for the full comparison table (`JavAIList` vs. `VectorIndex` vs. `KnowledgeGraph`
vs. a JPA-style repository query) — reproduce it as module-level documentation once `javai-collections`
has a real package to attach it to.
