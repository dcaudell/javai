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

`vector()` and `concatenatedTextVector()` are two genuinely different embeddings, not two names for the same
thing — see "Three vector accessors, not two" below.

## Public API

| Element | Kind | Purpose |
|---|---|---|
| `JavAIVectorizable` | Interface (weaver-implemented) | Implemented automatically on any `@JavAIVectorizable` class |
| `EmbeddingVector` | Value type | A versioned vector: `values`, `modelId`, `dims`, `computedAt` — not a bare `float[]` |
| `vector(): EmbeddingVector` | Method | Compositional aggregate: the centroid of every `@Vectorize` field's own `fieldVector()`, recomputed lazily if any field is dirty — not itself cached (see "Embedding concurrency model" below) |
| `concatenatedTextVector(): EmbeddingVector` | Method | A single embedding of every `@Vectorize` field's current value concatenated into one text block — what `vector()` used to compute before per-field caching; preserved under its own name |
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

Every `JavAIVectorizable` instance carries **two independent staleness flags**, not one — `FieldDirty` and
`SummaryDirty`. These predate, and are now narrower in scope than, the per-field concurrency machinery
described in "Embedding concurrency model" below (each `@Vectorize` field has its own, independently
tracked recompute state via `VectorCacheSlot`), but the two flags are still real and still gate real
behavior:

```
This object:              Clean --setter mutates a @Vectorize field--> FieldDirty
                           FieldDirty --next read of summaryVector()--> (consumed as summaryVector()'s
                                                                          own base-term staleness signal)

Each live dependent,       SummaryDirty <--markDirty() walks dependents(), stops at any node already dirty-- FieldDirty
transitively:              SummaryDirty --next read of summaryVector()/toContext()--> SummaryRecomputing
                           SummaryRecomputing --summary computed--> Clean
```

- **Clean** — nothing pending: every `@Vectorize` field's own cache slot is up to date and
  `summaryVector()` reflects the current graph.
- **FieldDirty** — a `@Vectorize` field was mutated via a woven setter. `vector()` itself has no cache of
  its own to gate anymore (see below) — `FieldDirty`'s sole remaining consumer is `summaryVector()`, which
  always includes `vector(self)` as its own base term and so must recompute whenever any local field
  changed, not just when a descendant did.
- **SummaryDirty** — a reachable descendant went through `FieldDirty` (or was itself already
  `SummaryDirty`) and the back-edge walk (`JavAIRuntime.propagateDirty`) reached this object.
  `summaryVector()` is stale; the object's own fields may still be perfectly current.
- **SummaryRecomputing** — transient, in-process state entered by the next read of `summaryVector()`,
  never entered by the write itself.

The propagation walk stops the instant it reaches a node already marked `SummaryDirty`, which is both the
cycle-safety guarantee and the reason repeated mutations in a batch don't re-walk the same ancestors
repeatedly. There is no global graph-wide generation counter for *this* pair of flags — that's deliberate,
and distinct from the per-field generation counters `VectorCacheSlot` maintains below, which exist to solve
a different problem (concurrent-computation races, not propagation cycles).

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
    JavAIRuntime.vectorizeFieldMutated(this, "body", body);
}
```

`vectorizeFieldMutated` does everything the old `markDirty()`/`propagateDirty()` pair used to (mark
`FieldDirty`, register the new value as a dependency if it's itself graph-shaped, propagate `SummaryDirty`
to ancestors) plus the new per-field concurrency bookkeeping described next: bumping this field's own
`VectorCacheSlot` generation, and — under `EVENTUAL_CONSISTENCY` only — eagerly dispatching this field's own
background recompute.

## Embedding concurrency model

Every `@Vectorize` field, and the object-level `concatenatedTextVector()`, is backed by its own
`VectorCacheSlot` (`javai-vector`) — a lock-free primitive tracking a monotonically increasing
**generation** (bumped once per mutation) and, separately, the highest generation any computation has
**attempted** versus the highest generation that actually **succeeded**. `vector()` itself holds no cache
of its own anymore: it's a purely compositional centroid of each field's own `fieldVector()`, recombined
in-memory on every call, so staleness and recomputation live entirely at the per-field level.

A slot is "dirty" exactly when its committed generation doesn't match its live generation — matching this
project's own definition of clean: *an object is clean exactly when its vector is accurate*, never merely
because a computation was attempted. A **failed** attempt (the provider throws) never advances the
committed generation, only the attempted one — this is what lets a slower, stale success from an older
generation be correctly rejected after a newer generation has already failed, without also permanently
blocking a same-generation *retry* from landing once the underlying problem clears up (see "Failure
handling" below).

**A reassignment that doesn't actually change the value is a complete no-op, under every mode.** The woven
setter compares the field's value *before* the assignment (captured via `@Advice.OnMethodEnter`, since by
the time an exit-only advice could see it, the assignment would already have overwritten it) against the
new value with `Objects.equals`; if they match, `JavAIRuntime.vectorizeFieldMutated` returns immediately —
no dirty-marking, no generation bump, no dispatch, no lock acquisition. Safe regardless of mode: if the
value genuinely didn't change, the vector genuinely can't have gone stale either.

### Three consistency modes

Selected once, ideally at startup alongside the embedding provider itself, via
`JavAIRuntime.configureConsistencyMode(EmbeddingConsistencyMode)` — **undefined behavior on a mid-flight
toggle**. Defaults to `IMMEDIATE_CONSISTENCY`.

- **`IMMEDIATE_CONSISTENCY`** (default) — mutation never triggers computation; only a subsequent read of a
  dirty slot does, and that read **blocks**, snapshotting the field's current value and computing against
  it inline before returning, while holding the *whole object's* lock (`DirtyTrackingSupport.objectLock()`)
  for the duration. Every setter, on every mode (see below), briefly takes that same lock around its own
  bookkeeping — so under this mode specifically, a concurrent setter or getter on the same object genuinely
  waits out an in-progress computation rather than racing it, and at most one computation per object is
  ever in flight at a time. Every vector request is guaranteed accurate to the field's state at the time of
  the request, even under races — the tradeoff is the slowest of the three modes under contention.
- **`EVENTUAL_CONSISTENCY`** — mutation eagerly dispatches a background recompute (fire-and-forget, via a
  shared virtual-thread executor) using the value already in hand at mutation time. A read of a slot that
  has been computed at least once returns the **last known-good value immediately**, without blocking, and
  additionally fires its own background recompute if the slot is still dirty **and nothing is already
  outstanding for the current generation** — so a burst of concurrent readers of the same stale value
  triggers at most one real `embed()` call between them, not one each, and a thread that reads a stale value
  never blocks on it. A slot's **very first** computation always blocks regardless of mode: there is no
  prior real value to serve in the meantime.
- **`COALESCED_CONSISTENCY`** — mutation behaves exactly like `EVENTUAL_CONSISTENCY`: eager, non-blocking
  dispatch on every setter call. Reads differ: a read of a dirty slot **blocks** until whatever computation
  is currently outstanding for it resolves (joining that shared result, via `VectorCacheSlot.claimPendingComputation`,
  rather than starting a redundant one of its own), dispatching a fresh computation to join only if nothing
  is currently outstanding. Because the field can be mutated again while a reader is waiting, a blocked
  reader can return a vector for a value the field has since moved on from — but that vector is always
  accurate to *some* real value the field genuinely held, never wrong, reflecting whatever was the most
  recently dispatched computation at the moment the reader started waiting. This sits between the other two:
  cheaper than `IMMEDIATE_CONSISTENCY` (many readers share one `embed()` call instead of each doing their
  own redundant one) while still giving every reader a real, blocking answer, unlike `EVENTUAL_CONSISTENCY`'s
  possibly-stale immediate one.

Whichever mode is active, a computation that lands (successfully or not) resolves consistently for everyone
waiting on it — a blocking joiner under `COALESCED_CONSISTENCY` sees exactly the same outcome (a value,
possibly `null` under `RETURN_NULL`, or a thrown exception under `THROW`) that the owning computation itself
produced, never a re-derived one of its own.

### Bounded concurrency

`JavAIRuntime.configureMaxConcurrentEmbeddingCalls(int)` (default
`JavAIRuntime.DEFAULT_MAX_CONCURRENT_EMBEDDING_CALLS`, currently 8) sets a global `Semaphore` gating every
real `embed()` call — blocking and background alike, across every object and every field. This is what
makes a burst of rapid mutation under `EVENTUAL_CONSISTENCY` degrade to "slower," rather than firing
unboundedly many concurrent HTTP calls at the configured provider.

### Failure handling

`JavAIRuntime.configureFailureMode(EmbeddingFailureMode)` selects one of two paired behaviors, default
`THROW`:

| Mode | Blocking failure (a caller is waiting) | Background failure (nobody is waiting) |
|---|---|---|
| `THROW` (default) | Rethrows the provider's exception to the caller | Silently keeps serving the last known-good value |
| `RETURN_NULL` | Returns `null` instead of throwing | Nulls out the served value |

`IMMEDIATE_CONSISTENCY`'s blocking reads and `COALESCED_CONSISTENCY`'s blocking joiners both use the
"blocking failure" row — a `COALESCED_CONSISTENCY` joiner isn't the thread that ran the failing `embed()`
call, but it's still genuinely waiting, so it gets the same rethrow-or-null treatment the owning
computation itself resolved to, not a re-derived decision of its own. `EVENTUAL_CONSISTENCY`'s eager
mutation-triggered dispatch and its own opportunistic re-dispatch both use the "background failure" row.

A failed attempt **never** marks a slot clean, under any mode — it only ever advances the slot's
*attempted* generation, never its *committed* one. Consequently, a subsequent read of a still-dirty,
already-computed-once slot under `EVENTUAL_CONSISTENCY`/`COALESCED_CONSISTENCY` re-dispatches its own
attempt regardless of whether an earlier mutation is what made it dirty — a transient failure with no
further mutation still eventually retries and converges, purely from being read again.

### Boxing: leaf values are cached uniformly, invisibly to JPA

Conceptually, a `@Vectorize` field's underlying value is cached the same way any other `JavAIVectorizable`
node would be — via its own `VectorCacheSlot` — regardless of whether the field's declared type is itself
graph-shaped. This is implemented as boxing *inside* `DirtyTrackingSupport`'s per-field slot map (keyed by
field name), never by changing the field's own declared type: a `@Vectorize private String title` field
stays a plain `String` for every purpose outside this mechanism — JPA/Hibernate mapping, equality,
serialization — completely undisturbed. `dev.xtrafe.javai.model.VectorizableString` is the standalone,
directly usable form of this same idea: an immutable `String` box implementing the full
`JavAIVectorizable` contract, always computing blocking (never background-dispatching, since immutability
means there's no "mutation" to eagerly react to — only ever a new instance).

### Three vector accessors, not two

- **`vector()`** — the compositional aggregate: centroid of each `@Vectorize` field's own `fieldVector()`.
  Free to evolve independently as more modalities are added (e.g. a future per-modality centroid rather than
  one flattened text embedding).
- **`concatenatedTextVector()`** — a single embedding of real text assembled from the object graph. Kept
  under its own name specifically so `vector()` remains free to change shape for a multi-modal future without
  losing this simpler, holistic embedding as an option. **Opt-in as of OMI-191** — see "Concatenated text
  vectoring" below; it returns an absent vector, costing nothing, for a type that has not asked for it.
- **`summaryVector()`** — the hierarchical, decay-weighted aggregate over the object's contained/referenced
  graph (see the formula below) — unrelated to either of the above except that it uses `vector()` as its own
  base term.

**Naming history, for anyone diffing against an older design:** `concatenatedTextVector()` was originally
going to be named `textVector()`. A real, reproducible collision surfaced during implementation: a
`@Vectorize private String text` field's own conventionally-synthesized per-field accessor
(`fieldName + "Vector"` → `textVector()`) collided exactly with that object-level method name, and
ByteBuddy's weaving failure for the collision was — at the time — silently swallowed by the weaver's default
listener configuration, producing a completely unwoven class with no visible error. Fixed two ways: the
object-level method was renamed to `concatenatedTextVector()` (since `text` is too common a real field name to
treat this as a rare edge case), and `javai-substrate`'s `JavAIWeaver` now also throws a clear,
immediate `IllegalStateException` for this entire class of collision (any per-field accessor name matching
a reserved `JavAIVectorizable` method), rather than silently producing broken bytecode.

### Persistence must-haves

Regardless of which `EmbeddingConsistencyMode` is configured, the database must never see a vector that
doesn't match the field value being written in the same flush. `JavAIRuntime.runWithSubgraphLockedForPersistence(root, action)`
is what `javai-persistence`'s two backends (`RepositoryBackendHibernatePostgres`/`RepositoryBackendNeo4j`)
wrap every `save()` call in:

- Every `JavAIVectorizable` reachable from `root` (root included, and including an intermediate collection
  like a `JavAIArrayList` in its own right) gets its own per-object lock (`DirtyTrackingSupport.objectLock()`
  — the same lock `IMMEDIATE_CONSISTENCY` uses) held for the duration of `action`. Locks are acquired in a
  fixed, construction-time-assigned global sequence order (`DirtyTrackingSupport.sequenceNumber()`), which is
  what makes this deadlock-free even when two overlapping subgraphs are locked concurrently by separate
  persistence operations.
- Every `fieldVector()`/`concatenatedTextVector()` read on the calling thread is forced to compute accurately
  (blocking, never serving a stale cached value) for the duration of `action`, regardless of the globally
  configured consistency mode — implemented as a thread-local override, restored to whatever it was before
  once `action` completes (including on exception), so it never leaks past the call or across threads.
- **Nothing in the locked subgraph can mutate out from under the flush while it's in progress** —
  genuinely true, not just true-for-reads, because every setter under *every* consistency mode briefly takes
  the same per-object lock around its own bookkeeping (see "Three consistency modes" above). Without that,
  an ordinary `EVENTUAL_CONSISTENCY`/`COALESCED_CONSISTENCY` setter (which otherwise never blocks on
  anything) would proceed completely unobstructed while a flush believes the subgraph is frozen.

See `doc/spec/persistence-bridge.md` for how the two backends use this from their own `save()` methods.

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

`javai-vector` ships five real `JavAIEmbeddingProvider` implementations:

| Implementation | Backend | Status |
|---|---|---|
| `EmbeddingProviderTextEmbeddingsInference` | Hugging Face TEI (§4.5.2) | The Phase 0 default — `docker/docker-compose.yml`'s `cpu`/`cuda` profiles |
| `EmbeddingProviderOllama` | Ollama (GGUF via llama.cpp) | Not in the whitepaper — added for the platform gap below |
| `EmbeddingProviderOpenAI` | OpenAI's hosted `/v1/embeddings` | Mirrors `javai-completion`'s `CortexOpenAI` vendor; not yet verified against a live endpoint |
| `EmbeddingProviderVLlm` | Self-hosted vLLM's OpenAI-compatible `/v1/embeddings` | Mirrors `CortexVLlm`; not yet verified against a live endpoint |
| `EmbeddingProviderReplicate` | Replicate's create-prediction-then-poll API | Mirrors `CortexReplicate`; best-effort — Replicate has no vendor-wide embeddings contract, see `javai-vector/README.md`'s "Hosted-vendor providers" section |

All five hand-roll `java.net.http.HttpClient` calls (see each class's own javadoc for why no JSON library),
and all five retry a `429` response via `RetrySupport`/`EndpointRateLimiter` (also `javai-vector` — see
`doc/spec/completion-fabric.md`'s "Rate limiting" section): endpoint-keyed, cross-instance backoff state
shared with `javai-completion`'s `Cortex` implementations too, since `javai-completion` depends on
`javai-vector` and can reuse the same registry.

`javai-completion`'s `Cortex` vendor set also includes Anthropic and Groq, but neither has a native
embeddings API to mirror — Anthropic has none at all (it officially recommends Voyage AI instead) and
Groq's API reference lists no embeddings endpoint. No `EmbeddingProviderAnthropic`/`EmbeddingProviderGroq`
exist as a result; this is a deliberate gap, not an oversight.

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

## Maximum input size (OMI-216, not in the whitepaper)

Every embedding model has a maximum input length, and before this each provider did something different when
a text exceeded it. Measured, not assumed: a 324,000-character input — roughly 80,000 tokens against
`qwen3-embedding:0.6b`'s 32,768-token context — sent to a live Ollama instance returned **HTTP 200 and an
ordinary 1024-dimension vector**. It had been truncated, and nothing in the response said so. TEI does the
same by design; OpenAI rejects the request outright. So identical text yielded a correct vector, a quietly
partial one, or an exception, depending only on which provider happened to be configured — and the quiet
partial vector is the worst of the three, because a truncated vector is by construction indistinguishable
from a complete one. It is a plausible embedding of a document prefix, and it will sit in the index scoring
against queries forever without ever looking wrong.

This matters more here than in `javai-completion`, where the analogous limit is handled by
`Cortex.contextWindowTokens()`/`PromptContext.render(int)`: an over-long prompt usually produces a visibly
degraded answer, whereas an over-long embedding produces a *silently* wrong one.

`JavAIEmbeddingProvider.maxInputTokens()` is the SPI addition — a `default` method, so no existing
implementation breaks. It resolves through three tiers, most authoritative first:

1. **An explicit override**, when the caller supplied one — a constructor parameter on Ollama/TEI/vLLM/
   OpenAI, `Builder.maxInputTokens(int)` on Replicate.
2. **Runtime discovery**, where the backend's API can actually answer:

   | Provider | Endpoint | Field |
   |---|---|---|
   | `EmbeddingProviderOllama` | `/api/show` | `<architecture>.context_length` |
   | `EmbeddingProviderTextEmbeddingsInference` | `/info` | `max_input_length` |
   | `EmbeddingProviderVLlm` | `/v1/models` | `max_model_len` |
   | `EmbeddingProviderOpenAI`, `EmbeddingProviderReplicate` | — | neither vendor publishes one |

   Note Ollama's key is named for the model's *architecture* (`qwen3.context_length`), not a fixed name, so
   a constant-name lookup finds nothing and silently falls back. Discovery is cached per provider instance —
   a limit lookup must not become a per-embedding tax — and any failure (unreachable, unparseable, absent
   field) falls back to the next tier rather than throwing. Not knowing the limit precisely is a reason to be
   conservative, never a reason to refuse to embed at all.
3. **`EmbeddingModelLimits`**, a best-effort table of published limits, whose fallback for an unrecognized
   model is **512 tokens** — deliberately not the completion side's 8192. Embedding models run far tighter
   than chat models (many sentence-transformer models cap at 512 and several at 256), so a generous default
   would produce partial vectors for exactly the models most likely to be missing from the table.

Limits are in tokens; JavAI holds characters and has no tokenizer. `EmbeddingInputLimits` converts at a fixed
**3 characters per token** — under the ~4 typical of English prose, because giving up a little of a long input
is recoverable and sailing past the real limit is not. Truncation cuts at a word boundary when one falls
within the last tenth of the budget, and at the budget exactly when one doesn't (JSON, a URL, a long
identifier), so text with no whitespace near the cut loses only what the budget requires.

Enforcement is client-side on **all five providers uniformly**, including the two that already truncate
server-side. That is a deliberate trade: TEI and Ollama truncate *exactly*, having real tokenizers, so
deferring to them would preserve more text. Uniformity wins anyway — the complaint being fixed is that the
outcome depended on the provider, and cutting at the same estimated boundary everywhere makes results
reproducible across a provider swap, which the SPI treats as a configuration change (§4.5.4). TEI keeps its
own `"truncate": true` as a server-side backstop regardless. Batched inputs are bounded per member, so one
over-long entry neither shortens its neighbours nor fails the batch.

**Truncation is currently silent**, and that is a known, deliberately-deferred gap rather than a settled
design. Signalling it would mean either a breaking change to the `EmbeddingVector` record (45 construction
sites) or introducing the project's first logging mechanism — this codebase has none — and both are larger
decisions than this fix. It is not a regression (Ollama and TEI already did this with no limit knowledge at
all), but it is the invisible-failure shape this project has twice rejected elsewhere, and it should be
closed once a project-wide logging mechanism is chosen.

## Batched embedding (OMI-187, OMI-213, OMI-266, not in the whitepaper)

`JavAIEmbeddingProvider.embedAll(List<String>)` embeds several texts in one request. Its `default` loops over
`embed`, so every provider is correct without it and overriding it is a pure latency optimization with no
semantic difference — callers can use it unconditionally rather than branching on provider capability.

The motivation is latency, not call count, and it is a different problem from the waste OMI-187 removed.
Lazy computation discovers one text at a time, deep inside a read, so seeding 1,400 tags is 1,400
*sequential* round trips (~14s at the ~10ms per embed measured there) even when every one of them is
warranted. `JavAIRuntime.precomputeVectors(Collection<?>)` is the caller-side half: it gathers texts across a
collection first, de-duplicates them (a value shared by several objects is embedded once), and issues chunked
`embedAll` calls — 100 per call by default.

| Provider | Batched | Wire shape |
|---|---|---|
| Ollama | yes | `"input"` array → `{"embeddings": [[…], […]]}` |
| OpenAI | yes | `"input"` array → `{"data": [{index, embedding}, …]}` |
| vLLM | yes | identical to OpenAI |
| TEI | yes | `"inputs"` array → `[[…], […]]` |
| Replicate | **no**, deliberately | see below |

**The one thing an implementation can get wrong invisibly** is which vector belongs to which text. OpenAI
documents that `data` entries carry an explicit `index` and are *not* guaranteed to arrive in request order;
vLLM, serving the same contract, schedules across continuous batches for reasons of its own. An
implementation that reads rows by array position therefore pairs every text with the wrong vector — and
nothing fails: each vector is well-formed and correctly dimensioned, and the only symptom is a semantic index
that returns subtly wrong neighbours forever. `OpenAiCompatibleEmbeddings` (shared by both providers rather
than duplicated) treats the ordering as a checked invariant: it places rows by `index` and refuses a response
whose indices are not exactly one each of `0..n-1`. TEI needs none of this — its response is a bare array
whose position *is* the correspondence — but its row count is still checked, that being the only way it could
misalign. A slow loop beats a fast wrong answer.

**Replicate deliberately keeps the looping default.** Not because it is hard, but because there is nothing to
implement against: Replicate's `input` object is shaped by each model's own `cog predict()` signature rather
than by Replicate, so there is no vendor-wide contract to code to. Investigating the bundled default model
(`beautyyuyanli/multilingual-e5-large`) found it *does* take several texts at once — but through a field named
`texts`, not the `text` this provider defaults to, and described only as "formatted as a JSON list of
strings", which leaves it ambiguous between a native JSON array and a JSON-encoded string (the usual way a
`cog` model expresses a list, given cog's scalar input types). Both the field name and the encoding would
have been guesses. A wrong guess either errors loudly or silently embeds the string `["a","b"]` instead of
`a` and `b` — the second being exactly the failure shape this work exists to prevent.

**Who calls it (OMI-266).** For two releases, nothing did. `precomputeVectors` and `embedAll` were both real,
both tested, both documented here — and reachable only by a caller who read this file and wired them up by
hand, so every flow *inside* the library went on discovering one text at a time. That is now closed at three
levels:

| Level | Entry point | Batches across |
|---|---|---|
| One `save()` | `runWithSubgraphLockedForPersistence` warms before the flush runs | everything reachable from the saved root |
| One bulk write | `JavAIRuntime.warmSubgraphsForPersistence(roots)`, behind `JavAIRepository.saveAll` | the union of every root's subgraph |
| One `query()` | warms the candidate set before ranking | every match the graph walk found |

The first is the important one, because it needs no caller cooperation at all: a plain `repo.save(article)`
batches its whole subgraph without the caller knowing this section exists. The persistence-side numbers and
the transaction-duration argument are in `doc/spec/persistence-bridge.md`'s own "Batching" section.

A warm is always an optimization and never a semantic change: it fills exactly the slots the reads that
follow would have filled, skips any slot already accurate, and — if the provider fails — is *dropped*, leaving
the slots dirty for those reads to resolve under the configured `EmbeddingFailureMode`. Deciding the failure
outcome in the warm instead would mean a second implementation of that policy, free to disagree with the
first.

**How large a batch may get (OMI-266).** Three ceilings apply, and the smallest wins: the caller's own
`batchSize`, the provider's `maxBatchSize()` (inputs per request), and the provider's `maxBatchTokens()`
(total tokens per request). The last two are new SPI `default` methods, so no implementation breaks and one
that declares nothing keeps the chunk size already in use.

Both provider ceilings are needed, because they fail in opposite directions: eight enormous documents break a
token ceiling while satisfying any count ceiling, and two thousand one-word strings do the reverse. And
neither is covered by the per-input limit above — that section is explicit that truncation is applied *per
member, never per batch*, which is right, and which leaves the sum unbounded. A hundred individually-legal
texts against `qwen3-embedding:0.6b`'s 32,768-token context is roughly **9.4 MiB in one HTTP body**. That was
theoretical while nothing in the library called `embedAll`; it stopped being theoretical when every `save()`
started to.

| Provider | Count ceiling | Size ceiling | How |
|---|---|---|---|
| TEI | `max_client_batch_size` (**default 32**) | `max_batch_tokens` (default 16,384) | discovered from `/info`, cached with `max_input_length` in one fetch |
| OpenAI | 2048 | 300,000 tokens | vendor-published; there is no endpoint to ask |
| Ollama, vLLM | library default | library default | neither publishes a batch limit |
| Replicate | — | — | doesn't batch at all |

TEI's default of 32 is the concrete case: it is *below* the 100 this library chunked at, so a default TEI
deployment refused a full batch outright.

`embedAll` itself deliberately does **not** split — it sends exactly one request for exactly what it is given,
which is what keeps one call equal to one round trip and one permit. Splitting is the caller's job, and
`precomputeVectors` is the caller that does it. A single text larger than the whole batch budget is sent
alone rather than dropped: it is already bounded to the model's context, so it is a request the provider has
every reason to accept.

**Gate accounting:** a batched call takes **one** permit from
`JavAIRuntime.configureMaxConcurrentEmbeddingCalls`'s semaphore, not one per text. That gate bounds concurrent
*calls into the provider*, and a batch is one call on one connection however many texts it carries. Charging
per text would make the gate throttle batching itself — and a 100-text batch against a gate of 8 could never
acquire enough permits, so bulk seeding would deadlock rather than be bounded. Taking a permit at all is also
new in OMI-213: `precomputeVectors` previously called the provider without touching the gate, so a bulk seed
ran entirely outside the bound that is documented as applying to every provider call in every consistency
mode.

## Concatenated text vectoring (OMI-191, not in the whitepaper)

`concatenatedTextVector()` is the other kind of aggregate: where `summaryVector()` does arithmetic over
already-computed vectors, this assembles **real text** from an object graph into one string and embeds that
once. An embedding model can capture relationships across a whole document that combining separately-embedded
fields arithmetically cannot.

It was woven onto every `@JavAIVectorizable` from the start, but nothing stored it, no query reached it, and
every JavAI collection threw `UnsupportedOperationException` when asked for one. It was computed on demand at
the price of a real model call, and thrown away. This makes it a real feature and, in the same move, makes it
**opt-in** — a type that says nothing gets an absent vector, assembles no text, and never reaches the
provider.

### Three independent opt-ins

Nothing is accumulated unless asked for. The library cannot know whether folding a `Song`'s lyrics into an
`Album` is meaningful for a given domain, so it must not guess.

| Placement | Meaning |
|---|---|
| `@Summary(concatenate = true)` on a **TYPE** | This class produces text from its own `@Vectorize` fields |
| `@Summary(concatenate = true)` on a **FIELD** referencing a vectorizable | Absorb that child's text into mine |
| `@Summary(concatenate = true)` on a **FIELD** holding a JavAI collection | Aggregate its members' text into mine |

`concatenate` defaults to `false`, so nothing changes for existing consumers. The three are genuinely
independent: a container may absorb its children without contributing its own fields, which is what you want
when its own fields are bookkeeping. A JavAI collection always *can* aggregate; whether it does is decided by
the **owning field**, never by the collection.

Note this **adds to** `@Summary`'s existing meaning rather than replacing it — such a field still contributes
to `summaryVector()`. There is deliberately no way to absorb a child's text while excluding it from the
summary vector; no use case demanded it, and a second orthogonal flag is a worse default than an honest
restriction.

Before this, `@Summary` on a TYPE was inert: the target was declared, every reader was field-only. That is
what left the TYPE placement free to be given this meaning.

### Assembly: parent first, each node exactly once

Text is assembled parent-first, newline-separated, with each node contributing **exactly once per assembly**.

That last part is a deliberate divergence from `summaryVector()`, which lets a node reachable by two paths
stack additively — adding a vector twice is a meaningful weighting. The same paragraph appearing twice in one
string is not a weighting; it just skews the embedding toward whatever it happens to say. The colouring also
supplies cycle safety, so a diamond, a cycle and a self-reference all fall out of one mechanism rather than
three.

`concatenatedText()` returns **`null`, never `""`**, when there is no text -- a type that declined, or one that participates but has nothing in its fields. The empty string is a *value*, and a value is something a provider will embed; that is precisely the wasted call (and the space-shaped vector real providers return for an empty input) that OMI-187 removed from the collection path. `null` maps straight onto `EmbeddingVector.absent()`, so "there is nothing here" stays representable rather than approximated all the way down.

Assembly is **pure string work** — no embedding happens while walking the graph. That is what makes the
batched pass possible: `precomputeVectors` builds every text first and only then reaches the network, so a
graph of any size or shape costs one embedding per participating object, in batches, rather than one call
discovered at a time inside a read. The concatenated pass runs *after* the field pass, so assembly never
interleaves with per-field embedding.

### Staleness, and a propagation bug this surfaced

The concatenated text has its own `VectorCacheSlot`, so it gets independent validity by generation — the same
mechanism that gives each `@Vectorize` field its own. A descendant's mutation bumps its ancestors' slots
through the existing dependent walk.

Making that work required fixing `propagateDirty`, which had pruned at the first already-`SummaryDirty` node.
That prune is sound for a monotone boolean whose clearer also clears its descendants, which holds on the path
`summaryVector()` itself takes. **It does not hold for concatenated text**: assembling an ancestor's text
calls `concatenatedText()` on its descendants, which is pure string work committing nothing, so the ancestor
goes clean while its descendants stay dirty. A later mutation down there would prune at the dirty descendant
and leave the clean ancestor holding silently stale text. The walk now visits each reachable dependent once
via an identity set. It is the *walk* that was overfitted to a single reader, not just the flag — OMI-187's
lesson one level up.

### Storage and query

Persisted on the entity-grain table alongside the summary vector; see `doc/spec/persistence-bridge.md`.
`findNearestByConcatenatedTextVector(EmbeddingVector, int)` searches it, and is **rejected at
repository-creation time** for an entity type that does not participate — otherwise it would return an empty
list forever, indistinguishable from "nothing was similar".
