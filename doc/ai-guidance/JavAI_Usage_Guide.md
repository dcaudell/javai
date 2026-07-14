# JavAI Extensions — Usage Guide for AI Coding Agents

**Audience: an AI coding assistant helping a developer in a project that depends on JavAI Extensions
(`io.github.dcaudell:*`) as an ordinary Maven library.** This is not for someone contributing to the JavAI
Extensions repository itself — that repository has its own `SPEC.md`/`CLAUDE.md` for that audience.

Read this file before generating or editing any code in a project that has a `io.github.dcaudell:*`
dependency, or before adding one. See the sibling file, `JavAI_Codegen_Guidance.md`, for a separate,
narrower set of rules that only apply when the code you're touching carries one of five specific
meta-annotations (`@Requires`/`@Intent`/`@AgentWritable`/`@Nondeterministic`/`@Provenance` and friends) —
read that one too, but only when you actually encounter those annotations.

Everything below reflects real, tested behavior as of this writing, sourced directly from the library's own
code and module READMEs, not from the design whitepaper's aspirations. Where something is aspirational
rather than implemented, it's marked as such explicitly. If in doubt, the authoritative source is always
the actual module `README.md` (e.g. `javai-vector/README.md`) in the JavAI Extensions repository itself.

## What this library does

No existing ORM, object-graph mapper, or JVM language automatically re-embeds an object as a side effect of
mutating it. JavAI Extensions makes that a property of the object model itself:

| Capability | What it gives you | Backed by |
|---|---|---|
| **Auto-maintained embeddings** | Annotate a field `@Vectorize`; the object's `vector()` recomputes lazily whenever that field changes — never eagerly on write, only on next read | `javai-vector` + `javai-model` (Vector Core) |
| **Object-graph similarity queries** | `object.query(referenceVector, SomeType.class)` walks the live in-memory object graph, cycle-safe, returning a ranked `JavAIList<T>` | Vector Core |
| **Hierarchical summary vectors** | `@Summary` on a field (single reference or a `JavAIList`/`Set`/`Map` collection) folds a child's `summaryVector()` into its container's own, decay-weighted, propagating lazily up the containment graph on mutation | Vector Core |
| **Native knowledge-graph structure** | `KnowledgeGraph<N, E>` — nodes/edges plus hybrid pattern-match + similarity queries (`nearestSubgraph`) in one call, re-queryable on the result itself | `javai-collections` (Vector Collections) |
| **Vector-aware standard collections** | `JavAIList`/`JavAISet`/`JavAIMap` — drop-in `java.util` replacements that are themselves `JavAIVectorizable` (a collection has its own `vector()`/`summaryVector()`) | `javai-model` |
| **Persisted, searchable object graphs** | `JavAIPI.repository(YourRepository.class)` — CRUD plus `findNearestBy<Field>Vector`-style derived queries, against Postgres+pgvector or Neo4j, model-versioned automatically | `javai-persistence` (Persistence Bridge) |
| **Provider-agnostic RAG completions** | `Cortex` (six providers: OpenAI, Anthropic, Groq, vLLM, Ollama, Replicate) + `CompletionRequest`/`CompletionResult`, wrapping Spring AI rather than competing with it | `javai-completion` (Completion Fabric) |
| **Grounding a completion in real object-graph data** | `PromptContext`/`Contextable`/`ContextableObject` — a `query()` result, or any `JavAIList`/`Set`/`Map`, renders directly as prompt material, no manual serialization | `javai-model` (lives here, not `javai-completion` — see "Module layout" below) |
| **Agentic Supervision** | `@SyncSupervision`/`@AsyncSupervision` on a method or constructor — a registered `SupervisionListener` can veto/rewrite a call (blocking) and/or react to it (fire-and-forget), at PRE/POST/EXCEPTION | `javai-supervision` |
| **Codegen Guidance** | A *different* annotation family (`@Requires`/`@Intent`/`@AgentWritable`/`@Nondeterministic`/`@Provenance`) that constrains what an AI agent may read/generate/modify in annotated code | `javai-annotations`; see `JavAI_Codegen_Guidance.md` |

**The hard interop rule that shapes all of the above:** every class this library produces — woven or
plain — is a complete, correct, standard JVM class file, runnable on any stock JDK 21+, with the JavAI
runtime as an ordinary classpath dependency. There is no custom JVM, no required GPU, no modified bytecode
format you need to reproduce or worry about — it's all real, if sometimes runtime-synthesized, code.

### Module layout (which artifact has what)

Install the full set (see "Installing the library" below) rather than picking and choosing — the table
below is for understanding what each module actually contributes, not for deciding which ones to skip.

| Module | Extension area | What it gives you |
|---|---|---|
| `javai-annotations` | Codegen Guidance + shared annotation vocabulary | Every annotation used across all seven areas — every other module depends on it |
| `javai-vector` | Vector Core, part 1 | `EmbeddingVector`, embedding providers, dirty-tracking primitives |
| `javai-model` | Vector Core part 2, Vector Collections' interfaces, Completion Fabric's RAG primitives | `@JavAIVectorizable`'s contract, `JavAIList`/`Set`/`Map`, `PromptContext`/`Contextable` |
| `javai-substrate` | Acceleration Substrate | The weaver that actually implements `@JavAIVectorizable` at runtime |
| `javai-supervision` | Agentic Supervision | `@SyncSupervision`/`@AsyncSupervision` and their weaver |
| `javai-collections` | Vector Collections | `KnowledgeGraph`, `SubgraphResult`, `VectorIndex` |
| `javai-persistence` | Persistence Bridge | `JavAIPI.repository(...)` against Postgres or Neo4j |
| `javai-completion` | Completion Fabric | `Cortex`/`CompletionRequest` |

`javai-model` is a *physical*, not conceptual, module — it exists because `JavAIVectorizable.query()`
returns `JavAIList<T>` and `JavAIList` implements `JavAIVectorizable` right back, so those types (plus the
Vector Collections interfaces and Completion Fabric's RAG primitives, for the same compile-order reasons)
have to live together upstream of everything else. Don't be surprised to find `PromptContext` or
`JavAIList` there instead of in the module whose name you'd expect.

## The most important thing to know: some methods you'll see called don't exist in source

`@JavAIVectorizable`-annotated classes gain real, callable methods **at classloader time**, woven in by
`javai-substrate`'s ByteBuddy transformer — not present in the `.java` source, not visible to `javac`, but
100% real and correct once the class is loaded through a JVM that has the weaver installed (see
"Activating the weaver," below). Concretely:

- You will see code like `article.vector()` or `article.titleVector()` where `Article.java` has no
  `vector()` method, no `titleVector()` method, and no `implements JavAIVectorizable` anywhere in its
  source. **This is correct, working code.** Do not "fix" it by adding a hand-written implementation,
  removing the call, or reporting it as a bug — the method is real, it's just synthesized later than
  `javac` runs.
- The failure mode you might actually encounter is the opposite: if the weaver genuinely isn't installed
  (or was installed too late — see below), the exact same call fails at runtime with a real
  `NoSuchMethodError` (the class was loaded unwoven and stays that way for the JVM's life). If you see that,
  the fix is almost always "install the weaver earlier," not "rewrite the call" or "add the method by hand"
  — never write `implements JavAIVectorizable` yourself; that defeats the entire mechanism.
- An IDE (IntelliJ, via the separate `javai-intellij-idea` plugin) can be taught to resolve these for a
  human editing the file live. You have no equivalent help reading raw source — the table below is the
  substitute. `@SyncSupervision`/`@AsyncSupervision`, by contrast, add **no new methods** — they wrap the
  method that's already there via Byte Buddy `Advice`, so nothing about a supervised method's own signature
  changes.

### Every method a woven `@JavAIVectorizable` class gains

| Method | Interface | Callable from your own code? | What it does |
|---|---|---|---|
| `EmbeddingVector vector()` | `JavAIVectorizable` | Yes | This object's own embedding, from its `@Vectorize` fields. Recomputes lazily on next read after any of them changes. |
| `EmbeddingVector summaryVector()` | `JavAIVectorizable` | Yes | Decay-weighted combination of `vector()` and every `@Summary`-marked child's own `summaryVector()`. Cycle-safe. |
| `double similarityTo(JavAIVectorizable other)` | `JavAIVectorizable` | Yes | Cosine similarity between this object's `vector()` and `other`'s. |
| `double similarityTo(EmbeddingVector reference)` | `JavAIVectorizable` | Yes | Cosine similarity against an arbitrary vector (e.g. a query embedding). |
| `<T> JavAIList<T> query(EmbeddingVector reference, Class<T> type)` | `JavAIVectorizable` | Yes | Walks the reachable object graph for instances of `type`, ranked by similarity to `reference`. Unbounded depth, cycle-safe. Respects `@SearchVisibility(PRIVATE)`. |
| `<T> JavAIList<T> query(EmbeddingVector reference, Class<T> type, int maxDepth)` | `JavAIVectorizable` | Yes | Same, with an explicit traversal-depth limit. |
| `EmbeddingVector fieldVector(String fieldName)` | `JavAIVectorizable` | Yes | Dynamic (string-keyed) counterpart to the per-field accessors below. |
| `<field>Vector()` — e.g. `titleVector()` for a field named `title` | Synthesized, one per `@Vectorize` field | Yes | Named accessor for that one field's own contribution — real method, real name, not reflection-only. |
| `addDependent(Object)` / `dependents()` | `JavAIDirtyTracking` | **No** — internal bookkeeping | Registers/lists what to mark dirty when this object changes. `JavAIRuntime` calls this for you via the woven setter. |
| `isFieldDirty()` / `markFieldDirty()` / `clearFieldDirty()` | `JavAIDirtyTracking` | **No** — internal bookkeeping | Tracks whether this object's own `vector()` is stale. |
| `isSummaryDirty()` / `markSummaryDirty()` / `clearSummaryDirty()` | `JavAIDirtyTracking` | **No** — internal bookkeeping | Tracks whether this object's `summaryVector()` is stale (a descendant changed). |

The class also gains `implements JavAIVectorizable, JavAIDirtyTracking` on its type itself (not just the
methods), plus one synthesized private state field holding the dirty-tracking bookkeeping. Every
conventional `setXxx(...)` setter for a `@Vectorize`/`@Summary` field gets its *body* instrumented (the
original assignment is untouched; dirty-marking and dependency-registration calls are added around it) —
and if a `@Summary` field is initialized inline in a field initializer rather than via a setter (a
`final JavAIArrayList<Comment> comments = new JavAIArrayList<>();`-style field, elements added to later
through the collection itself), the weaver wires that dependency at **constructor exit** instead.

## Annotation reference

### Vector Core / Vector Collections — shape what gets embedded, queried, and how

| Annotation | Target | Meaning |
|---|---|---|
| `@JavAIVectorizable` | class | Triggers the entire woven method table above. Never also write `implements JavAIVectorizable` by hand. |
| `@Vectorize` | field | This field contributes to the declaring object's own `vector()`. Also gets a synthesized `<field>Vector()` accessor. |
| `@VectorizeIgnore` | field | Explicitly excludes a field from the local embedding. Wins over `@Vectorize` if a field somehow carries both. |
| `@Summary` | field or class | This field (a single reference or a `JavAIList`/`Set`/`Map`) folds into the container's `summaryVector()`, decay-weighted, cycle-safe. |
| `@SearchVisibility(PUBLIC\|PROTECTED\|PRIVATE)` | field or class | Search-semantic visibility, independent of Java access modifiers. `PRIVATE` on a *field* blocks `query()` from traversing through it at all. `PRIVATE` on a *class* blocks instances from being returned as a match (but traversal still passes through them, so their own descendants stay reachable). `PUBLIC`/`PROTECTED` currently behave identically. |
| `@EmbeddingModel("model-id")` | class, field, method, or parameter | Overrides which embedding model computes this element's vector, instead of the default. |
| `@JavAIGraphNode` / `@JavAIEdge` | class | **Documentation/intent-signaling only — not woven, no runtime behavior.** To actually make a class a `KnowledgeGraph` participant, hand-declare `implements JavAIGraphNode` / `implements JavAIEdge` directly (both are empty marker interfaces in `javai-collections` — there are no method bodies to weave, so annotating alone does nothing). Using the annotation *and* the `implements` together is the documented, correct pattern; the annotation alone is not enough. |

### Completion Fabric — grounding a completion in real data

| Annotation | Target | Meaning |
|---|---|---|
| `@dev.xtrafe.javai.annotations.PromptContext` | field | Allowlists this field for `PromptContext.defaultMarshall(Object)`'s GSON-based rendering when the object is wrapped in `ContextableObject`. An *allowlist*, not a blocklist — an unannotated field (including a woven class's internal state) is excluded by default. **Shares its simple name with the unrelated `dev.xtrafe.javai.model.PromptContext` record** (the RAG-context value type) — different packages, so it compiles, but if you're writing code *inside* that record's own file you must reference the annotation fully-qualified, since the record's own simple name already occupies that name in scope. |

### Agentic Supervision — sync/async interception of a method or constructor

| Annotation | Target | Meaning |
|---|---|---|
| `@SyncSupervision(SupervisionPointcut...)` | method or constructor | Weaves blocking, read-write interception at the given pointcut(s) (`PRE`/`POST`/`EXCEPTION`, all three by default). Every `SupervisionListener` registered via `registerSyncListener` and scoped to the call runs on the calling thread, in registration order, each able to rewrite arguments/return value/thrown exception. `EXCEPTION` is rejected at weave time on a **constructor** specifically (a real JVM restriction — see `javai-supervision`'s own README). |
| `@AsyncSupervision(SupervisionPointcut...)` | method or constructor | Weaves fire-and-forget, observation-only interception at the given pointcut(s). Every `SupervisionListener` registered via `registerAsyncListener` and scoped to the call is dispatched on a virtual-thread-per-task executor; any mutation it makes is discarded. |
| Stackable on the same element | — | A method/constructor can carry both `@SyncSupervision` and `@AsyncSupervision` at the same or different pointcuts; **the sync tier for a given dispatch always fully resolves — including any mutation — before the async tier for that same dispatch is ever invoked.** A single `SupervisionListener` instance may be registered via both `registerSyncListener` and `registerAsyncListener` at once; it's the same interface either way (see below), and it will then receive both dispatches independently. |
| `SupervisionPointcut` | enum value | `PRE` (before the body runs) / `POST` (after a normal return) / `EXCEPTION` (after a throw, including one propagated from a called method, not just a literal `throw` in the annotated method's own body). |
| `SupervisionListener` (interface, not an annotation) | — | One shape — `onPre`/`onPost`/`onException`(`SupervisionEvent`), all default no-ops, plus `supportedClass()` for coarse scoping — used for **both** the sync and async tiers. Whether a given registration is blocking/read-write or fire-and-forget/observation-only is a property of which `JavAISupervisionRuntime` method it's registered through, not of the listener's type. |

### Codegen Guidance — a distinct feature, see the sibling file

`@Requires`/`@Ensures`/`@Invariant`, `@Intent`, `@AgentWritable`/`@Frozen`/`@HumanOnly`,
`@Nondeterministic`/`@Costly`, `@Provenance` — these don't affect runtime behavior at all. They constrain
what *you*, the AI agent, may read, generate, or modify in code that carries them. Full rules in
`JavAI_Codegen_Guidance.md`.

## Installing the library

**Not yet published to Maven Central.** Build from source:

```sh
git clone <this repository>
cd javai
mvn install   # builds and installs all 8 modules to the local ~/.m2, in dependency order
```

Then add the **full module set** to your own project's `pom.xml`, at the version declared in this
repository's root `pom.xml` (currently `0.1.1` — check there directly rather than assuming it
hasn't changed). Install everything rather than picking a subset — the modules are small and designed to
interoperate, and not reasoning about which subset a given task needs is one less decision to make:

```xml
<dependency>
  <groupId>io.github.dcaudell</groupId>
  <artifactId>javai-vector</artifactId>
  <version>0.1.1</version>
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

`javai-annotations` isn't listed above because it's transitively required by every other module — it comes
along automatically.

For a Gradle project, the equivalent `build.gradle.kts` dependency block is:

```kotlin
dependencies {
    implementation("io.github.dcaudell:javai-vector:0.1.1")
    implementation("io.github.dcaudell:javai-model:0.1.1")
    implementation("io.github.dcaudell:javai-substrate:0.1.1")
    implementation("io.github.dcaudell:javai-supervision:0.1.1")
    implementation("io.github.dcaudell:javai-collections:0.1.1")
    implementation("io.github.dcaudell:javai-persistence:0.1.1")
    implementation("io.github.dcaudell:javai-completion:0.1.1")
}
```

Every module publishes both a POM and Gradle Module Metadata (`.module`) to Maven Central.

## Activating the weaver (required for `@JavAIVectorizable`/`@SyncSupervision`/`@AsyncSupervision` to do anything)

`javai-substrate` (Vector Core weaving) and `javai-supervision` (Agentic Supervision weaving) are **load-time
bytecode weavers** — nothing about them runs automatically just because the dependency is on the classpath.
A consuming project must explicitly install both, and must do so **before any annotated class is loaded by
the JVM for the first time**:

```java
Instrumentation instrumentation = net.bytebuddy.agent.ByteBuddyAgent.install();
dev.xtrafe.javai.substrate.JavAIWeaver.install(instrumentation);         // Vector Core
dev.xtrafe.javai.supervision.SupervisionWeaver.install(instrumentation); // Agentic Supervision
```

Install both unconditionally, even in a project that only ends up using one of the two feature sets right
away — it's cheap, and it means adding the other capability later never requires touching this bootstrap
code again.

- `ByteBuddyAgent.install()` self-attaches a Java agent to the *currently running* JVM. On JDK 9+ this
  requires the JVM flag `-Djdk.attach.allowAttachSelf=true` (self-attach is disabled by default) — add it
  to whatever launches your `java` process or test runner.
- **Timing is the real danger here, not the mechanism.** Any reflection-heavy framework that touches an
  annotated class before the code above runs will load that class unwoven — and it stays unwoven for the
  rest of that JVM's life; installing the weaver later does nothing for an already-loaded class. This is a
  real, previously-hit bug in this project's own test suite (JUnit 5's test discovery reflectively inspects
  test-class fields to build its test plan, which is enough to trigger unwoven loading if the weaver isn't
  installed before discovery starts).
  - **Plain application**: call the snippet above as the very first lines of `main()`, before touching any
    domain class.
  - **JUnit 5 test suite**: register a `LauncherSessionListener` via
    `META-INF/services/org.junit.platform.launcher.LauncherSessionListener` and install the weaver(s) from
    its `launcherSessionOpened(...)` callback — **not** `@BeforeAll`, which already runs too late (after
    discovery). See this repository's own `e2e-client-test/src/test/java/.../JavAIWeavingLauncherSessionListener.java`
    for a real, working example.
- Both weavers share one `Instrumentation` instance — `ByteBuddyAgent.install()` is idempotent (a second
  call just returns the already-attached instance), so it's always safe to call it once and pass the result
  to both.
- As of this writing there is no build-time Maven/Gradle plugin and no traditional manifest-based
  `-javaagent` shipped — self-attach via `ByteBuddyAgent.install()` is the real, current mechanism. If you
  see a build-time plugin described as available somewhere, verify against `javai-substrate`'s own current
  README before relying on it — the design docs describe it as a future option, not a Phase 0 deliverable.

## Runtime backends

The full module set needs three external backends: an embedding-model provider (Vector Core), Postgres+
pgvector and/or Neo4j (Persistence Bridge), and a completion provider (Completion Fabric). Agentic
Supervision and the woven Vector Core mechanism itself need nothing beyond the weaver above.

The straightforward way to get all three at once: this repository ships a reference Dockerfile,
`e2e-client-test/docker/Dockerfile`, bundling Postgres+pgvector, Neo4j, and Ollama (with both a reference
embedding model and a chat-completion model already baked in) into one container — not published to a
registry, but buildable and runnable directly:

```sh
cd e2e-client-test
docker build -t javai-e2e-monolithic:latest -f docker/Dockerfile docker
docker run -d --name javai-e2e-monolithic \
  -p 15432:5432 -p 17474:7474 -p 17687:7687 -p 21434:11434 \
  javai-e2e-monolithic:latest
# next time: docker start javai-e2e-monolithic
```

Postgres on `localhost:15432`, Neo4j on `17474` (HTTP)/`17687` (Bolt), Ollama on `21434` — the same fixed
ports and container this repository's own `e2e-client-test` module reuses across runs, not a separate
quick-start-only artifact. See `e2e-client-test/README.md`'s "Persistent container" section for the full
lifecycle notes.

Beyond that quick start — e.g. your own choice of embedding/completion vendor, or infrastructure you already
run in production — each backend is configured independently:

| Backend | Configured via |
|---|---|
| Embedding provider | `javai-vector`'s `LocalEmbeddingDefaults` picks Ollama or Hugging Face TEI per host platform, or supply your own `JavAIEmbeddingProvider` |
| Postgres/Neo4j | `javai-persistence/README.md`; connection settings default to `javai.persistence.*` system properties |
| Completion provider | `javai-completion/README.md` — hosted API key (OpenAI/Anthropic/Groq/Replicate) or a local Ollama/vLLM instance; `Cortex.contextWindowTokens()`/`CompletionRequest.render(int)` size a `PromptContext` to fit automatically |

**Embedding provider, in detail** — registered once, globally, before anything calls `vector()`:

```java
JavAIRuntime.configureEmbeddingProvider(
    new EmbeddingProviderOllama(URI.create("http://localhost:11434"), "qwen3-embedding:0.6b"));
// or: new EmbeddingProviderTextEmbeddingsInference(URI.create("http://localhost:8080"), "your-model-label")
// or: new EmbeddingProviderOpenAI(apiKey, "text-embedding-3-small")
// or: new EmbeddingProviderVLlm(URI.create("http://your-vllm-host:8000"), "your-model")
// or: EmbeddingProviderReplicate.builder().apiToken(apiToken).model("owner/model-name").build()
// or: implement JavAIEmbeddingProvider yourself against any other vendor's embedding API
```

Without an explicit call, `JavAIRuntime` falls back to the `javai.embedding.endpoint`/`javai.embedding.model`
system properties (constructing an `EmbeddingProviderTextEmbeddingsInference`); with neither the call nor
those properties set, the first `vector()` call throws `IllegalStateException`.

There's no `EmbeddingProviderAnthropic`/`EmbeddingProviderGroq` alongside `Cortex`'s Anthropic/Groq
implementations — neither vendor has a native embeddings API (Anthropic recommends Voyage AI instead;
Groq's API has no embeddings endpoint). `EmbeddingProviderReplicate` is also a different case from the
other four: Replicate has no vendor-wide embeddings contract, so it defaults to one popular model and input
field name that you should verify against whatever model you actually run — see `javai-vector/README.md`'s
"Hosted-vendor providers" section for the full detail.

**Completion provider, in detail** — no global registration; construct whichever `Cortex` you want, wherever
you use it:

```java
Cortex cortex = CortexOpenAI.builder().apiKey(System.getenv("OPENAI_API_KEY")).model("gpt-4.1").build();
// or CortexAnthropic / CortexGroq / CortexVLlm / CortexOllama / CortexReplicate -- same builder shape
```

Constructing several `Cortex`es side by side, local and remote, is normal — each is a plain object, not
something you register.

## Worked example

A domain class combining most of the above — real, tested shape (see this repository's own
`e2e-client-test/src/main/java/.../domain/Article.java` for the full, working version this is adapted
from):

```java
import dev.xtrafe.javai.annotations.*;
import dev.xtrafe.javai.collections.JavAIGraphNode;
import dev.xtrafe.javai.model.JavAIArrayList;

@JavAIVectorizable
public class Article implements JavAIGraphNode {   // implements is required -- @JavAIGraphNode alone is not

    @Vectorize
    @PromptContext
    private String title;

    @Vectorize
    @PromptContext
    private String body;

    @Summary
    private final JavAIArrayList<Comment> comments = new JavAIArrayList<>();

    public void setTitle(String title) { this.title = title; }   // re-vectorizes lazily on next vector() read
    public void setBody(String body) { this.body = body; }
    public JavAIArrayList<Comment> getComments() { return comments; }
}
```

Using it — every call below is a woven method, not something declared in `Article.java`
(imports for `EmbeddingVector`/`JavAIList`/`Cortex`/`CompletionRequest`/`CompletionResult`/`PromptContext`/
`ContextableObject` omitted for brevity):

```java
Article article = new Article();
article.setTitle("Rate limits and you");
article.setBody("...");
article.getComments().add(new Comment("someone", "Great writeup!"));

EmbeddingVector titleVec = article.titleVector();     // per-field accessor
EmbeddingVector wholeArticleVec = article.vector();    // title + body combined
EmbeddingVector summary = article.summaryVector();     // includes the comments' own vectors, decayed

JavAIList<Comment> concerned = article.query(article.vector(), Comment.class);

// Grounding a completion in that real query result:
CompletionResult brief = cortex.complete(CompletionRequest.builder()
        .prompt("Summarize reader concerns in two sentences.")
        .context(PromptContext.builder()
                .entries(concerned.stream().map(ContextableObject::new).toList())
                .build())
        .maxTokens(200)
        .build());
```

## Quick reference: where to look next

| Question | Read |
|---|---|
| Exact primitive definitions, code examples, per extension area | This repository's `doc/spec/*.md` |
| Full design rationale, prior art | `doc/JAI_Whitepaper.docx` |
| What's *actually* implemented right now, module by module | Each module's own `README.md` — treat it as more current than the whitepaper or `doc/spec/` |
| Codegen Guidance meta-annotation behavioral rules | `JavAI_Codegen_Guidance.md` (this directory) |
