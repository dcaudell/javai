# javai-runtime

Extension area: **Vector Core**. Whitepaper: §4.1–§4.3 (mechanism), §4.5 (embedding generation), §5.1
(primitives), §6.1–§6.3 (worked examples). Full detail: [`doc/spec/vector-core.md`](../doc/spec/vector-core.md).

Depends on `javai-annotations` only. The area everything else is built on: computing an object's embedding,
keeping it current as the object mutates, and searching an object graph by similarity.

**Module-placement note (discovered while scaffolding, not in the whitepaper):** `JavAISortable<T>`,
`JavAIList<T>`, `JavAISet<T>`, and `JavAIMap<K,V>` physically live here, not in `javai-collections`, even
though the whitepaper and `doc/spec/vector-collections.md` discuss them as part of the Vector Collections
extension area. Reason: `JavAIVectorizable.query()` returns `JavAIList<T>`, and `javai-collections` depends
on `javai-runtime`, not the reverse — putting `JavAIList` in `javai-collections` would create a circular
module dependency. The extension-area taxonomy is conceptual; this is the compile-order-correct physical
split. `javai-collections` holds `KnowledgeGraph`, `SubgraphResult`, and `VectorIndex` — the types that
depend on what's here, not the reverse.

## The `JavAIVectorizable` contract

`javaic`/the weaver (`javai-agent`) implements every method below on any class annotated
`@JavAIVectorizable`. You never write `implements JavAIVectorizable` by hand — the annotation alone
triggers full codegen.

```java
public interface JavAIVectorizable {

    EmbeddingVector vector();
    EmbeddingVector summaryVector();

    double similarityTo(JavAIVectorizable other);
    double similarityTo(EmbeddingVector reference);

    // Search this object's reachable graph for instances of `type`,
    // ranked by similarity to `reference`. maxDepth defaults to
    // unbounded-but-cycle-safe: descend until a loop or a leaf.
    <T> JavAIList<T> query(EmbeddingVector reference, Class<T> type);
    <T> JavAIList<T> query(EmbeddingVector reference, Class<T> type, int maxDepth);

    // Dynamic counterpart to the per-field bodyVector()-style accessors
    // synthesized for every @Vectorize field.
    EmbeddingVector fieldVector(String fieldName);
}
```

## Public API

| Element | Kind | Purpose |
|---|---|---|
| `JavAIVectorizable` | Interface (weaver-implemented) | Implemented automatically on any `@JavAIVectorizable` class |
| `EmbeddingVector` | Record | A versioned vector: `values`, `modelId`, `dims`, `computedAt` — not a bare `float[]` |
| `JavAISortable<T>` | Interface | `sortByCosineDistance(EmbeddingVector): JavAIList<T>` |
| `JavAIList<T>` / `JavAISet<T>` / `JavAIMap<K,V>` | Interfaces | Extend both their `java.util` counterpart and `JavAISortable`/`JavAIVectorizable`; add `nearestN`, `filterByMinSimilarity`, `centroid()` |
| `vector(): EmbeddingVector` | Method | The object's own canonical embedding, recomputed lazily if dirty |
| `summaryVector(): EmbeddingVector` | Method | Hierarchical embedding over the object's contained/referenced graph — see formula below |
| `fieldNameVector(): EmbeddingVector` | Method (synthesized per `@Vectorize` field) | E.g. a `body` field gets `bodyVector()` |
| `similarityTo(...): double` | Method | Cosine similarity; CPU or accelerated backend chosen via `invokedynamic` (Acceleration Substrate) |
| `query(EmbeddingVector, Class<T>[, int maxDepth]): JavAIList<T>` | Method | Search reachable graph for instances of `T`, ranked by similarity |
| `JavAISimilarityBackend` / `JavAIEmbeddingProvider` | SPI interfaces | Pluggable similarity engine and embedding-model provider |

## Object lifecycle state machine

Every `JavAIVectorizable` instance carries **two independent staleness flags**, not one:

```
This object:              Clean --setter mutates a @Vectorize field--> FieldDirty
                           FieldDirty --next read of vector()/similarityTo()/query()--> EmbeddingRecomputing
                           EmbeddingRecomputing --embedding computed--> Clean

Each live dependent,       SummaryDirty <--markDirty() walks dependents(), stops at any node already dirty-- FieldDirty
transitively:              SummaryDirty --next read of summaryVector()/toContext()--> SummaryRecomputing
                           SummaryRecomputing --summary computed--> Clean
```

- **Clean** — `vector()` and `summaryVector()` both reflect current field state; nothing pending.
- **FieldDirty** — a `@Vectorize` field was mutated via a woven setter; this object's own `vector()` is stale.
- **SummaryDirty** — a reachable descendant went dirty and the back-edge walk (`JavAIRuntime.propagateDirty`)
  reached this object; only `summaryVector()` is stale.
- **EmbeddingRecomputing** / **SummaryRecomputing** — transient states entered by the next *read*, never by
  the write — recomputation is a read-time side effect, never eager.

An object can be FieldDirty and SummaryDirty simultaneously; each clears independently on its own
corresponding read. The propagation walk stops the instant it reaches a node already marked SummaryDirty —
the cycle-safety guarantee. There is no global generation counter: each object's pair of flags is the
entire durable state.

```java
// What the developer writes -- and all they ever see:
public void setBody(String body) {
    this.body = body;
}

// What the weaver actually ships:
public void setBody(String body) {
    this.body = body;
    this.markDirty();
    JavAIRuntime.propagateDirty(this);
}
```

## Summary-vector formula

`summaryVector()` is a decay-weighted **recursive** average, not a flat average over "everything reachable":

```
summaryVector(obj) = normalize(
    1.0 * vector(obj)
    + decay * Sum over each @Summary-marked child c of obj: summaryVector(c)
)
```

Four deliberate design decisions (full derivation in `doc/spec/vector-core.md`):

1. **Exponential decay, not linear** — an architectural constraint: it's the only form computable
   recursively from each child's already-cached `summaryVector()`, which the lazy propagation model requires.
2. **Duplicate references stack additively; cycles stop** — falls out of the recursive formula for free;
   a repeated node on the same path is treated as a leaf.
3. **Weighted sum vs. weighted average don't change retrieval** — cosine similarity is scale-invariant.
4. **`@Summary` still gates inclusion; decay only controls distance.**

**Known open risk:** high-fan-in "hub" nodes can dominate aggregate weight (a TF-IDF-like problem), not yet
resolved. Proposed (**not Phase 0**) `@Summary` tuning parameters — `decay`, `maxStack`/`@SummaryStacking`,
`maxDepth`, `aggregation` (MEAN\|MAX\|ATTENTION), `edgeKind` (OWNERSHIP\|REFERENCE\|AGGREGATION) — are
specification proposals only; do not implement until there's a demonstrated need.

## What's actually implemented

- `EmbeddingVector` — real record with a compact constructor validating `dims == values.length` and a
  non-blank `modelId`. Tested (`EmbeddingVectorTest`): stores values/modelId, rejects mismatched dims,
  requires a modelId.
- `JavAIVectorizable`, `JavAISortable`, `JavAIList`, `JavAISet`, `JavAIMap` — interfaces only, no
  implementation. Nothing implements them yet; that's `javai-agent`'s weaving job, not started.
- The lifecycle state machine and summary-vector formula above are specified precisely enough to be written
  as tests directly (state-transition tests, cycle-safety tests, duplicate-reference-stacking tests) — do
  that alongside implementing the weaving spike in `javai-agent`, not as an afterthought.
