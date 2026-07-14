# Module dependency graph

The physical Maven reactor graph — eight modules realizing the seven conceptual extension areas
`SPEC.md` describes. This is deliberately a separate artifact from `SPEC.md`'s own dependency-graph diagram:
that one is organized by *conceptual area* (per the whitepaper); this one is organized by *physical module*,
since one conceptual area (Vector Core) is realized by two modules (`javai-vector` + `javai-model`), and one
module (`javai-model`) is the physical home for pieces of three different conceptual areas at once. See
`javai-model`'s own package-info.java for the full reasoning behind that split.

```
                                  javai-annotations
                                  (no dependencies)
                                          |
                                          v
                                    javai-vector
                        (EmbeddingVector, VectorMath, embedding
                         providers, dirty-tracking primitives)
                                          |
                                          v
                                    javai-model
                (JavAIVectorizable/JavAIRuntime, JavAIList/Set/Map +
                 concrete collections, Contextable/PromptContext)
                          |                           |
                          v                           v
                  javai-substrate              javai-collections
              (ByteBuddy weaver --      (KnowledgeGraph/SubgraphResult,
               depends on both           VectorIndex -- depends on both
               javai-vector AND          javai-vector AND javai-model)
               javai-model)                       |
                                          +--------+--------+
                                          v                 v
                                  javai-persistence   javai-completion
                                (Hibernate + Neo4j)   (Cortex, wraps Spring AI)

  javai-supervision — standalone, depends only on javai-annotations. Its own
  independent weaver; not fed by javai-substrate and doesn't feed anything
  else at the module level. See doc/spec/agentic-supervision.md.
```

## Build order

1. `javai-annotations` — no internal dependencies.
2. `javai-vector` — depends only on `javai-annotations`.
3. `javai-model` — depends on `javai-annotations` + `javai-vector`.
4. `javai-substrate` and `javai-supervision` (parallel) — `javai-substrate` depends on `javai-annotations` +
   `javai-vector` + `javai-model`; `javai-supervision` depends only on `javai-annotations`.
5. `javai-collections` — depends on `javai-vector` + `javai-model`.
6. `javai-persistence` and `javai-completion` (parallel) — both depend on `javai-collections` (+
   `javai-vector`/`javai-model` transitively, and directly where their own source references those types
   — see the table below).

`e2e-client-test` is a ninth, standalone project (not in the root reactor's `<modules>`), depending on all
eight as ordinary published artifacts.

## Per-module direct dependencies (confirmed by grepping actual imports, not assumed)

| Module | Depends on (`io.github.dcaudell:*`) | Depends on (third-party) |
|---|---|---|
| `javai-annotations` | — | — |
| `javai-vector` | `javai-annotations` | — |
| `javai-model` | `javai-annotations`, `javai-vector` | `gson` |
| `javai-substrate` | `javai-annotations`, `javai-vector`, `javai-model` | `byte-buddy`, `byte-buddy-agent` |
| `javai-supervision` | `javai-annotations` | `byte-buddy`, `byte-buddy-agent` |
| `javai-collections` | `javai-vector`, `javai-model` | — |
| `javai-persistence` | `javai-collections`, `javai-vector`, `javai-model` | `hibernate-core`, `hibernate-vector`, `postgresql`, `neo4j-java-driver` |
| `javai-completion` | `javai-collections`, `javai-vector`, `javai-model` | `spring-ai-*`, `gson`, `handlebars` |
| `e2e-client-test` (standalone) | `javai-annotations`, `javai-vector`, `javai-model`, `javai-substrate`, `javai-collections`, `javai-persistence`, `javai-completion` | `datafaker`, `testcontainers`, `junit` |

Every module that references `EmbeddingVector`/`VectorMath`/`JavAIDirtyTracking`/embedding providers directly
declares `javai-vector`; every module that references `JavAIVectorizable`/`JavAIRuntime`/`JavAIList`-family
types/`Contextable`/`PromptContext` directly declares `javai-model` — several modules (`javai-substrate`,
`javai-persistence`, `javai-completion`, `javai-collections`) need both, since their own source touches
types from each side of the split.

## Why the split, in one paragraph

`JavAIVectorizable.query()` returns `JavAIList<T>`, and `JavAIList` in turn `extends JavAIVectorizable`
right back — a genuine mutual reference that can never be split across a module boundary without either an
illegal cycle or an API change. Confirmed empirically that this isn't fixable by extracting `query()` into
its own interface: `JavAIRuntime.summaryVector()`'s cycle-safe walk checks
`value instanceof JavAIVectorizable` to decide whether a `@Summary`-annotated collection field should
contribute its own `summaryVector()` (real, tested behavior), so `JavAIList` needs the *whole*
`JavAIVectorizable` contract, not just `query()`. Since `Contextable`/`PromptContext` are wired into
`JavAIList` the same way (`JavAIList implements Contextable`, `PromptContext`'s own field is
`JavAIList<Contextable> entries`), the whole cluster — `JavAIVectorizable`, `JavAIRuntime`,
`JavAIList`/`Set`/`Map` + concretes, `Contextable`, `PromptContext`, `ContextableObject` — is irreducibly one
unit, physically homed in `javai-model`. What's left in `javai-vector` is exactly the subset with zero
reference to any of that: `EmbeddingVector`, `VectorMath`, embedding providers, and dirty-tracking
primitives.
