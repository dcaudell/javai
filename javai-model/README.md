# javai-model

Extension area: **Vector Core** (part 2 of 2 — see [`javai-vector`](../javai-vector/README.md) for the
other half), plus the physical home for Vector Collections' interfaces and Completion Fabric's
RAG-integration primitives. Whitepaper: §4.1–§4.3, §5.1, §5.4, §6.1–§6.4 (Vector Core/Collections); §5.3,
§7.1–§7.3 (Completion Fabric's RAG half). Full detail: [`doc/spec/vector-core.md`](../doc/spec/vector-core.md),
[`doc/spec/vector-collections.md`](../doc/spec/vector-collections.md),
[`doc/spec/completion-fabric.md`](../doc/spec/completion-fabric.md).

Depends on `javai-annotations` + `javai-vector`. The formalized home for whatever has to live physically
upstream of `javai-collections`/`javai-completion` for compile-order reasons, rather than because it's
vector/embedding core (that's `javai-vector`).

**Why this module exists, precisely:** `JavAIVectorizable.query()` returns `JavAIList<T>`, and `JavAIList`
in turn `extends JavAIVectorizable` right back. Two types with a genuine mutual reference can never live in
separate modules without an illegal cycle or an API change — confirmed this isn't avoidable by extracting
`query()` into its own interface: `JavAIRuntime.summaryVector()`'s cycle-safe walk checks
`value instanceof JavAIVectorizable` to decide whether a `@Summary`-annotated collection field should
contribute its own `summaryVector()` (real, tested behavior — `Article.comments`, a `JavAIList`, is exactly
this case), so `JavAIList` needs the *whole* `JavAIVectorizable` contract, not just `query()`. There's no
clean seam here; the two types are irreducibly one unit. Three groups of types end up here for this reason
or one just like it:

1. **`JavAIVectorizable` (the contract) and `JavAIRuntime` (the engine)** — described in the whitepaper as
   core "Vector Core," physically here because of the `JavAIList` entanglement above.
2. **`JavAISortable`, `JavAIList`/`Set`/`Map`, and their concrete implementations
   (`JavAIArrayList`/`JavAILinkedHashSet`/`JavAILinkedHashMap`) plus `CollectionVectorSupport`** —
   conceptually "Vector Collections" per `doc/spec/vector-collections.md`, physically here for the same
   reason: `javai-collections` depends on this module, not the reverse, and `KnowledgeGraph`/
   `SubgraphResult`/`VectorIndex` (which actually belong in `javai-collections`) depend on the types here.
3. **`Contextable`, `PromptContext`, `ContextableObject`, and the package-private `PlainTextEntry`** —
   conceptually "Completion Fabric"'s RAG-integration primitives per `doc/spec/completion-fabric.md`,
   physically here because `Contextable.toContext(PromptContext)` references `PromptContext`, and
   `JavAIList`/`Set`/`Map` (group 2, already here) all implement `Contextable` — so both types must live
   wherever those three collection interfaces do, or this module would need an illegal reverse dependency
   on `javai-completion`.

The whitepaper's seven-extension-area taxonomy is conceptual, not a physical module map — this module is
the accumulated evidence that three separate areas each have a piece that can't physically live where their
own dedicated module lives, for the identical structural reason each time. See this module's own
package-info.java and [`doc/module-dependency-graph.md`](../doc/module-dependency-graph.md) for the full
picture.

## The `JavAIVectorizable` contract

`javaic`/the weaver (`javai-substrate`) implements every method below on any class annotated
`@JavAIVectorizable`. You never write `implements JavAIVectorizable` by hand — the annotation alone
triggers full codegen.

```java
public interface JavAIVectorizable {

    EmbeddingVector vector();
    EmbeddingVector concatenatedTextVector();
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
| `JavAIRuntime` | Static engine | Back-edge propagation, per-field mode-aware recompute, the `query()` graph walk, whole-subgraph persistence locking — every woven method delegates here |
| `EmbeddingConsistencyMode` | Enum | `IMMEDIATE_CONSISTENCY` (default) / `EVENTUAL_CONSISTENCY` / `COALESCED_CONSISTENCY` — see "Embedding concurrency model" below |
| `EmbeddingFailureMode` | Enum | `THROW` (default) / `RETURN_NULL` — paired blocking/background failure behavior, see below |
| `VectorizableString` | Concrete implementation | Standalone, directly usable immutable `String` box implementing the full `JavAIVectorizable` contract — the boxing idea below, made concrete and public |
| `JavAISortable<T>` | Interface | `sortByCosineDistance(EmbeddingVector): JavAIList<T>` |
| `JavAIList<T>` / `JavAISet<T>` / `JavAIMap<K,V>` | Interfaces | Extend both their `java.util` counterpart and `JavAISortable`/`JavAIVectorizable`/`Contextable`; add `nearestN`, `filterByMinSimilarity`, `centroid()` |
| `JavAIArrayList` / `JavAILinkedHashSet` / `JavAILinkedHashMap` | Concrete collections | Real `ArrayList`/`LinkedHashSet`/`LinkedHashMap` subclasses implementing the above by hand, not woven |
| `CollectionVectorSupport` | Static utility | Shared `vector()`/`summaryVector()` arithmetic for the three concrete collections above |
| `Contextable` | Interface | `toContext(PromptContext): String` — anything that can render itself as prompt material; `JavAIList`/`Set`/`Map` implement it, delegating per-element |
| `PromptContext` | Record, also fulfills `List<Contextable>` | An ordered bag of `Contextable` entries, assembled into one String on demand — see below |
| `ContextableObject<T>` | Record | Wraps an arbitrary object as a `Contextable` via GSON's default marshalling |

## `Contextable`/`PromptContext`

`PromptContext` is Completion Fabric's informing-material primitive — `javai-completion`'s `CompletionRequest`
carries one — but it lives here because `JavAIList`/`JavAISet`/`JavAIMap` implement `Contextable`, whose
method signature references `PromptContext` (see the module-placement note above).

- **Fulfills the full `List<Contextable>` contract**, delegating every method to its own entries — so a
  whole `List<Contextable>` (including another `PromptContext`) can be added directly via `addAll(...)` to
  an already-built instance, not just supplied at construction via `Builder.entries(...)`.
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
- **`defaultMarshall(Object)` uses GSON**, not Jackson — neither this module nor `javai-vector` takes a
  Jackson dependency of its own (only `javai-completion` sees Jackson at all, transitively via Spring AI).
  GSON's default reflective serialization has no cycle guard equivalent to this module's own
  `JavAIRuntime.enterSummaryComputation`/`exitSummaryComputation` (used by `summaryVector()`'s recursive
  walk) — a self-referential or graph-shaped object passed into `ContextableObject` risks a stack overflow.
  This is why `KnowledgeGraph`/`SubgraphResult`/`VectorIndex` (`javai-collections`) do not implement
  `Contextable` yet — deferred pending a cycle-aware design, not silently dropped.
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

Every `JavAIVectorizable` instance carries **two independent staleness flags** — `FieldDirty` and
`SummaryDirty`. `vector()` itself is no longer cached at the object level (it's a purely compositional
centroid of each field's own cache, see "Embedding concurrency model" below), so `FieldDirty`'s sole
remaining job is gating `summaryVector()`'s own base-term recompute:

```
This object:              Clean --setter mutates a @Vectorize field--> FieldDirty
                           FieldDirty --next read of summaryVector()--> (consumed as its own base-term
                                                                          staleness signal)

Each live dependent,       SummaryDirty <--markDirty() walks dependents(), stops at any node already dirty-- FieldDirty
transitively:              SummaryDirty --next read of summaryVector()/toContext()--> SummaryRecomputing
                           SummaryRecomputing --summary computed--> Clean
```

- **Clean** — every `@Vectorize` field's own cache slot is current and `summaryVector()` reflects it.
- **FieldDirty** — a `@Vectorize` field was mutated via a woven setter; consumed only by `summaryVector()`,
  which always includes `vector(self)` as its own base term.
- **SummaryDirty** — a reachable descendant went dirty and the back-edge walk (`JavAIRuntime.propagateDirty`)
  reached this object; only `summaryVector()` is stale.
- **SummaryRecomputing** — transient state entered by the next *read* of `summaryVector()`, never by the
  write — recomputation is a read-time side effect, never eager, for this pair of flags specifically (each
  `@Vectorize` field's own recompute timing is mode-dependent — see below).

An object can be FieldDirty and SummaryDirty simultaneously; each clears independently on its own
corresponding read. The propagation walk stops the instant it reaches a node already marked SummaryDirty —
the cycle-safety guarantee. There is no global generation counter *for these two flags* — that's separate
from the per-field generation counters described next, which solve a different problem.

```java
// What the developer writes -- and all they ever see:
public void setBody(String body) {
    this.body = body;
}

// What the weaver actually ships:
public void setBody(String body) {
    this.body = body;
    JavAIRuntime.vectorizeFieldMutated(this, "body", body);
}
```

## Embedding concurrency model

Full contract in `doc/spec/vector-core.md`'s "Embedding concurrency model" section — summary here:

- Each `@Vectorize` field (and `concatenatedTextVector()`) has its own `VectorCacheSlot` (`javai-vector`),
  keyed by field name in `DirtyTrackingSupport`. `vector()` recombines them fresh on every call; it has no
  cache of its own.
- **Reassigning a field to its current value is a complete no-op**, under every mode — checked via
  `Objects.equals` against the value captured just before the assignment (`@Advice.OnMethodEnter`, since by
  the time an exit-only advice would see it, the assignment would already have overwritten it). No
  dirty-marking, no generation bump, no dispatch, no lock touch.
- **`EmbeddingConsistencyMode.IMMEDIATE_CONSISTENCY`** (default): mutation never triggers computation; a
  read of a dirty slot blocks and recomputes inline, snapshotting the field's current value, while holding
  the whole object's lock (`DirtyTrackingSupport.objectLock()`) for the duration — every setter, every mode,
  briefly takes that same lock around its own bookkeeping, so a concurrent setter or getter on this object
  genuinely waits rather than racing. Every request is accurate to the field's state at the time of the
  request, even under races.
- **`EVENTUAL_CONSISTENCY`**: mutation eagerly dispatches a background recompute; a read of an
  already-computed slot returns the last known-good value immediately and dispatches its own background
  recompute only if nothing is already outstanding for the current generation (so a burst of concurrent
  readers of a stale value shares at most one real `embed()` call). A slot's very first computation always
  blocks regardless of mode.
- **`COALESCED_CONSISTENCY`**: mutation is eager and non-blocking, exactly like `EVENTUAL_CONSISTENCY`. A
  read of a dirty slot **blocks**, joining whatever computation is already outstanding rather than starting
  its own (dispatching a fresh one to join only if nothing's outstanding) — cheaper than
  `IMMEDIATE_CONSISTENCY` under concurrent readers, but every reader still gets a real, blocking answer
  rather than `EVENTUAL_CONSISTENCY`'s possibly-stale immediate one.
- **`JavAIRuntime.configureMaxConcurrentEmbeddingCalls(int)`** (default 8) bounds concurrent `embed()` calls
  globally, blocking and background alike — rapid mutation degrades to "slower," never unbounded parallel
  HTTP calls.
- **`EmbeddingFailureMode.THROW`** (default) rethrows a blocking failure (including to every
  `COALESCED_CONSISTENCY` joiner) and keeps serving the last known-good value on a background failure;
  **`RETURN_NULL`** returns/serves `null` instead either way. A failed attempt never marks a slot clean,
  under any mode.
- **`JavAIRuntime.runWithSubgraphLockedForPersistence(root, action)`** locks every reachable
  `JavAIVectorizable`'s own per-object lock (fixed global order, deadlock-free — the same lock
  `IMMEDIATE_CONSISTENCY` uses) and forces every `fieldVector()`/`concatenatedTextVector()` read on the
  calling thread to block for an accurate value for the duration of `action`, regardless of the ambient
  mode — what `javai-persistence`'s backends wrap every `save()` in, so the database never sees a stale
  vector. Because every setter takes this same lock (see above), the subgraph is genuinely frozen against
  mutation for the duration too, not just protected on the read side.

All of the above is configured once, ideally at startup alongside the embedding provider — undefined
behavior on a mid-flight toggle.

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

- `JavAIVectorizable`/`JavAISortable`/`JavAIList`/`JavAISet`/`JavAIMap` — real contracts, implemented for
  real. `javai-substrate`'s weaver implements `JavAIVectorizable`/`JavAIDirtyTracking` on any
  `@JavAIVectorizable`-annotated class at load time (see that module's README); this module itself owns
  three concrete, hand-written (not woven) collection types implementing `JavAIList`/`JavAISet`/`JavAIMap`
  directly — `JavAIArrayList`, `JavAILinkedHashSet`, `JavAILinkedHashMap` — each with real
  `vector()`/`summaryVector()` (centroid/decay-weighted sum via `CollectionVectorSupport`, shared with
  `javai-collections`' `KnowledgeGraph`) and dirty-tracking wired through their mutators.
- `JavAIRuntime` — the back-edge propagation/lazy-recompute engine every woven `vector()`/`summaryVector()`/
  `query()` call delegates to: `propagateDirty`, `registerDependency`, cycle-safe `summaryVector`
  computation, and the reflective, hierarchy-aware (walks superclasses) `query()` graph walk, gated by
  `@SearchVisibility` at both the field (traversal) and type (matching) level.
- The lifecycle state machine and summary-vector formula above are exactly what's implemented and tested:
  state-transition tests, cycle-safety tests, and duplicate-reference-stacking tests all exist and pass,
  both hermetically (a fake embedding provider) and against real embeddings in `e2e-client-test`.
- The embedding concurrency model above is real and tested: `EmbeddingConcurrencyTest` proves all three
  consistency modes never serve a vector that doesn't match some genuinely-assigned field value under real
  concurrent races, proves convergence to the final mutation once activity settles, proves the
  `configureMaxConcurrentEmbeddingCalls` gate under rapid mutation, proves both `EmbeddingFailureMode`s'
  blocking/background behavior including the opportunistic-retry guarantee, proves the no-op-reassignment
  skip never triggers a new `embed()` call under any mode, proves `IMMEDIATE_CONSISTENCY`'s setter genuinely
  blocks while a concurrent read is mid-computation, and proves `COALESCED_CONSISTENCY`'s blocking joiners
  share exactly one real computation (not one each) and see the same THROW/RETURN_NULL outcome the owning
  computation produced. `PersistenceSupportTest` proves `runWithSubgraphLockedForPersistence`'s
  forced-accuracy override doesn't leak past its own block, and proves an ordinary setter (any mode) genuinely
  waits out an in-progress flush. `EmbeddingConsistencyBenchmark` is a separate, hand-run comparative
  profiling harness (tagged `"performance"`, excluded from the default `mvn test` run — see the root
  `pom.xml`) reporting throughput and read-latency percentiles for all three modes side by side under a
  representative concurrent workload. `VectorizableString` has its own dedicated test suite
  (`VectorizableStringTest`).
- `Contextable`/`ContextableObject`/`PromptContext` — real and tested (`PromptContextTest`,
  `PromptContextListContractTest`, `ContextableCollectionsTest`, plus delegation tests in
  `JavAIArrayListTest`/`JavAILinkedHashSetTest`/`JavAILinkedHashMapTest`): stop-at-first-overflow budgeted
  assembly, silent omission, unbounded-by-default, `sourceLabel` print-once-if-set, nested `PromptContext`
  ignoring the outer context's config, `merge()`, `withMaxLength()`, the full `List<Contextable>` delegation
  contract, GSON-backed `defaultMarshall()`, the `@PromptContext` field-allowlist filter (including a real
  check that an `EmbeddingVector`-typed field is excluded unless explicitly annotated), and the custom-`Gson`
  escape hatch restoring unfiltered serialization. `e2e-client-test`'s own `CompletionE2ETest` additionally
  proves this against a real, woven `@JavAIVectorizable` class, not just hand-written test POJOs. Not yet
  implemented: `Contextable` on `KnowledgeGraph`/`SubgraphResult`/`VectorIndex` (`javai-collections`) — see
  the module-placement note above for why.
