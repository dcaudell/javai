# Vector Core

Modules: `javai-vector` (pure vector/embedding functionality: `EmbeddingVector`, the CPU similarity backend,
embedding providers, dirty-tracking primitives — depends only on `javai-annotations`) and `javai-model`
(`JavAIVectorizable`/`JavAIRuntime`, plus the Vector Collections interfaces and Completion Fabric's RAG
primitives — depends on `javai-vector`; see below for why). Whitepaper: §4.1–§4.3 (mechanism), §4.5
(embedding generation), §5.1 (primitives), §6.1–§6.3 (worked examples).

**Module-placement note (discovered while scaffolding, not in the whitepaper):** `JavAIVectorizable` (the
contract) and `JavAIRuntime` (the engine implementing it) live in `javai-model`, not `javai-vector`, for the
same reason `JavAISortable<T>`, `JavAIList<T>`, `JavAISet<T>`, and `JavAIMap<K,V>` do — all four physically
live in `javai-model`, not in `javai-collections`, even though `doc/spec/vector-collections.md` discusses
the latter three as part of the Vector Collections extension area. Reason: `JavAIVectorizable.query()`
returns `JavAIList<T>`, and `JavAIList` in turn `extends JavAIVectorizable` right back — two types with a
genuine mutual reference can't live in separate modules without an illegal cycle, so wherever `JavAIList`
goes, `JavAIVectorizable`/`JavAIRuntime` (which constructs a `JavAIList` to implement `query()`) has to go
too. `javai-collections` depends on `javai-model`, not the reverse — putting `JavAIList` in
`javai-collections` would create a circular module dependency. The extension-area taxonomy is conceptual;
this is the compile-order-correct physical split. `javai-collections` holds `KnowledgeGraph`,
`SubgraphResult`, and `VectorIndex` — the types that depend on what's here, not the reverse. See
`javai-model`'s own package-info.java for the full reasoning and `doc/module-dependency-graph.md` for the
complete physical module graph.

The area everything else is built on: computing an object's embedding, keeping it current, and searching
an object graph by similarity.

## The `JavAIVectorizable` contract

`javaic`/the weaver implements every method below on any class annotated `@JavAIVectorizable`. You never
write `implements JavAIVectorizable` by hand — the annotation alone triggers full codegen.

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
| `EmbeddingVector` | Value type | A versioned vector: `values`, `modelId`, `dims`, `computedAt` — not a bare `float[]` |
| `vector(): EmbeddingVector` | Method | The object's own canonical embedding, recomputed lazily if dirty |
| `summaryVector(): EmbeddingVector` | Method | Hierarchical embedding over the object's contained/referenced graph — see formula below |
| `fieldNameVector(): EmbeddingVector` | Method (synthesized per `@Vectorize` field) | E.g. a `body` field gets `bodyVector()` |
| `fieldVector(String): EmbeddingVector` | Method | Dynamic fallback for the per-field accessors, for reflective/generic tooling |
| `similarityTo(...): double` | Method | Cosine similarity; CPU or accelerated backend chosen via `invokedynamic` (Acceleration Substrate) |
| `query(EmbeddingVector, Class<T>): JavAIList<T>` | Method | Search reachable graph for instances of `T`, ranked by similarity |
| `query(EmbeddingVector, Class<T>, int maxDepth): JavAIList<T>` | Method | Same, with an explicit traversal depth limit |
| `JavAISimilarityBackend` | SPI interface | Pluggable similarity engine (CPU/GPU), resolved via `invokedynamic` bootstrap |
| `JavAIEmbeddingProvider` | SPI interface | Pluggable, versioned embedding-model provider |

## Vectorization-controlling annotations (the "search-visibility hat")

| Annotation | Target | Purpose |
|---|---|---|
| `@JavAIVectorizable` | class | Opts a class into the interface above |
| `@Vectorize` / `@VectorizeIgnore` | field | Include/exclude a field from the local embedding |
| `@SearchVisibility(PUBLIC\|PROTECTED\|PRIVATE)` | field / class | Search-semantic visibility, independent of Java access modifiers |
| `@Summary` | field / class | Marks contribution to a container's hierarchical summary vector |
| `@EmbeddingModel("id")` | class / field | Overrides which embedding model vectorizes this element |

Internal/synthetic bookkeeping (never called directly): `isDirty(): boolean`, `markDirty(): void`,
`dependents(): Set<WeakReference<Object>>` (the back-edge set used for propagation).

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
- **FieldDirty** — a `@Vectorize` field was mutated via a woven setter; this object's own `vector()` is
  stale. Entered directly from Clean by a local mutation, nothing else.
- **SummaryDirty** — a reachable descendant went through FieldDirty (or was itself already SummaryDirty)
  and the back-edge walk (`JavAIRuntime.propagateDirty`) reached this object. This object's own field
  vector may still be perfectly current; only `summaryVector()` is stale.
- **EmbeddingRecomputing** / **SummaryRecomputing** — transient, in-process states entered by the next read
  of `vector()` or `summaryVector()` respectively, never entered by the write itself. This is what "lazy"
  means concretely: recomputation is a read-time side effect, not a write-time one.

An object can be FieldDirty and SummaryDirty at the same time — independent flags, each clears on its own
corresponding read. The propagation walk stops the instant it reaches a node already marked SummaryDirty,
which is both the cycle-safety guarantee and the reason repeated mutations in a batch don't re-walk the
same ancestors repeatedly. There is no global graph-wide generation counter or epoch — each object's pair
of flags is the entire durable state, which is what keeps the mechanism cheap enough to run on every
setter call without a background thread.

The mutation hook is compiled directly into the ordinary bytecode of the mutating setter at build time
(the same trick Hibernate already uses via ByteBuddy for entity dirty-checking):

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

## Summary-vector semantics, formally

`summaryVector()` is a decay-weighted **recursive** average, not a flat average over "everything reachable":

```
summaryVector(obj) = normalize(
    1.0 * vector(obj)
    + decay * Sum over each @Summary-marked child c of obj: summaryVector(c)
)

// Equivalently, flattened over every path from obj: each reachable node
// contributes decay^depth * its own vector(), and duplicate paths to the
// same node stack (add) rather than deduplicate. The recursive form and
// the flattened form give the same result for any acyclic reachability
// graph -- the recursive form is what actually gets implemented, since it
// only needs each child's *already-cached* summaryVector().
```

Four design decisions, each deliberate:

1. **Exponential decay, not linear.** A linear falloff (`weight = max(0, 1 - depth/D)`) needs a node's
   absolute distance from whichever ancestor is being summarized, which differs per ancestor — there's no
   way to compute it from a child's cached `summaryVector()` alone. Exponential decay is the only form
   that composes recursively one hop at a time, which the lazy propagation model above requires. This is
   an architectural constraint, not a style preference — do not swap in linear decay without redesigning
   the propagation model.
2. **Duplicate references stack, cycles stop.** A node reached via two different paths from `obj`
   contributes once through each path — this falls out of the recursive formula for free. A cycle (a path
   revisiting a node already on that same path) is different and must stop; treat the repeated node as a
   leaf for that path rather than recursing forever.
3. **Averaging vs. summing doesn't change retrieval results.** Cosine similarity is scale-invariant, so a
   weighted sum and a weighted average produce identical similarity rankings. `normalize()` is kept for
   numerical hygiene, not because it changes what a query returns.
4. **`@Summary` still gates inclusion; decay only controls distance.** A field excluded via
   `@VectorizeIgnore` or never marked `@Summary` contributes nothing regardless of depth.

**Known open risk:** a highly-shared node (a reference-data object pointed to by thousands of others) can
accumulate large aggregate weight purely from fan-in, diluting what actually distinguishes one object's
summary from another's — the same failure mode TF-IDF exists to correct for in text search. Not yet
resolved; see the proposed parameters below.

### Proposed `@Summary` parameters — NOT Phase 0

These formalize the knobs the section above implies. They are specification proposals, not implementation
commitments — do not build these until there's a concrete, demonstrated need:

| Proposed Parameter | Purpose |
|---|---|
| `@Summary(decay = 0.5)` | Per-field/edge-type override of the global default decay rate |
| `@Summary(maxStack = N)` / `@SummaryStacking(...)` | Caps how many occurrences of a repeatedly-referenced node stack at full weight — the fan-in/hub dampening control |
| `@Summary(maxDepth = N)` | Hard cutoff distinct from decay's asymptotic falloff — decay alone never truly reaches zero |
| `@Summary(aggregation = MEAN \| MAX \| ATTENTION)` | Pluggable combination function beyond weighted-mean |
| `@Summary(edgeKind = OWNERSHIP \| REFERENCE \| AGGREGATION)` | Names the relationship so a future decay-rate policy can be configured centrally per edge kind |

## Worked examples

Object vector vs. summary vector vs. field vector, and subgraph querying, are demonstrated end to end in
the whitepaper §6.1–§6.4 and Appendix A. Reproduce those as integration tests once `javai-model` and
`javai-collections` both exist — they're written as a coherent story (an `Article`/`Comment` domain) and
translate directly into test fixtures.

## Provider selection across platforms (discovered building the E2E test, not in the whitepaper)

`javai-vector` ships two real `JavAIEmbeddingProvider` implementations, not one:

| Implementation | Backend | Status |
|---|---|---|
| `TextEmbeddingsInferenceProvider` | Hugging Face TEI (§4.5.2) | The Phase 0 default — `docker/docker-compose.yml`'s `cpu`/`cuda` profiles |
| `OllamaEmbeddingProvider` | Ollama (GGUF via llama.cpp) | Not in the whitepaper — added for the platform gap below |

The reason a second implementation exists at all: TEI's Candle backend has a confirmed, unresolved upstream
bug running the reference model, `Qwen/Qwen3-Embedding-0.6B` (§4.5.1), on CPU — "Intel MKL ERROR: Parameter
8 was incorrect on entry to SGEMM" — reported on native x86_64/AMD hardware, not only under emulation (see
[huggingface/text-embeddings-inference#667](https://github.com/huggingface/text-embeddings-inference/issues/667)
and [#636](https://github.com/huggingface/text-embeddings-inference/issues/636); no upstream fix as of this
writing). It reproduces reliably on macOS, where Apple Silicon additionally needs x86_64 emulation to run
TEI's `cpu-1.9` image at all (it has no arm64 build). A separately-suggested fix,
`attn_implementation="eager"`, does **not** apply: that addresses a different bug (NaN output from
PyTorch's SDPA kernel on macOS) in a different stack (loading the model directly via
`transformers`/`sentence-transformers` in Python) — TEI is an independent Rust reimplementation that never
touches PyTorch's attention-kernel selection.

Ollama runs the same reference model through a genuinely different stack, unaffected by TEI's bug, on an
image that's natively arm64 (confirmed via `docker image inspect` — no x86_64 emulation on Apple Silicon at
all). This is exactly the "swapping the embedding provider entirely... is a configuration key, not a code
change" flexibility this SPI is designed for (§4.5.4) — applied one level down, to work around a specific
backend's bug rather than to change models or vendors.

**`dev.xtrafe.javai.vector.LocalEmbeddingDefaults`** is the one place this decision is made, so it can't
drift between "which provider class gets constructed" and "which container actually gets started":

- Defaults to Ollama on macOS, TEI everywhere else (Linux, Windows, unrecognized) — matching the
  whitepaper on every platform except the one with a confirmed bug.
- Overridable via the `javai.embedding.provider` system property (`ollama` or `tei`) — e.g. an Intel Mac
  that wants to try TEI, or a Linux box that wants Ollama for consistency with a Mac dev fleet.
- Exposes everything a caller needs to both start the right container (`dockerImage()`, `containerPort()`,
  `healthCheckPath()`, `modelIdentifierForContainerStartup()`) and construct the matching provider against
  it (`create(URI)`, `modelLabel()` for the `EmbeddingVector.modelId()` it will produce).

`e2e-client-test`'s `ArticleGraphEmbeddingE2ETest` is built entirely on top of this — it has no
platform-specific logic of its own, just asks `LocalEmbeddingDefaults` what to do. Verified end to end on
macOS (Ollama path, all 5 assertions passing against real 1024-dim embeddings); the TEI/Linux/Windows path
is exercised by the same test code but has not been separately run on those platforms yet — it reuses the
TEI wiring already proven correct in an earlier version of this test (against a different model, before the
Qwen3/Candle bug was tracked down), so nothing about the container startup or HTTP contract is new or
unverified, only the specific model being requested.
