# JavAI Extensions — Project Instructions

This repository implements **JavAI Extensions**: a set of extensions to Java that make semantic embedding
vectors, knowledge-graph structure, and LLM-codegen guidance first-class, automatically-maintained
properties of the object model — not a new language, and not infrastructure bolted on after the fact.

Read `SPEC.md` before starting any work in this repository. It is short by design and gives complete
orientation regardless of which module you're touching. It links out to `doc/spec/*.md` for
area-specific depth, and to `doc/JAI_Whitepaper.docx` (the full design document, source of truth for
rationale) and `doc/JavAI_Codegen_Guidance.md` (agent-facing rules, described further down).

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

## The six extension areas, and where their code lives

| Area | Module | One-line purpose |
|---|---|---|
| Vector Core | `javai-runtime` | Embedding calculation, dirty-state propagation, object-graph `query()` |
| Persistence Bridge | `javai-persistence` | JPA/Hibernate + Neo4j automation for vectorized, searchable persistence |
| Completion Fabric | `javai-completion` | Provider-agnostic RAG completions, wrapping Spring AI |
| Vector Collections | `javai-collections` | `JavAIList`/`Set`/`Map`, `KnowledgeGraph`, `VectorIndex` |
| Codegen Guidance | `javai-annotations` (definitions only — see `doc/JavAI_Codegen_Guidance.md`) | Annotations governing what an LLM agent may read/write/trust |
| Acceleration Substrate | `javai-agent` | ByteBuddy weaving that makes Vector Core/Collections real without a compiler |

`javai-annotations` also carries Vector Core's and Vector Collections' annotation vocabulary (`@Vectorize`,
`@SearchVisibility`, `@Summary`, `@JavAIGraphNode`, `@JavAIEdge`, etc.) — it is the one module every other
module depends on, directly or transitively.

## Build order (matches the dependency graph in `SPEC.md`)

1. `javai-annotations` — no internal dependencies, unblocks everything else.
2. `javai-runtime` — depends on `javai-annotations`.
3. `javai-agent` — depends on `javai-annotations` + `javai-runtime` (it weaves calls into runtime hooks).
4. `javai-collections` — depends on `javai-runtime`.
5. `javai-persistence` and `javai-completion` — both depend on `javai-collections` (+ `javai-runtime`
   transitively); order between these two doesn't matter.

Prove the weaving mechanism itself (a toy `@Vectorize` class, a woven setter, `markDirty()`/`propagateDirty()`
firing correctly, lazy recomputation on next read) before building out `javai-runtime` and `javai-collections`
in full — it's the highest-novelty, highest-risk piece, and everything downstream assumes it works.

## Formal specs are test specs

`doc/spec/vector-core.md` contains a precise object lifecycle state machine (`Clean → FieldDirty →
EmbeddingRecomputing → Clean`, and separately `→ SummaryDirty → SummaryRecomputing → Clean`) and a
decay-weighted recursive formula for `summaryVector()`. Both are specified precisely enough to be written
as tests directly — state-transition tests, cycle-safety tests, duplicate-reference-stacking tests — before
or alongside the implementation they check, not as an afterthought.

## Single multi-module build

All six modules live under this one Maven reactor and are expected to build together (`mvn install` or
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
- `doc/JavAI_Codegen_Guidance.md` — read this before generating or modifying any code annotated with
  `@Requires`/`@Ensures`/`@Invariant`, `@Intent`, `@AgentWritable`/`@Frozen`/`@HumanOnly`,
  `@Nondeterministic`/`@Costly`, or `@Provenance`. It defines what an LLM agent is and isn't allowed to do
  around each of them, and applies to work in this repository, not just to JavAI's own users.
