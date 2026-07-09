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

## The seven extension areas

| # | Area | Purpose | Detail |
|---|---|---|---|
| 1 | **Vector Core** | Embedding calculation, object-graph `query()`, the mutation → dirty → recompute lifecycle | `doc/spec/vector-core.md` |
| 2 | **Persistence Bridge** | Automated JPA vectorization; persisted, searchable object graphs (Hibernate/pgvector + Neo4j) | `doc/spec/persistence-bridge.md` |
| 3 | **Completion Fabric** | Provider-agnostic RAG completions, wrapping Spring AI rather than competing with it | `doc/spec/completion-fabric.md` |
| 4 | **Vector Collections** | `JavAIList`/`Set`/`Map`, `KnowledgeGraph`, `VectorIndex` — vector-aware, parallel to `java.util` | `doc/spec/vector-collections.md` |
| 5 | **Codegen Guidance** | Annotations governing what an LLM coding agent may read, generate, or modify | `doc/spec/codegen-guidance.md` |
| 6 | **Acceleration Substrate** | The compiler/weaver/dispatch mechanism beneath Vector Core, Vector Collections, and Codegen Guidance | `doc/spec/acceleration-substrate.md` |
| 7 | **Agentic Supervision** | AoP-style method/constructor interception (sync, read-write + async, observation-only) enabling an LLM-backed listener to observe or intervene on execution | `doc/spec/agentic-supervision.md` |

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
```

Read arrows as "required by." Acceleration Substrate is the foundation for areas 1, 4, and 5 — it's the
compiler/weaver that actually implements Vector Core's interface and Vector Collections' graph types, and
the mechanism that turns Codegen Guidance's annotations into enforced behavior. Vector Core feeds Vector
Collections (`EmbeddingVector`/`similarityTo()` back cosine-distance sorting and graph-node similarity) and
Persistence Bridge (nothing to persist without it). Vector Collections feeds Persistence Bridge (a
`KnowledgeGraph` or `JavAIList` is what a repository query actually returns) and Completion Fabric
(`toContext()` is defined on Vector Collections' result types). Codegen Guidance is mostly a leaf, consumed
by the Substrate.

Agentic Supervision is deliberately *not* wired into this graph the way areas 1–6 are. It has its own
independent weaver rather than extending Acceleration Substrate's, so that its module (`javai-supervision`)
stays at the same early, cheap-to-prove tier as `javai-agent` instead of pulling Acceleration Substrate
downstream of Completion Fabric/Vector Collections. An "Agentic Listener" — a supervision listener whose
decision is LLM-backed and grounded in RAG over the object graph — is documented as an application-level
composition of Agentic Supervision's generic listener interfaces with Completion Fabric and Vector
Core/Collections, not a hard dependency any of those modules take on each other. See
`doc/spec/agentic-supervision.md`'s "Relationship to the rest of JavAI Extensions" section for the full
reasoning.

Practical implication: Acceleration Substrate and Vector Core are where a design mistake is most expensive
to unwind, since three or four other areas build on them. Persistence Bridge and Completion Fabric are the
safest place to experiment. Agentic Supervision, being structurally isolated, is a similarly safe, low-blast-radius
place to experiment — a mistake there doesn't ripple into the other six areas.

## Phase 0: the Golden Contract

Phase 0 targets full functional completeness — every annotation, every collection, every auto-generated
method, both persistence backends, and the entire Completion/RAG API — delivered via runtime bytecode
weaving instead of a real compiler, because nothing in the functional surface actually requires a compiler
or a GPU to work correctly, only to work fast. Seven components, each a plain library, each independently
buildable in the order below:

| Component | Mechanism | Delivers |
|---|---|---|
| `javai-annotations` | Plain annotation definitions, no processing logic | Full annotation vocabulary across all seven areas |
| `javai-agent` (Acceleration Substrate) | ByteBuddy weaving, load-time agent or build-time Maven/Gradle plugin | Implements `JavAIVectorizable`/`JavAIGraphNode` on any annotated class |
| `javai-supervision` (Agentic Supervision) | ByteBuddy weaving, its own independent installer | Sync (blocking, read-write) + async (fire-and-forget, observation-only) method/constructor interception |
| `javai-runtime` (Vector Core) | Pure Java library | `EmbeddingVector`, back-edge propagation, `query()`, CPU similarity, local embedding provider |
| `javai-collections` (Vector Collections) | Pure Java library, backed by `javai-runtime` | `JavAIList`/`Set`/`Map`, `VectorIndex`, `KnowledgeGraph` + `SubgraphResult` |
| `javai-persistence` (Persistence Bridge) | Hibernate-based shim + Neo4j shim | Both persistence backends real in Phase 0, not aspirational |
| `javai-completion` (Completion Fabric) | Wraps Spring AI `ChatModel` | Full RAG API — `PromptContext`, `CompletionRequest`/`Result`, `toContext()`, `complete()`/`completeStreaming()` |

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

Phase 0, actively underway. `javai-annotations`, `javai-runtime` (Vector Core, now including the
`Contextable`/`PromptContext` RAG-integration primitives — see below), `javai-agent` (Acceleration
Substrate's weaving, including the full lifecycle state machine and summary-vector propagation),
`javai-collections` (Vector Collections), and `javai-persistence` (Persistence Bridge, both backends,
including model-versioning/reindex/revert) all have real, tested implementations — see each module's own
README for exactly what's covered and what's still deliberately out of scope. `javai-completion` (Completion
Fabric) has both its connector layer (`Cortex`, six providers: OpenAI, Anthropic, Groq, vLLM, Ollama,
Replicate; `CompletionRequest`/`CompletionResult`, provider-specific tuning parameters) and its
RAG-integration half real and tested: grounding a completion in a `JavAIList`/`Set`/`Map` via `PromptContext`
(`Contextable`, `ContextableObject`) — these two primitives live in `javai-runtime`, not `javai-completion`,
for a real dependency-direction reason documented in both modules' own READMEs. `KnowledgeGraph`/
`SubgraphResult` (`javai-collections`) don't implement `Contextable` yet — deferred pending a cycle-safe
design, since GSON's default marshalling has no cycle guard and those types are graph-shaped by design; see
`javai-completion`'s own README for exactly what's covered. `javai-supervision` (Agentic Supervision) is
still scaffolding only — annotations and/or interfaces defined,
no weaving or dispatch logic written yet. Don't assume anything beyond what's in a given module's actual
source and tests reflects working code; check that module's README before relying
on a claim from this file, `doc/spec/`, or the whitepaper, all three of which describe the design and may
be ahead of or behind any one module's real implementation state at a given moment.
