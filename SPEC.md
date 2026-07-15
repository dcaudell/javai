# JavAI Extensions — Specification Overview

Read this file once. It is complete enough to orient you regardless of which module or extension area
you're working in. Deeper, area-specific primitive definitions and code examples live in `doc/spec/`;
full rationale, prior-art research, and the roadmap live in `doc/JAI_Whitepaper.docx`.

## What this is

JavAI Extensions is a set of extensions to Java — not a new language — that make semantic embedding
vectors, knowledge-graph structure, and LLM-codegen guidance first-class, automatically-maintained
properties of the object model itself. The central bet: no existing ORM, object-graph mapper, or
programming language automatically re-embeds an object as a side effect of mutating it. This project makes
that a property of the object model: vectorizable fields re-embed automatically on mutation, container and
containment-graph "summary vectors" stay consistent via lazy, cycle-safe propagation, and the resulting
vectors are queryable — by similarity, by graph structure, or both — against either the live in-process
object graph or a persisted third-party store.

This project is a validation testbed, not a bid to establish a permanent parallel ecosystem. Extensions
that prove valuable are candidates for proposal back to Java itself (a JEP, a widely-adopted library
convention, or both); extensions that don't earn adoption remain a useful, self-contained library.

## The hard interop rule (non-negotiable)

Every class this project produces — woven, compiled, or otherwise — must be a complete, correct, standalone
standard JVM class file, runnable on any stock JDK 21+, with the JavAI runtime as an ordinary classpath
dependency. Any custom JVM, GPU dispatch, or modified bytecode is allowed to exist only as an optional
accelerated execution path with a required correct fallback — never as a requirement for the semantics to
work at all. If you're ever tempted to make correctness depend on something only a custom runtime provides,
stop — that breaks the one constraint everything else is built around.

## The eight extension areas

| # | Area | Purpose | Detail |
|---|---|---|---|
| 1 | **Vector Core** | Embedding calculation, object-graph `query()`, the mutation → dirty → recompute lifecycle | `doc/spec/vector-core.md` |
| 2 | **Persistence Bridge** | Automated JPA vectorization; persisted, searchable object graphs (Hibernate/pgvector + Neo4j) | `doc/spec/persistence-bridge.md` |
| 3 | **Completion Fabric** | Provider-agnostic RAG completions, wrapping Spring AI rather than competing with it | `doc/spec/completion-fabric.md` |
| 4 | **Vector Collections** | `JavAIList`/`Set`/`Map`, `KnowledgeGraph`, `VectorIndex` — vector-aware, parallel to `java.util` | `doc/spec/vector-collections.md` |
| 5 | **Codegen Guidance** | Annotations governing what an LLM coding agent may read, generate, or modify | `doc/spec/codegen-guidance.md` |
| 6 | **Acceleration Substrate** | The compiler/weaver/dispatch mechanism beneath Vector Core, Vector Collections, and Codegen Guidance | `doc/spec/acceleration-substrate.md` |
| 7 | **Agentic Supervision** | AoP-style method/constructor interception (sync, read-write + async, observation-only) enabling an LLM-backed listener to observe or intervene on execution | `doc/spec/agentic-supervision.md` |
| 8 | **Tagging** | `@Taggable` objects, recursive Tags/TagSets, LLM-based classification, and tag-collection similarity search — independent of Vector Core, composable with it | `doc/spec/tagging.md` |

## Dependency graph between the areas

```
                     6. Acceleration Substrate
                (compiler / weaver / invokedynamic / GPU dispatch)
                    |            |              |
                    v            v              v
          1. Vector Core   4. Vector Coll.   5. Codegen Guidance
                    |            |
                    |    +-------+-------+
                    v    v               v
          2. Persistence Bridge   3. Completion Fabric

          7. Agentic Supervision   (standalone — its own independent weaver,
                                     depending only on javai-annotations; not
                                     fed by, and does not feed, any area above
                                     at the module level — see below)

          8. Tagging   (depends on Vector Core [1], Vector Collections [4],
                         Persistence Bridge [2], and Completion Fabric [3] —
                         the highest-tier area, consuming three others rather
                         than feeding into them; see below)
```

Read arrows as "required by." Acceleration Substrate is the foundation for areas 1, 4, and 5 — it's the
compiler/weaver that actually implements Vector Core's interface and Vector Collections' graph types, and
the mechanism that turns Codegen Guidance's annotations into enforced behavior. Vector Core feeds Vector
Collections (`EmbeddingVector`/`similarityTo()` back cosine-distance sorting and graph-node similarity) and
Persistence Bridge (nothing to persist without it). Vector Collections feeds Persistence Bridge (a
`KnowledgeGraph` or `JavAIList` is what a repository query actually returns) and Completion Fabric
(`toContext()` is defined on Vector Collections' result types). Codegen Guidance is mostly a leaf, consumed
by the Substrate.

**Vector Core is realized by two physical modules, not one** — `javai-vector` (pure vector/embedding
functionality: `EmbeddingVector`, the CPU similarity backend, the embedding-provider SPI, dirty-tracking
primitives) and `javai-model` (`JavAIVectorizable`/`JavAIRuntime`, plus the Vector Collections interfaces
`JavAIList`/`Set`/`Map` and the Completion Fabric RAG primitives `Contextable`/`PromptContext`, all
physically homed here for the same reason: `JavAIVectorizable.query()` returns `JavAIList<T>`, and
`JavAIList` implements `JavAIVectorizable` right back, so the two can never live in separate modules
without an illegal cycle — see `javai-model`'s own package-info.java for the full trace). This mirrors, and
formalizes, the same "conceptual area ≠ physical module" split this file already documents for `JavAIList`
below — see the `doc/module-dependency-graph.md` diagram for the actual 8-module Maven reactor shape.

Agentic Supervision is deliberately *not* wired into this graph the way areas 1–6 are. It has its own
independent weaver rather than extending Acceleration Substrate's, so that its module (`javai-supervision`)
stays at the same early, cheap-to-prove tier as `javai-substrate` instead of pulling Acceleration Substrate
downstream of Completion Fabric/Vector Collections. An "Agentic Listener" — a supervision listener whose
decision is LLM-backed and grounded in RAG over the object graph — is documented as an application-level
composition of Agentic Supervision's generic listener interfaces with Completion Fabric and Vector
Core/Collections, not a hard dependency any of those modules take on each other. See
`doc/spec/agentic-supervision.md`'s "Relationship to the rest of JavAI Extensions" section for the full
reasoning.

Tagging is the mirror image of Agentic Supervision's isolation: instead of standing apart from the graph, it
sits at the *top* of it, genuinely depending on three other areas at once — Vector Core (a `Tag` is
`@JavAIVectorizable`), Persistence Bridge (Tags/TagSets/Taggings are persisted, three backends), and
Completion Fabric (classification is an LLM call). It does not extend `KnowledgeGraph`/Vector Collections as
a hard dependency, even though the two compose naturally where an object happens to be both `@Taggable` and
`@JavAIGraphNode` — see `doc/spec/tagging.md`'s "Optional composition with KnowledgeGraph." Unlike every
area before it, `@Taggable` itself is deliberately *unwoven* (the same lightweight, hand-implemented-marker
shape as `@JavAIGraphNode`, not `@JavAIVectorizable`'s full weave) — see that same doc's "Orthogonality"
section for why tagging state doesn't need Acceleration Substrate's mutation-interception mechanism at all.

Practical implication: Acceleration Substrate and Vector Core are where a design mistake is most expensive
to unwind, since three or four other areas build on them. Persistence Bridge and Completion Fabric are the
safest place to experiment. Agentic Supervision, being structurally isolated, is a similarly safe, low-blast-radius
place to experiment — a mistake there doesn't ripple into the other six areas. Tagging is the opposite kind
of low-risk: expensive to get wrong in its *own* design, but incapable of rippling back downward into the
three areas it depends on, since nothing below it takes a dependency on `javai-tagging`.

## Phase 0: the Golden Contract

Phase 0 targets full functional completeness — every annotation, every collection, every auto-generated
method, all three persistence backends, the entire Completion/RAG API, and Tagging — delivered via runtime
bytecode weaving instead of a real compiler, because nothing in the functional surface actually requires a
compiler or a GPU to work correctly, only to work fast. Nine components (Vector Core is realized by two —
see above), each a plain library, each independently buildable in the order below:

| Component | Mechanism | Delivers |
|---|---|---|
| `javai-annotations` | Plain annotation definitions, no processing logic | Full annotation vocabulary across all eight areas |
| `javai-vector` (Vector Core, part 1) | Pure Java library | `EmbeddingVector`, CPU similarity, embedding providers, dirty-tracking primitives |
| `javai-model` (Vector Core, part 2 + physical home for Vector Collections/Completion Fabric primitives) | Pure Java library, depends on `javai-vector` | `JavAIVectorizable`/`JavAIRuntime` (back-edge propagation, `query()`), `JavAIList`/`Set`/`Map`, `Contextable`/`PromptContext` |
| `javai-substrate` (Acceleration Substrate) | ByteBuddy weaving, load-time agent or build-time Maven/Gradle plugin | Implements `JavAIVectorizable`/`JavAIGraphNode` on any annotated class |
| `javai-supervision` (Agentic Supervision) | ByteBuddy weaving, its own independent installer | Sync (blocking, read-write) + async (fire-and-forget, observation-only) method/constructor interception |
| `javai-collections` (Vector Collections) | Pure Java library, backed by `javai-vector` + `javai-model` | `VectorIndex`, `KnowledgeGraph` + `SubgraphResult` |
| `javai-persistence` (Persistence Bridge) | Hibernate-based shim + Neo4j shim + Spring Data MongoDB shim | All three persistence backends real in Phase 0, not aspirational |
| `javai-completion` (Completion Fabric) | Wraps Spring AI `ChatModel` | Full RAG API — `PromptContext`, `CompletionRequest`/`Result`, `toContext()`, `complete()`/`completeStreaming()` |
| `javai-tagging` (Tagging) | Pure Java library, backed by `javai-vector`/`javai-model`/`javai-collections`/`javai-persistence`/`javai-completion`; ships its own pre-woven `Tag`/`TagSet` (see `doc/spec/tagging.md`'s "this module weaves itself") | `@Taggable`/`@TagIgnore`, `Tag`/`TagSet`/`Tagging`, LLM-based classification via `JavAITagging`, the tag-summary-vector `VectorIndex<TaggableRef>` |

Nothing past Phase 0 (a real `javaic` compiler, `invokedynamic` dispatch, GPU acceleration, an optional
JavAIVM) adds a capability a Phase 0 developer doesn't already have — each replaces an internal mechanism
with a faster version of the same contract. Do not build toward those until Phase 0 proves the core thesis
out.

## Object lifecycle (cross-cutting — relevant no matter what you're building)

Every vectorizable instance carries two independent staleness flags, not one:

- **FieldDirty** — a `@Vectorize` field was mutated; this object's own `vector()` is stale. Clears on the
  next read of `vector()`/`similarityTo()`/`query()` (lazy recomputation — never eager on write).
- **SummaryDirty** — a reachable descendant went dirty and the back-edge walk reached this object; only
  `summaryVector()` is stale. Clears on the next read of `summaryVector()`/`toContext()`. The walk stops the
  instant it reaches a node already marked, which is the cycle-safety guarantee.

An object can be both at once; each clears independently. There is no global generation counter — each
object's pair of flags is the entire durable state. Full diagram: `doc/spec/vector-core.md`.

## Summary-vector formula (cross-cutting)

```
summaryVector(obj) = normalize(
    1.0 * vector(obj)
    + decay * Sum over each @Summary-marked child c of obj: summaryVector(c)
)
```

Exponential decay, not linear — this is an architectural constraint, not a style choice: exponential decay
is the only form computable from each child's already-cached `summaryVector()`, which is what the lazy,
one-hop-at-a-time propagation model above requires. Duplicate references (the same node via two different
paths) stack additively for free, out of the recursive formula; cycles (a path revisiting its own ancestor)
must stop, per the same cycle-safety rule above. Full derivation, the hub/fan-in risk, and proposed (not
Phase 0) `@Summary` tuning parameters: `doc/spec/vector-core.md`.

## Current status

Phase 0, actively underway. `javai-annotations`, `javai-vector` + `javai-model` (Vector Core, physically
split across the two — see above — with `javai-model` also including the `Contextable`/`PromptContext`
RAG-integration primitives and the `JavAIList`/`Set`/`Map` Vector Collections interfaces), `javai-substrate`
(Acceleration Substrate's weaving, including the full lifecycle state machine and summary-vector
propagation), `javai-collections` (Vector Collections' `KnowledgeGraph`/`SubgraphResult`/`VectorIndex`), and
`javai-persistence` (Persistence Bridge, both backends, including model-versioning/reindex/revert) all have
real, tested implementations — see each module's own README for exactly what's covered and what's still
deliberately out of scope. `javai-completion` (Completion Fabric) has both its connector layer (`Cortex`,
six providers: OpenAI, Anthropic, Groq, vLLM, Ollama, Replicate; `CompletionRequest`/`CompletionResult`,
provider-specific tuning parameters, Handlebars-based prompt templating via `CompletionRequest.render()`)
and its RAG-integration half real and tested: grounding a completion in a `JavAIList`/`Set`/`Map` via
`PromptContext` (`Contextable`, `ContextableObject`) — these primitives live in `javai-model`, not
`javai-completion`, for a real dependency-direction reason documented in both modules' own READMEs.
`KnowledgeGraph`/`SubgraphResult` (`javai-collections`) don't implement `Contextable` yet — deferred pending
a cycle-safe design, since GSON's default marshalling has no cycle guard and those types are graph-shaped by
design; see `javai-completion`'s own README for exactly what's covered. `javai-supervision` (Agentic
Supervision) also has a real, tested implementation now: `SupervisionWeaver` (its own independent ByteBuddy
weaver) and `JavAISupervisionRuntime` (listener registration + sync-then-async dispatch), proven end to end
by `SupervisionWeavingTest` — PRE/POST/EXCEPTION, sync veto and rewrite rights, async off-thread dispatch
that can't block or mutate the call, and two JVM-imposed asymmetries between methods and constructors
discovered (not assumed) while building it; see that module's own README for the full detail. `javai-tagging`
(Tagging) also has a real, tested implementation now: `@Taggable`/`@TagIgnore`, `Tag`/`TagSet`/`Tagging`
against all three persistence backends, LLM-based classification via `JavAITagRepository.classify`/
`classifyAll` (a `Cortex`-backed diff against `source = "auto"` associations), and the tag-summary-vector
`VectorIndex<TaggableRef>` (`tagSimilarityIndex()`) — the first module to ship its own pre-woven
`@JavAIVectorizable` classes inside its own jar, which required adding real build-time (Maven-plugin)
weaving to `javai-substrate` as a prerequisite (see that module's own README). `JavAITagRepository` is an
instance wrapper, not a static facade — see "Coding standard: static/global scope is the exception" below,
which this module (alongside `javai-persistence`'s own `JavAIPI.repository(Class, JavAIPersistenceConfig)`)
is the reference example for. Don't assume anything beyond what's in a given module's actual source and
tests reflects working code; check that module's README before relying on a claim from this file,
`doc/spec/`, or the whitepaper, all three of which describe the design and may be ahead of or behind any one
module's real implementation state at a given moment.

## Coding standard: static/global scope is the exception, not the default

A recurring anti-pattern surfaced and was removed from this codebase: a `final` class exposing only
`public static` methods over `private static` mutable state, configured via a `configureXxx(...)`-style
setter that every other static method implicitly reads. `JavAIPI` used to work this way
(`configurePersistence(...)` set an ambient "current config" `repository(Class)` read); `javai-tagging`'s
`JavAITagging` used to work this way too (a `configureTagging(...)`/`configureClassification(...)` pair
feeding every other static method). Both were replaced: `JavAIPI.repository(Class, JavAIPersistenceConfig)`
now takes its config as an explicit argument, and `JavAITagging` was deleted outright in favor of
`JavAITagRepository`, a plain instance constructed from its dependencies.

**The problem with the removed shape**: an ambient "configure once, read anywhere" pointer means any two
unrelated call sites in the same process can interfere with each other's configuration, the order code runs
in becomes load-bearing in ways that aren't visible at any single call site, and there is no way to have two
independently-configured instances of the same capability coexist (e.g. tagging against two different
backends at once) without inventing an escape hatch (this codebase's own now-removed
`activatePostgresTagging()`-style methods in `e2e-client-test` were exactly that escape hatch, made
unnecessary once the underlying facade stopped needing one).

**The standard going forward**: prefer instance-scoped state, constructed explicitly and passed as a
constructor or method argument, over static/global mutable state. This applies repo-wide, not just to the
two classes above.

**The one sanctioned exception**: `JavAIRuntime`'s embedding-provider/consistency-mode/concurrency-gate
configuration (`javai-model`) stays a static, process-wide concern — it is the "most basic vector provider /
runtime concern" this project treats as a deliberate carve-out, since every `@JavAIVectorizable` object in
the process needs to resolve the same embedding provider without threading a dependency through every woven
setter by hand. A narrow additional allow-list, each independently justified in its own module's docs, not
silently exempted:
- `EndpointRateLimiter` (`javai-vector`, shared with `javai-completion`) — a static, cross-instance/
  cross-module registry keyed by endpoint URL, deliberately: two independently-constructed providers hitting
  the same base URL must share 429-backoff state, or a per-instance rate limiter would defeat the entire
  point. See `javai-vector/README.md` and `doc/spec/vector-core.md`/`doc/spec/completion-fabric.md`'s own
  "Rate limiting" sections.
- `DirtyTrackingSupport`'s sequence-number generator (`javai-vector`) — one `AtomicLong`, no configure-style
  API at all, needed for a deadlock-free global lock-acquisition order in the persistence-flush
  subgraph-locking scheme.
- Bytecode-weaver installation (`JavAIWeaver`/`SupervisionWeaver`, `javai-substrate`/`javai-supervision`) —
  inherently JVM/classloader-global; there is no meaningful "instance" of an installed
  `Instrumentation`-based transformer to hold instead.
- `MonolithicContainer`'s idempotent-startup flag (`e2e-client-test`) — coordinates a real, singular
  OS-level Docker container, not conceptually instance/session state; test infrastructure, not library
  design.

**Known, tracked debt, not yet fixed**: `JavAISupervisionRuntime` (`javai-supervision`) has the same static-
facade-over-mutable-state shape (`SYNC_LISTENERS`/`ASYNC_LISTENERS` plus `register*`/`unregister*` statics)
as the two classes fixed above, and is *not* one of the sanctioned exceptions — it's flagged for a future
refactoring pass, not fixed here, because its dispatch entry points are called directly from ByteBuddy-woven
advice with no current path to an instance; removing the static there needs its own design (e.g. a
thread-local or classloader-scoped registry resolution strategy) rather than the same "just take the
dependency as a constructor argument" fix that worked for `JavAIPI`/`JavAITagging`.
