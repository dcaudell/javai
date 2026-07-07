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

## The six extension areas

| # | Area | Purpose | Detail |
|---|---|---|---|
| 1 | **Vector Core** | Embedding calculation, object-graph `query()`, the mutation → dirty → recompute lifecycle | `doc/spec/vector-core.md` |
| 2 | **Persistence Bridge** | Automated JPA vectorization; persisted, searchable object graphs (Hibernate/pgvector + Neo4j) | `doc/spec/persistence-bridge.md` |
| 3 | **Completion Fabric** | Provider-agnostic RAG completions, wrapping Spring AI rather than competing with it | `doc/spec/completion-fabric.md` |
| 4 | **Vector Collections** | `JavAIList`/`Set`/`Map`, `KnowledgeGraph`, `VectorIndex` — vector-aware, parallel to `java.util` | `doc/spec/vector-collections.md` |
| 5 | **Codegen Guidance** | Annotations governing what an LLM coding agent may read, generate, or modify | `doc/spec/codegen-guidance.md` |
| 6 | **Acceleration Substrate** | The compiler/weaver/dispatch mechanism beneath everything else — never part of any area's public surface | `doc/spec/acceleration-substrate.md` |

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
```

Read arrows as "required by." Acceleration Substrate is the foundation — it's the compiler/weaver that
actually implements Vector Core's interface and Vector Collections' graph types, and the mechanism that
turns Codegen Guidance's annotations into enforced behavior. Vector Core feeds Vector Collections
(`EmbeddingVector`/`similarityTo()` back cosine-distance sorting and graph-node similarity) and Persistence
Bridge (nothing to persist without it). Vector Collections feeds Persistence Bridge (a `KnowledgeGraph` or
`JavAIList` is what a repository query actually returns) and Completion Fabric (`toContext()` is defined on
Vector Collections' result types). Codegen Guidance is mostly a leaf, consumed by the Substrate.

Practical implication: Acceleration Substrate and Vector Core are where a design mistake is most expensive
to unwind, since three or four other areas build on them. Persistence Bridge and Completion Fabric are the
safest place to experiment.

## Phase 0: the Golden Contract

Phase 0 targets full functional completeness — every annotation, every collection, every auto-generated
method, both persistence backends, and the entire Completion/RAG API — delivered via runtime bytecode
weaving instead of a real compiler, because nothing in the functional surface actually requires a compiler
or a GPU to work correctly, only to work fast. Six components, each a plain library, each independently
buildable in the order below:

| Component | Mechanism | Delivers |
|---|---|---|
| `javai-annotations` | Plain annotation definitions, no processing logic | Full annotation vocabulary across all six areas |
| `javai-agent` (Acceleration Substrate) | ByteBuddy weaving, load-time agent or build-time Maven/Gradle plugin | Implements `JavAIVectorizable`/`JavAIGraphNode` on any annotated class |
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

Phase 0, pre-implementation. Repository structure and build scaffolding are in place; the ByteBuddy
weaving spike described in `CLAUDE.md`'s build order has not yet been written. Don't assume anything beyond
what's in this repo's actual source and tests reflects working code — the whitepaper describes the design,
not a shipped implementation.
