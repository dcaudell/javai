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

**Same reasoning, one more pair of types:** `Contextable` and `PromptContext` — Completion Fabric's
RAG-integration primitives — also live here rather than in `javai-completion`. `Contextable.toContext(...)`
references `PromptContext`, and `JavAIList`/`JavAISet`/`JavAIMap` all implement `Contextable`, so both types
have to live wherever those three collection interfaces do, or this module would need an illegal reverse
dependency on `javai-completion`. See "`Contextable`/`PromptContext`" below.

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
| `Contextable` | Interface | `toContext(PromptContext): String` — anything that can render itself as prompt material; `JavAIList`/`Set`/`Map` implement it, delegating per-element |
| `PromptContext` | Record | An ordered bag of `Contextable` entries, assembled into one String on demand — see below |
| `ContextableObject<T>` | Record | Wraps an arbitrary object as a `Contextable` via GSON's default marshalling |

## `Contextable`/`PromptContext`

`PromptContext` is Completion Fabric's informing-material primitive — `javai-completion`'s `CompletionRequest`
carries one — but it lives here because `JavAIList`/`JavAISet`/`JavAIMap` implement `Contextable`, whose
method signature references `PromptContext` (see the module-placement note above).

- **Assembly is entry-by-entry, not a flat string.** `PromptContext.toString()`/`toContext(...)` renders
  each entry via `entry.toContext(this)`, joined with `"\n\n"`.
- **`sourceLabel`**, if set, prints once as a `"[Source: ...]\n"` header before all entries; unset (the
  default) means no header at all. Never per-entry.
- **`maxLength` is opt-in** (`null` = unbounded, the default). This is what lets a caller partition a
  context window across several named regions, each its own budgeted `PromptContext` — the budget is
  honored identically whether that context is handed straight to a completion or nested as one `Contextable`
  entry inside a larger, outer `PromptContext` (a nested `PromptContext` ignores the outer context's own
  config and renders using its own entries/label/budget — see its class javadoc for why).
- **No partial entries.** Assembly stops at the first entry whose rendered text would overflow the
  remaining budget (preserving list order — entries are typically already relevance-sorted, e.g. via
  `nearestN`); nothing is emitted to signal that omission happened.
- **`defaultMarshall(Object)` uses GSON**, not Jackson — this module takes no Jackson dependency of its own
  (only `javai-completion` sees Jackson at all, transitively via Spring AI). GSON's default reflective
  serialization has no cycle guard equivalent to this module's own `enterSummaryComputation`/
  `exitSummaryComputation` (used by `summaryVector()`'s recursive walk) — a self-referential or graph-shaped
  object passed into `ContextableObject` risks a stack overflow. This is why `KnowledgeGraph`/
  `SubgraphResult`/`VectorIndex` (`javai-collections`) do not implement `Contextable` yet — deferred pending
  a cycle-aware design, not silently dropped.
- **`defaultMarshall`'s default `Gson` only serializes fields annotated
  `@`[`dev.xtrafe.javai.annotations.PromptContext`](../javai-annotations/src/main/java/dev/xtrafe/javai/annotations/PromptContext.java)**
  — an allowlist, not a blocklist. Every other field is excluded, including a woven `@JavAIVectorizable`
  class's internal bookkeeping (a cached `EmbeddingVector`, dirty-tracking state) — those must never leak
  into a prompt just because an object got wrapped in `ContextableObject`, and an allowlist is the only way
  to guarantee that without hand-maintaining a blocklist of "things that look internal." A caller that wants
  GSON's ordinary, unfiltered reflection can still get it via `PromptContext.Builder.gson(Gson)` with a
  plain `new Gson()` (or one with its own `ExclusionStrategy`) — the annotation-filtered default is an
  opinionated starting point, not the only option. Note this annotation shares its simple name with
  `PromptContext` the record itself — different packages, so it compiles, but code inside this very class
  can't `import` it (its own type name is already in scope) and references it fully-qualified instead; not
  an oversight, documented on both types.

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
- `JavAIVectorizable`/`JavAIDirtyTracking`/`JavAISortable`/`JavAIList`/`JavAISet`/`JavAIMap` — real
  contracts, implemented for real. `javai-agent`'s weaver implements `JavAIVectorizable`/
  `JavAIDirtyTracking` on any `@JavAIVectorizable`-annotated class at load time (see that module's README);
  this module itself owns three concrete, hand-written (not woven) collection types implementing
  `JavAIList`/`JavAISet`/`JavAIMap` directly — `JavAIArrayList`, `JavAILinkedHashSet`, `JavAILinkedHashMap`
  — each with real `vector()`/`summaryVector()` (centroid/decay-weighted sum via `CollectionVectorSupport`,
  shared with `javai-collections`' `KnowledgeGraph`) and dirty-tracking wired through their mutators.
- `JavAIRuntime` — the back-edge propagation/lazy-recompute engine every woven `vector()`/`summaryVector()`/
  `query()` call delegates to: `propagateDirty`, `registerDependency`, cycle-safe `summaryVector`
  computation, and the reflective, hierarchy-aware (walks superclasses) `query()` graph walk, gated by
  `@SearchVisibility` at both the field (traversal) and type (matching) level.
- `JavAIEmbeddingProvider` has two real implementations, not just the interface: `OllamaEmbeddingProvider`
  and `TextEmbeddingsInferenceProvider` (Hugging Face's TEI) — both plain `java.net.http.HttpClient`
  clients, no JSON library dependency. `LocalEmbeddingDefaults` picks between them per host OS (see its own
  javadoc for why: a confirmed TEI/Candle CPU bug on this project's reference model).
- The lifecycle state machine and summary-vector formula above are exactly what's implemented and tested:
  state-transition tests, cycle-safety tests, and duplicate-reference-stacking tests all exist and pass,
  both hermetically (a fake embedding provider) and against real embeddings in `e2e-client-test`.
- `Contextable`/`ContextableObject`/`PromptContext` — real and tested (`PromptContextTest`,
  `ContextableCollectionsTest`, plus delegation tests in `JavAIArrayListTest`/`JavAILinkedHashSetTest`/
  `JavAILinkedHashMapTest`): stop-at-first-overflow budgeted assembly, silent omission, unbounded-by-default,
  `sourceLabel` print-once-if-set, nested `PromptContext` ignoring the outer context's config, `merge()`,
  `withMaxLength()`, GSON-backed `defaultMarshall()`, the `@PromptContext` field-allowlist filter (including
  a real check that an `EmbeddingVector`-typed field is excluded unless explicitly annotated), and the
  custom-`Gson` escape hatch restoring unfiltered serialization. `e2e-client-test`'s own `CompletionE2ETest`
  additionally proves this against a real, woven `@JavAIVectorizable` class, not just hand-written test
  POJOs. Not yet implemented: `Contextable` on `KnowledgeGraph`/`SubgraphResult`/`VectorIndex`
  (`javai-collections`) — see the
  "`Contextable`/`PromptContext`" section above for why.
