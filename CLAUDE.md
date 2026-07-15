# JavAI Extensions — Project Instructions

This repository implements **JavAI Extensions**: a set of extensions to Java that make semantic embedding
vectors, knowledge-graph structure, and LLM-codegen guidance first-class, automatically-maintained
properties of the object model — not a new language, and not infrastructure bolted on after the fact.

Read `SPEC.md` before starting any work in this repository. It is short by design and gives complete
orientation regardless of which module you're touching. It links out to `doc/spec/*.md` for
area-specific depth, and to `doc/JAI_Whitepaper.docx` (the full design document, source of truth for
rationale) and `doc/ai-guidance/JavAI_Codegen_Guidance.md` (agent-facing rules, described further down).
Note `doc/ai-guidance/` also has a sibling file, `JavAI_Usage_Guide.md`, aimed at a different audience —
an AI helping someone *consume* JavAI Extensions as a dependency in their own project, not contribute to
this repository — not usually relevant to work done here, but good to know it exists.

## Hard rule: never run `git commit`

**Claude may run any command needed for development — build, test, format, lint, `git status`, `git diff`,
`git add`, `git log`, branch operations, everything — except creating a commit.** Committing (`git commit`
in any form: `-m`, `-a`, `--amend`, interactive, or otherwise) is reserved exclusively for the human owner
of this repository. This is enforced technically via `.claude/settings.json`'s permission deny rule, not
just requested here — but treat it as an absolute rule regardless: if a task seems to require a commit to
be "done," stop short of it, leave the working tree staged/ready, and say so explicitly rather than finding
a workaround. The same spirit extends to other commit-creating operations (`git merge` without
`--no-commit`, `git rebase --continue`, `git cherry-pick`, `git revert` without `--no-commit`) — prefer
leaving those to the human as well, even though only `git commit` itself is technically blocked.

## What this project is (one paragraph)

JavAI Extensions exists to validate this design space against real usage, not to establish a permanent
parallel ecosystem. Every JVM-verifiable class it produces must run correctly on stock JDK 21+ with the
JavAI runtime as an ordinary classpath dependency — no custom JVM, GPU backend, or modified bytecode is
ever allowed to be *required* for correctness, only optional acceleration with a required correct
fallback. Individual extensions that prove valuable are candidates for proposal back to Java itself;
extensions that don't earn adoption remain a useful, self-contained library. See `SPEC.md` for what that
means concretely.

## The eight extension areas, and where their code lives

| Area | Module | One-line purpose |
|---|---|---|
| Vector Core | `javai-vector` + `javai-model` (see note below) | Embedding calculation, dirty-state propagation, object-graph `query()` |
| Persistence Bridge | `javai-persistence` | JPA/Hibernate + Neo4j + Spring Data MongoDB automation for vectorized, searchable persistence |
| Completion Fabric | `javai-completion` | Provider-agnostic RAG completions, wrapping Spring AI |
| Vector Collections | `javai-collections` (interfaces in `javai-model` — see note below) | `JavAIList`/`Set`/`Map`, `KnowledgeGraph`, `VectorIndex` |
| Codegen Guidance | `javai-annotations` (definitions only — see `doc/ai-guidance/JavAI_Codegen_Guidance.md`) | Annotations governing what an LLM agent may read/write/trust |
| Acceleration Substrate | `javai-substrate` | ByteBuddy weaving that makes Vector Core/Collections real without a compiler |
| Agentic Supervision | `javai-supervision` | AoP-style sync (blocking, read-write) + async (fire-and-forget) interception, its own independent weaver |
| Tagging | `javai-tagging` | `@Taggable` objects, recursive Tags/TagSets, LLM-based classification, tag-collection similarity search — see `doc/spec/tagging.md` |

**`javai-model` is a physical-only module, not an eighth conceptual area.** It's the formalized home for
whatever has to live upstream of `javai-collections`/`javai-completion` for compile-order reasons rather
than because it's vector/embedding core: `JavAIVectorizable`/`JavAIRuntime` (Vector Core's contract and
engine — dependency-direction hostages of `JavAIList`, since `JavAIVectorizable.query()` returns
`JavAIList<T>` and `JavAIList` extends `JavAIVectorizable` right back), the `JavAIList`/`Set`/`Map`
interfaces and concrete collections (Vector Collections), and `Contextable`/`PromptContext` (Completion
Fabric's RAG primitives). `javai-vector` holds only what has zero reference to any of that: `EmbeddingVector`,
the CPU similarity backend, embedding providers, and dirty-tracking primitives. See `doc/module-dependency-graph.md`
for the full physical module graph, and `javai-model`'s own package-info.java for the traced-not-assumed
reasoning behind the split.

`javai-annotations` also carries Vector Core's and Vector Collections' annotation vocabulary (`@Vectorize`,
`@SearchVisibility`, `@Summary`, `@JavAIGraphNode`, `@JavAIEdge`, etc.), Agentic Supervision's
(`@SyncSupervision`, `@AsyncSupervision`, `SupervisionPointcut`), and Tagging's (`@Taggable`, `@TagIgnore`
— both unwoven markers, see `doc/spec/tagging.md`'s "Orthogonality" section for why) — it is the one module
every other module depends on, directly or transitively.

## Build order (matches the dependency graph in `SPEC.md`)

1. `javai-annotations` — no internal dependencies, unblocks everything else.
2. `javai-vector` — depends only on `javai-annotations`.
3. `javai-model` — depends on `javai-annotations` + `javai-vector`.
4. `javai-substrate` and `javai-supervision` — `javai-substrate` depends on `javai-annotations` +
   `javai-vector` + `javai-model` (it weaves calls into both); `javai-supervision` depends only on
   `javai-annotations` (its own independent weaver — see `doc/spec/agentic-supervision.md` for why it
   doesn't extend `javai-substrate`). These two can proceed in parallel once `javai-model` exists.
5. `javai-collections` — depends on `javai-vector` + `javai-model`.
6. `javai-persistence` and `javai-completion` — both depend on `javai-collections` (+ `javai-vector`/
   `javai-model` transitively); order between these two doesn't matter.
7. `javai-tagging` — depends on `javai-collections`, `javai-persistence`, *and* `javai-completion` all
   three, built last. Unlike every module before it, it also needs `javai-substrate` directly (not just
   transitively) to weave its own shipped `Tag`/`TagSet` classes at build time — see
   `doc/spec/tagging.md`'s "this module weaves itself" for why that's a new situation this project hasn't
   hit before.

Prove the weaving mechanism itself (a toy `@Vectorize` class, a woven setter, `markDirty()`/`propagateDirty()`
firing correctly, lazy recomputation on next read) before building out `javai-model` and `javai-collections`
in full — it's the highest-novelty, highest-risk piece, and everything downstream assumes it works.
`javai-supervision`'s own weaving spike (a toy `@SyncSupervision` method, a listener registered and actually
firing, PRE-stage argument rewrite proven end to end) is a second, independent risk spike — worth proving
early too, but it doesn't block or get blocked by Vector Core's.

## Formal specs are test specs

`doc/spec/vector-core.md` contains a precise object lifecycle state machine (`Clean → FieldDirty →
EmbeddingRecomputing → Clean`, and separately `→ SummaryDirty → SummaryRecomputing → Clean`) and a
decay-weighted recursive formula for `summaryVector()`. Both are specified precisely enough to be written
as tests directly — state-transition tests, cycle-safety tests, duplicate-reference-stacking tests — before
or alongside the implementation they check, not as an afterthought. `doc/spec/agentic-supervision.md`
similarly specifies the sync/async dispatch ordering (sync tier commits fully before the async tier
observes) precisely enough to test directly, and states a hard rule worth a test of its own: this project's
own supervision-runtime classes must never carry `@SyncSupervision`/`@AsyncSupervision`.

## Single multi-module build

All eight modules live under this one Maven reactor and are expected to build together (`mvn install` or
`mvn verify` from the repo root), not independently — they're interdependent by design, per the dependency
graph above. Opening this repository root in IntelliJ IDEA imports all modules as one project automatically,
since IntelliJ detects the root `pom.xml`.

## Where things are

- `SPEC.md` — start here.
- `doc/spec/` — one markdown file per extension area, full primitive definitions and code examples.
- `doc/JAI_Whitepaper.docx` — the full design whitepaper (vision, prior-art research, roadmap, go/no-go).
  Treat it as the source of truth for *rationale*; `SPEC.md`/`doc/spec/` are the source of truth for
  *current implementation-facing detail*, since they'll be kept current as the design evolves and the
  docx will not be re-exported for every small change.
- `doc/ai-guidance/JavAI_Codegen_Guidance.md` — read this before generating or modifying any code annotated
  with `@Requires`/`@Ensures`/`@Invariant`, `@Intent`, `@AgentWritable`/`@Frozen`/`@HumanOnly`,
  `@Nondeterministic`/`@Costly`, or `@Provenance`. It defines what an LLM agent is and isn't allowed to do
  around each of them, and applies to work in this repository, not just to JavAI's own users.
- `doc/ai-guidance/JavAI_Usage_Guide.md` — the sibling file in that same directory, aimed at an AI helping
  someone *consume* JavAI Extensions as a Maven dependency in a separate project (capabilities, the full
  annotation vocabulary, every auto-generated/woven method, installation/activation steps). Not usually
  relevant to work done in this repository itself.
