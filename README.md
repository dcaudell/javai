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

## The seven extension areas

| # | Directory | Extension area | Purpose |
|---|---|---|---|
| 1 | [`javai-annotations`](javai-annotations/README.md) | Codegen Guidance (+ shared annotation vocabulary) | Every annotation used across all seven areas — plain definitions, no processing logic |
| 2 | [`javai-vector`](javai-vector/README.md) + [`javai-model`](javai-model/README.md) | Vector Core | Embedding calculation, dirty-state propagation, object-graph `query()` — physically two modules, see note below |
| 3 | [`javai-substrate`](javai-substrate/README.md) | Acceleration Substrate | ByteBuddy weaving that makes Vector Core/Collections real without a compiler |
| 4 | [`javai-supervision`](javai-supervision/README.md) | Agentic Supervision | AoP-style sync (blocking, read-write) + async (fire-and-forget) interception, its own independent weaver |
| 5 | [`javai-collections`](javai-collections/README.md) | Vector Collections | `KnowledgeGraph`, `SubgraphResult`, `VectorIndex` (interfaces `JavAIList`/`Set`/`Map` live in `javai-model`) |
| 6 | [`javai-persistence`](javai-persistence/README.md) | Persistence Bridge | JPA/Hibernate + Neo4j automation for vectorized, searchable persistence |
| 7 | [`javai-completion`](javai-completion/README.md) | Completion Fabric | Provider-agnostic RAG completions, wrapping Spring AI (`Contextable`/`PromptContext` live in `javai-model`) |

`javai-annotations` is the one module every other module depends on, directly or transitively — it also
carries Vector Core's and Vector Collections' vectorization/search-visibility annotation vocabulary
(`@Vectorize`, `@SearchVisibility`, `@Summary`, `@JavAIGraphNode`, `@JavAIEdge`) and Agentic Supervision's
(`@SyncSupervision`, `@AsyncSupervision`, `SupervisionPointcut`), not just the Codegen Guidance ones.

### Dependency graph

```
                     javai-substrate (Acceleration Substrate)
                (compiler / weaver / invokedynamic / GPU dispatch)
                    |            |              |
                    v            v              v
       javai-vector+model  javai-collections  javai-annotations
          (Vector Core)   (Vector Collections)  (Codegen Guidance)
                    |            |
                    |    +-------+-------+
                    v    v               v
          javai-persistence      javai-completion
          (Persistence Bridge)   (Completion Fabric)

          javai-supervision (Agentic Supervision) — standalone, depends only
          on javai-annotations, its own independent weaver. Not fed by
          javai-substrate and doesn't feed anything else at the module level;
          an LLM-backed listener composes with javai-completion/javai-vector/
          javai-model/javai-collections at the application level instead. See
          doc/spec/agentic-supervision.md.
```

See [`doc/module-dependency-graph.md`](doc/module-dependency-graph.md) for the full, precise 8-module
physical dependency graph (this diagram groups `javai-vector`/`javai-model` together for readability against
the seven-*conceptual*-area framing).

Build order matches this graph: `javai-annotations` → `javai-vector` → `javai-model` → (`javai-substrate`,
`javai-supervision` in parallel — the latter depends only on annotations, not on the former) →
`javai-collections` → (`javai-persistence`, `javai-completion` — order between these two doesn't matter).

**A note on where things physically live vs. the conceptual area they belong to:** `JavAIVectorizable.query()`
returns `JavAIList<T>`, and `javai-collections` depends on `javai-model`, not the reverse — so
`JavAIVectorizable`/`JavAIRuntime`/`JavAISortable`/`JavAIList`/`JavAISet`/`JavAIMap` (plus
`Contextable`/`PromptContext`, for the identical reason) physically live in `javai-model`, even though the
whitepaper discusses them as part of Vector Collections/Completion Fabric. See `javai-model/README.md` and
`javai-collections/README.md` for the full explanation.

## Building

All eight modules live under one Maven reactor and build together — they're interdependent by design, not
meant to be built independently:

```
mvn install
```

Opening this repository root in IntelliJ IDEA imports all eight modules as one project automatically, since
IntelliJ detects the root `pom.xml`.

## How to install

The above is for building *this* repository. To add JavAI Extensions to your own project instead, from
standard public Maven repositories:

These instructions install the **full module set** — all seven extension areas — rather than picking and
choosing. You can technically get away with fewer modules if you only want one or two capabilities, but the
whole set is small, every module is designed to interoperate with the others, and not having to reason
about which subset you need is one less decision to make.

### Automated install

**Do not copy this repository's own `SPEC.md`** — that file orients a *contributor* to this repo's own
8-module Phase 0 build, not a downstream consumer, and would just confuse an assistant reading it in your
project. Copy only the [`doc/ai-guidance/`](doc/ai-guidance/README.md) directory into your project (e.g.
`docs/javai-guidance/`) — it's the one piece written specifically for this, and is self-contained — then
tell Claude Code (or another AI coding assistant) something like:

> Read `docs/javai-guidance/README.md` and follow `JavAI_Usage_Guide.md` to install the full JavAI
> Extensions module set in this project.

`JavAI_Usage_Guide.md` has the actual dependency coordinates, the full annotation vocabulary, every method a
woven class gains at runtime, and the exact steps to activate the weaver correctly. Following it, an
assistant can add all the dependencies, wire up both weavers, and stand up the reference Docker environment
(see below) that covers every module's runtime needs at once.

### Manual install

1. **Add all seven modules** as dependencies (`javai-annotations` comes along transitively — no need to
   declare it directly):

   ```xml
   <dependency>
     <groupId>io.github.dcaudell</groupId>
     <artifactId>javai-vector</artifactId>
     <version>0.1.1</version> <!-- match the current release -->
   </dependency>
   <dependency>
     <groupId>io.github.dcaudell</groupId>
     <artifactId>javai-model</artifactId>
     <version>0.1.1</version>
   </dependency>
   <dependency>
     <groupId>io.github.dcaudell</groupId>
     <artifactId>javai-substrate</artifactId>
     <version>0.1.1</version>
   </dependency>
   <dependency>
     <groupId>io.github.dcaudell</groupId>
     <artifactId>javai-supervision</artifactId>
     <version>0.1.1</version>
   </dependency>
   <dependency>
     <groupId>io.github.dcaudell</groupId>
     <artifactId>javai-collections</artifactId>
     <version>0.1.1</version>
   </dependency>
   <dependency>
     <groupId>io.github.dcaudell</groupId>
     <artifactId>javai-persistence</artifactId>
     <version>0.1.1</version>
   </dependency>
   <dependency>
     <groupId>io.github.dcaudell</groupId>
     <artifactId>javai-completion</artifactId>
     <version>0.1.1</version>
   </dependency>
   ```

2. **Install both weavers** before any annotated class is loaded — as early as possible in `main()`, or,
   for a JUnit 5 test suite, from a `LauncherSessionListener` (not `@BeforeAll`, which runs too late):

   ```java
   Instrumentation instrumentation = net.bytebuddy.agent.ByteBuddyAgent.install();
   dev.xtrafe.javai.substrate.JavAIWeaver.install(instrumentation);         // Vector Core
   dev.xtrafe.javai.supervision.SupervisionWeaver.install(instrumentation); // Agentic Supervision
   ```

   and add `-Djdk.attach.allowAttachSelf=true` to the JVM launching that process (self-attach is disabled by
   default on JDK 9+).

3. **Stand up the runtime backends** the full set needs: an embedding-model provider (Vector Core), Postgres
   and/or Neo4j (Persistence Bridge), and a completion provider (Completion Fabric). Agentic Supervision and
   the woven Vector Core mechanism itself need nothing beyond step 2.

   The straightforward way to get all three at once: this repository ships a reference Dockerfile
   (`e2e-client-test/docker/Dockerfile`) bundling Postgres+pgvector, Neo4j, and Ollama — with both a
   reference embedding model and a chat-completion model already baked in — into one container. It's not
   published to a registry; build it locally the first time (a genuinely large, slow, one-time image
   build), then just restart the same container on every subsequent run:

   ```sh
   cd e2e-client-test
   docker build -t javai-e2e-monolithic:latest -f docker/Dockerfile docker
   docker run -d --name javai-e2e-monolithic \
     -p 15432:5432 -p 17474:7474 -p 17687:7687 -p 21434:11434 \
     javai-e2e-monolithic:latest
   # next time: docker start javai-e2e-monolithic
   ```

   Postgres on `localhost:15432`, Neo4j on `17474` (HTTP)/`17687` (Bolt), Ollama on `21434`. See
   `e2e-client-test/README.md`'s "Persistent container" section for the full lifecycle notes (including how
   to force a rebuild after editing the Dockerfile) — this is the same container `e2e-client-test`'s own
   tests reuse, not a separate quick-start-only artifact.

4. **Or configure your own embedding and completion providers**, from whichever vendors you prefer, instead
   of the bundled Ollama-in-a-container from step 3. The two are independent — neither has a single
   "one setting for everything" switch:

   - **Embedding provider** (Vector Core) is registered once, globally, before anything calls `vector()`:

     ```java
     JavAIRuntime.configureEmbeddingProvider(
         new EmbeddingProviderOllama(URI.create("http://localhost:11434"), "qwen3-embedding:0.6b"));
     // or: new EmbeddingProviderTextEmbeddingsInference(URI.create("http://localhost:8080"), "your-model-label")
     // or: new EmbeddingProviderOpenAI(apiKey, "text-embedding-3-small")
     // or: new EmbeddingProviderVLlm(URI.create("http://your-vllm-host:8000"), "your-model")
     // or: EmbeddingProviderReplicate.builder().apiToken(apiToken).model("owner/model-name").build()
     // or: implement JavAIEmbeddingProvider yourself against any other vendor's embedding API
     ```

     Note: unlike the `Cortex` family (below), there's no `EmbeddingProviderAnthropic`/`EmbeddingProviderGroq`
     — neither vendor exposes a native embeddings API (Anthropic recommends Voyage AI instead; Groq's API has
     no embeddings endpoint at all). See `javai-vector/README.md`'s "Hosted-vendor providers" section.

     Without an explicit call, `JavAIRuntime` falls back to the `javai.embedding.endpoint`/
     `javai.embedding.model` system properties (constructing an `EmbeddingProviderTextEmbeddingsInference`);
     with neither the call nor those properties set, the first `vector()` call throws.

   - **Completion provider** (Completion Fabric) has no global registration — construct whichever `Cortex`
     you want, wherever you use it:

     ```java
     Cortex cortex = CortexOpenAI.builder().apiKey(System.getenv("OPENAI_API_KEY")).model("gpt-4.1").build();
     // or CortexAnthropic / CortexGroq / CortexVLlm / CortexOllama / CortexReplicate -- same builder shape
     ```

     Constructing several `Cortex`es side by side, local and remote, is normal — each is a plain object, not
     something you register.

Full detail on every step above — the complete woven-method reference, the full annotation vocabulary, and
the exact timing trap that silently leaves a class unwoven if the weaver installs too late — lives in
[`doc/ai-guidance/JavAI_Usage_Guide.md`](doc/ai-guidance/JavAI_Usage_Guide.md).

## Where things are

- [`SPEC.md`](SPEC.md) — read this first. Complete orientation regardless of which module you're touching.
- [`CLAUDE.md`](CLAUDE.md) — instructions for Claude Code / agentic work in this repo, including the hard
  rule that only a human commits.
- `doc/spec/*.md` — one file per extension area, full primitive definitions and code examples. Kept current
  as the design evolves.
- [`doc/JAI_Whitepaper.docx`](doc/JAI_Whitepaper.docx) — the full design whitepaper: vision, prior-art
  research, roadmap, go/no-go. Source of truth for *rationale*; `SPEC.md`/`doc/spec/` are the source of
  truth for *current implementation-facing detail*.
- [`doc/ai-guidance/`](doc/ai-guidance/README.md) — the **AI Guidance Package**: self-contained
  documentation meant to be dropped into, or referenced from, a *downstream* project that depends on JavAI
  Extensions as an ordinary Maven library (as opposed to this file and `CLAUDE.md`, which are for
  contributors working inside this repository). Covers capabilities, the full annotation vocabulary, every
  auto-generated/woven method, and installation/activation steps
  ([`JavAI_Usage_Guide.md`](doc/ai-guidance/JavAI_Usage_Guide.md)), plus the narrower Codegen Guidance
  meta-annotation rules ([`JavAI_Codegen_Guidance.md`](doc/ai-guidance/JavAI_Codegen_Guidance.md)) — required
  reading before generating or modifying any code annotated with `@Requires`/`@Ensures`/`@Invariant`,
  `@Intent`, `@AgentWritable`/`@Frozen`/`@HumanOnly`, `@Nondeterministic`/`@Costly`, or `@Provenance`.

## Current status

Phase 0, actively underway, verified against real embeddings and real backing stores, not just placeholder
smoke tests:

- **`javai-annotations`** — the full annotation vocabulary across all seven areas.
- **`javai-vector`** + **`javai-model`** (Vector Core, physically split — see above) — the full object
  lifecycle (`FieldDirty`/`SummaryDirty`, lazy recompute), `query()`, real embedding providers (Ollama and
  Hugging Face's text-embeddings-inference), the concrete `JavAIArrayList`/`JavAILinkedHashSet`/
  `JavAILinkedHashMap` collections, and the `Contextable`/`PromptContext` RAG-integration primitives.
- **`javai-substrate`** (Acceleration Substrate) — a full ByteBuddy weaver: multi-field `vector()`,
  `summaryVector()` propagation through both single references and collections, `query()`, cycle safety,
  `@SearchVisibility`/`@VectorizeIgnore`, and inherited-field support via synthesized setter overrides.
- **`javai-collections`** (Vector Collections) — `VectorIndex` and `KnowledgeGraph`/`SubgraphResult`
  (hybrid similarity + structural queries), both hand-written and reflection-based, not woven.
- **`javai-persistence`** (Persistence Bridge) — both backends real: Postgres+pgvector (one table per
  embedding model, so a provider swap needs no schema migration) and Neo4j (native vector index, one
  model-qualified property per model), a `JavAIRepository<T>` dynamic-proxy contract, and `reindexAll()`
  for re-embedding an existing store after a provider swap, reverting non-destructively.
- **`javai-completion`** (Completion Fabric) — real and tested: six `Cortex` providers (OpenAI, Anthropic,
  Groq, vLLM, Ollama, Replicate), `CompletionRequest`/`CompletionResult`, provider-specific tuning
  parameters, Handlebars-based prompt templating (`CompletionRequest.render()`), and the RAG-integration
  half grounding a completion in `PromptContext`.
- **`javai-supervision`** (Agentic Supervision) — a full, independent ByteBuddy weaver (`SupervisionWeaver`)
  and dispatch runtime (`JavAISupervisionRuntime`): method/constructor-scoped PRE/POST/EXCEPTION, sync
  listeners with real veto/rewrite rights running before fire-and-forget async listeners on a
  virtual-thread-per-task executor, and an improvement over its AoP predecessor (EXCEPTION now catches an
  exception propagated from a called method, not just a literal `throw`). See that module's README for two
  JVM-imposed method/constructor asymmetries discovered while building it.

`e2e-client-test` (a standalone downstream consumer, not one of the eight modules) proves the above against a
single monolithic Docker container bundling Postgres+pgvector, Neo4j, and a real embedding provider, not
fakes or mocks.

## License

Apache License, Version 2.0 — see [`LICENSE`](LICENSE) and [`NOTICE`](NOTICE).
