# JavAI Extensions

A set of extensions to Java that make semantic embedding vectors, knowledge-graph structure, and
LLM-codegen guidance first-class, automatically-maintained properties of the object model — not a new
language, and not infrastructure bolted on after the fact.

No existing ORM, object-graph mapper, or JVM language automatically re-embeds an object as a side effect of
mutating it. This project makes that a property of the object model itself: `@Vectorize`-annotated fields
re-embed on mutation, container and containment-graph "summary vectors" stay consistent via lazy,
cycle-safe propagation, and the resulting vectors are queryable — by similarity, by graph structure, or
both — against either the live in-process object graph or a persisted store.

JavAI Extensions is a validation testbed, not a bid to establish a permanent parallel ecosystem. Extensions
that prove valuable are candidates for proposal back to Java itself (a JEP, a widely-adopted library
convention, or both); extensions that don't earn adoption remain a useful, self-contained library.

**The hard interop rule (non-negotiable):** every class this project produces — woven, compiled, or
otherwise — must be a complete, correct, standalone standard JVM class file, runnable on any stock JDK 21+,
with the JavAI runtime as an ordinary classpath dependency. Any custom JVM, GPU dispatch, or modified
bytecode may exist only as an optional accelerated path with a required correct fallback, never as a
requirement for correctness.

## The six extension areas

| # | Directory | Extension area | Purpose |
|---|---|---|---|
| 1 | [`javai-annotations`](javai-annotations/README.md) | Codegen Guidance (+ shared annotation vocabulary) | Every annotation used across all six areas — plain definitions, no processing logic |
| 2 | [`javai-runtime`](javai-runtime/README.md) | Vector Core | Embedding calculation, dirty-state propagation, object-graph `query()` |
| 3 | [`javai-agent`](javai-agent/README.md) | Acceleration Substrate | ByteBuddy weaving that makes Vector Core/Collections real without a compiler |
| 4 | [`javai-collections`](javai-collections/README.md) | Vector Collections | `KnowledgeGraph`, `SubgraphResult`, `VectorIndex` |
| 5 | [`javai-persistence`](javai-persistence/README.md) | Persistence Bridge | JPA/Hibernate + Neo4j automation for vectorized, searchable persistence |
| 6 | [`javai-completion`](javai-completion/README.md) | Completion Fabric | Provider-agnostic RAG completions, wrapping Spring AI |

`javai-annotations` is the one module every other module depends on, directly or transitively — it also
carries Vector Core's and Vector Collections' vectorization/search-visibility annotation vocabulary
(`@Vectorize`, `@SearchVisibility`, `@Summary`, `@JavAIGraphNode`, `@JavAIEdge`), not just the Codegen
Guidance ones.

### Dependency graph

```
                     javai-agent (Acceleration Substrate)
                (compiler / weaver / invokedynamic / GPU dispatch)
                    |            |              |
                    v            v              v
          javai-runtime   javai-collections   javai-annotations
          (Vector Core)   (Vector Collections)  (Codegen Guidance)
                    |            |
                    |    +-------+-------+
                    v    v               v
          javai-persistence      javai-completion
          (Persistence Bridge)   (Completion Fabric)
```

Build order matches this graph: `javai-annotations` → `javai-runtime` → `javai-agent` → `javai-collections`
→ (`javai-persistence`, `javai-completion` — order between these two doesn't matter).

**A note on where things physically live vs. the conceptual area they belong to:** `JavAIVectorizable.query()`
returns `JavAIList<T>`, and `javai-collections` depends on `javai-runtime`, not the reverse — so
`JavAISortable`/`JavAIList`/`JavAISet`/`JavAIMap` physically live in `javai-runtime`, even though the
whitepaper discusses them as part of Vector Collections. See `javai-runtime/README.md` and
`javai-collections/README.md` for the full explanation.

## Building

All six modules live under one Maven reactor and build together — they're interdependent by design, not
meant to be built independently:

```
mvn install
```

Opening this repository root in IntelliJ IDEA imports all six modules as one project automatically, since
IntelliJ detects the root `pom.xml`.

## Where things are

- [`SPEC.md`](SPEC.md) — read this first. Complete orientation regardless of which module you're touching.
- [`CLAUDE.md`](CLAUDE.md) — instructions for Claude Code / agentic work in this repo, including the hard
  rule that only a human commits.
- `doc/spec/*.md` — one file per extension area, full primitive definitions and code examples. Kept current
  as the design evolves.
- [`doc/JAI_Whitepaper.docx`](doc/JAI_Whitepaper.docx) — the full design whitepaper: vision, prior-art
  research, roadmap, go/no-go. Source of truth for *rationale*; `SPEC.md`/`doc/spec/` are the source of
  truth for *current implementation-facing detail*.
- [`doc/JavAI_Codegen_Guidance.md`](doc/JavAI_Codegen_Guidance.md) — required reading before generating or
  modifying any code annotated with `@Requires`/`@Ensures`/`@Invariant`, `@Intent`,
  `@AgentWritable`/`@Frozen`/`@HumanOnly`, `@Nondeterministic`/`@Costly`, or `@Provenance`.

## Current status

Phase 0, pre-implementation. Repository structure and build scaffolding are in place; the annotation
vocabulary is fully defined and each module resolves and compiles against its dependencies, but none of the
six areas' real logic (weaving, the lifecycle state machine, `KnowledgeGraph`, persistence shims, the
completion layer) is implemented yet beyond placeholder smoke tests. The next concrete step is a minimal
ByteBuddy weaving spike in `javai-agent` — see that module's README and `CLAUDE.md`'s build-order section.

## License

GPLv3 — see [`LICENSE`](LICENSE).
