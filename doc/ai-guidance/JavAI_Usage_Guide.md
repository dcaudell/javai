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
| **Tagging** | `@Taggable` marks a class as taggable; a `JavAITagRepository` instance then handles structural queries (`tagsOf`/`taggingsOf`/`taggedWith`/`addTag`/`removeTag`/`hasTag`/`rankedByTags`), LLM-based classification (`classify`/`classifyAll` via `Cortex`), cross-type tag-similarity search (`tagSimilarityIndex()`), and Taggregate — `@Taggregate` fields make a container's tags a derived aggregate of its members', and `@Taggregate(concatenate = true)` renders any taggable's tags as an embedded, searchable string (`tagTextIndex()`) — no methods are woven onto the tagged class itself | `javai-tagging` (Tagging) |
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
| `javai-annotations` | Codegen Guidance + shared annotation vocabulary | Every annotation used across all eight areas — every other module depends on it |
| `javai-vector` | Vector Core, part 1 | `EmbeddingVector`, embedding providers, dirty-tracking primitives |
| `javai-model` | Vector Core part 2, Vector Collections' interfaces, Completion Fabric's RAG primitives | `@JavAIVectorizable`'s contract, `JavAIList`/`Set`/`Map`, `PromptContext`/`Contextable` |
| `javai-substrate` | Acceleration Substrate | The weaver that actually implements `@JavAIVectorizable` at runtime |
| `javai-supervision` | Agentic Supervision | `@SyncSupervision`/`@AsyncSupervision` and their weaver |
| `javai-collections` | Vector Collections | `KnowledgeGraph`, `SubgraphResult`, `VectorIndex` |
| `javai-persistence` | Persistence Bridge | `JavAIPI.repository(Class, JavAIPersistenceConfig)` against Postgres, Neo4j, or MongoDB |
| `javai-completion` | Completion Fabric | `Cortex`/`CompletionRequest` |
| `javai-tagging` | Tagging | `@Taggable`/`@TagIgnore`/`@Taggregate`, `Tag`/`TagSet`, `JavAITagRepository` (tag-based queries, similarity search, LLM classification, Taggregate + tag text) |

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
- `@Taggable`, by contrast again, adds **nothing at all** to the class it annotates — no woven methods, no
  new interface implementation beyond the marker `Taggable` you hand-implement yourself. There is no
  `article.addTag(...)` to go looking for. Every tagging operation goes through a separate `JavAITagRepository`
  instance instead — see "Tagging" below.

### Every method a woven `@JavAIVectorizable` class gains

| Method | Interface | Callable from your own code? | What it does |
|---|---|---|---|
| `EmbeddingVector vector()` | `JavAIVectorizable` | Yes | This object's own embedding, from its `@Vectorize` fields. Recomputes lazily on next read after any of them changes. |
| `EmbeddingVector summaryVector()` | `JavAIVectorizable` | Yes | Decay-weighted combination of `vector()` and every `@Summary`-marked child's own `summaryVector()`. Cycle-safe. |
| `EmbeddingVector concatenatedTextVector()` | `JavAIVectorizable` | Yes | One embedding of real text assembled across the object graph, as against `summaryVector()`'s arithmetic over vectors. **Opt-in**: absent, and free, unless you add `@Summary(concatenate = true)`. |
| `String concatenatedText()` | `JavAIVectorizable` | Yes | The assembled text itself, before embedding — useful for seeing exactly what got vectorized. **`null`** (not `""`) when there is no text: not opted in, or nothing to contribute. |
| `double similarityTo(JavAIVectorizable other)` | `JavAIVectorizable` | Yes | Cosine similarity between this object's `vector()` and `other`'s. |
| `double similarityTo(EmbeddingVector reference)` | `JavAIVectorizable` | Yes | Cosine similarity against an arbitrary vector (e.g. a query embedding). |
| `<T> JavAIList<T> query(EmbeddingVector reference, Class<T> type)` | `JavAIVectorizable` | Yes | Walks the reachable object graph for instances of `type`, ranked by similarity to `reference`. Unbounded depth, cycle-safe. Respects `@SearchVisibility(PRIVATE)`. |
| `<T> JavAIList<T> query(EmbeddingVector reference, Class<T> type, int maxDepth)` | `JavAIVectorizable` | Yes | Same, with an explicit traversal-depth limit. |
| `EmbeddingVector fieldVector(String fieldName)` | `JavAIVectorizable` | Yes | Dynamic (string-keyed) counterpart to the per-field accessors below. |
| `<field>Vector()` — e.g. `titleVector()` for a field named `title` | Synthesized, one per `@Vectorize` field | Yes | Named accessor for that one field's own contribution — real method, real name, not reflection-only. |
| `EmbeddingVector vector(String modelId)` | `JavAIVectorizable` | Yes | The same aggregate **restricted to one embedding model** — `@Vectorize` fields and `@ExternalVector`s alike. Absent when this object carries nothing from that model. Only matters once an object has more than one model on it; see "@ExternalVector" below. |
| `EmbeddingVector summaryVector(String modelId)` | `JavAIVectorizable` | Yes | `summaryVector()` restricted to one model — same decay-weighted formula, admitting only that model's vectors. Uncached (it recombines vectors that are themselves cached). |
| `EmbeddingVector externalVector(String vectorName)` | `JavAIVectorizable` | Yes | An `@ExternalVector`'s current value, or absent. **Never computes, never blocks, never calls a provider.** Throws if the class declares no such name. |
| `<name>Vector()` — e.g. `pixelsVector()` for `@ExternalVector(name = "pixels")` | Synthesized, one per `@ExternalVector` | Yes | Named accessor for that external vector, exactly like the `@Vectorize` one above. |
| `addDependent(Object)` / `dependents()` | `JavAIDirtyTracking` | **No** — internal bookkeeping | Registers/lists what to mark dirty when this object changes. `JavAIRuntime` calls this for you via the woven setter. |
| `isFieldDirty()` / `markFieldDirty()` / `clearFieldDirty()` | `JavAIDirtyTracking` | **No** — internal bookkeeping | Tracks whether this object's own `vector()` is stale. |
| `isSummaryDirty()` / `markSummaryDirty()` / `clearSummaryDirty()` | `JavAIDirtyTracking` | **No** — internal bookkeeping | Tracks whether this object's `summaryVector()` is stale (a descendant changed). |

The class also gains `implements JavAIVectorizable, JavAIDirtyTracking` on its type itself (not just the
methods), plus one synthesized private state field holding the dirty-tracking bookkeeping. Every
conventional `setXxx(...)` setter for a `@Vectorize`/`@Summary` field gets its *body* instrumented (the
original assignment is untouched; dirty-marking and dependency-registration calls are added around it) —
and if a `@Summary` field is initialized inline in a field initializer rather than via a setter (a
`final JavAIArrayList<Comment> comments = new JavAIArrayList<>();`-style field, elements added to later
through the collection itself), the weaver wires that dependency at **constructor exit** instead. That
concrete-typed declaration is one of two supported shapes for a collection field on a *persisted* entity —
see "Collection fields on a persisted `@Entity`" below before choosing one.

## Annotation reference

### Vector Core / Vector Collections — shape what gets embedded, queried, and how

| Annotation | Target | Meaning |
|---|---|---|
| `@JavAIVectorizable` | class | Triggers the entire woven method table above. Never also write `implements JavAIVectorizable` by hand. |
| `@Vectorize` | field | This field contributes to the declaring object's own `vector()`. Also gets a synthesized `<field>Vector()` accessor. |
| `@VectorizeIgnore` | field | Explicitly excludes a field from the local embedding. Wins over `@Vectorize` if a field somehow carries both. |
| `@Summary` | field or class | This field (a single reference or a `JavAIList`/`Set`/`Map`) folds into the container's `summaryVector()`, decay-weighted, cycle-safe. **When persisted, this makes the container a write-coordination point** — see the note below. |
| `@Summary(concatenate = true)` | field or class | Additionally opts into **concatenated text vectoring**. On a *class*: embed my own `@Vectorize` fields as text. On a *field*: absorb that child's (or collection's members') text into mine. Defaults to `false`; adds to `@Summary`'s meaning rather than replacing it. |
| `@SearchVisibility(PUBLIC\|PROTECTED\|PRIVATE)` | field or class | Search-semantic visibility, independent of Java access modifiers. `PRIVATE` on a *field* blocks `query()` from traversing through it at all. `PRIVATE` on a *class* blocks instances from being returned as a match (but traversal still passes through them, so their own descendants stay reachable). `PUBLIC`/`PROTECTED` currently behave identically. |
| `@ExternalVector(name, keyField, model)` | class, **repeatable** | Declares a vector JavAI **stores but never computes** — supplied from outside the process, in its own model. Gains a `<name>Vector()` accessor and a `findNearestBy<Name>Vector` query. See the section below. |
| `@EmbeddingModel("model-id")` | class, field, method, or parameter | Overrides which embedding model computes this element's vector, instead of the default. ⚠️ Defined but **not yet read by anything** — declaring it changes no behaviour today. |
| `@JavAIGraphNode` / `@JavAIEdge` | class | **Documentation/intent-signaling only — not woven, no runtime behavior.** To actually make a class a `KnowledgeGraph` participant, hand-declare `implements JavAIGraphNode` / `implements JavAIEdge` directly (both are empty marker interfaces in `javai-collections` — there are no method bodies to weave, so annotating alone does nothing). Using the annotation *and* the `implements` together is the documented, correct pattern; the annotation alone is not enough. |

### ⚠️ What `@Summary` implies once the container is persisted

A container's summary is stored as **one row per container**. So every write anywhere beneath a `@Summary`
container changes that one row — whichever child was touched, and however unrelated two writers are to each
other. Put `@Summary` on a hot container (an album everyone uploads into, a workspace everyone edits) and you
have declared a point every write underneath it passes through.

**JavAI handles the coordination for you** (OMI-255): on Postgres the summary is not written inside your
transaction at all. Your write records that the container owes a recomputation — an insert that cannot
collide with anyone — and the recomputation runs immediately after your transaction commits, from committed
state, under a lock. Two concurrent writers both succeed, and the container reflects both.

Three consequences worth knowing before you annotate:

- **A summary-vector search inside your own still-open transaction sees the previous summary.** After the
  commit it is current. Between `save` and `commit`, it is not.
- **Ancestors are recomputed even if you never loaded them**, because containment is resolved from the
  database. Adding to a `Shelf` updates the `Library` holding it without your code mentioning a library.
- **You can defer it per write** with `save(entity, SummaryPolicy.QUEUE_ONLY)` when throughput matters more
  than currency — then drain with `JavAIPI.drainPendingSummaries(config)`. The queue is durable, so this
  delays the work; it never loses it. But nothing drains it on your behalf.

Full contract, including what happens when a recomputation fails, is in `persistence-support-matrix.md`'s
"Concurrency" section. On Neo4j/MongoDB summaries are still written inline.

### Collection fields on a persisted `@Entity` — declare them by the interface

When a class is both `@JavAIVectorizable` and a JPA `@Entity` persisted through `JavAIRepository` on
**Postgres**, how you *declare* a collection field — not which annotation you put on it — decides whether it
can be mapped at all. **Since 0.1.10 there is one shape:**

```java
@OneToMany(cascade = CascadeType.ALL)
@Summary
private JavAIList<Comment> comments = new JavAIArrayList<>();   // interface-typed, and NOT final
```

This is a real JPA association in every respect — its own join table with foreign keys, `mappedBy`,
cascade/`orphanRemoval`, lazy loading, `@ManyToMany` shared ownership. What Hibernate substitutes into the
field is a `PersistentJavAIList`/`PersistentJavAISet`/`PersistentJavAIMap`, i.e. still a real JavAI
collection, so vectors and dirty-tracking survive the load. You write **nothing** JavAI-specific to get
this — no `@CollectionType`; the backend attaches it at mapping time. Two requirements, both mechanical:
declare the field by the JavAI *interface* (`JavAIList`/`JavAISet`/`JavAIMap`), and don't make it `final`,
since Hibernate assigns the field its own instance. A `Map` also wants `@MapKeyColumn`.

**⚠️ Breaking change in 0.1.10 (OMI-277): the concrete-typed field is now refused.**

```java
private final JavAILinkedHashMap<String, Comment> relatedComments = new JavAILinkedHashMap<>();
```

That used to be a second supported shape, stored in a side table the backend owned. It is rejected at
repository-registration time now, with an `IllegalArgumentException` naming the interface to use — and the
table is gone. It was withdrawn rather than repaired because it was **silently root-only in both
directions**: reached through an association the collection came back empty, and saved through one its
members were never written. It could not be made lazy where it stood either, since the field holds a `final`
instance of a `final` class and Hibernate manages a collection by substituting its own.

**Migrating:** change the declared type to the interface, drop `final`, and add the JPA annotation you would
have written for a plain collection. The initializer stays exactly as it is. Any *caller* that declared its
receiver as the concrete type (`JavAIArrayList<Comment> cs = article.getComments();`) needs the same one-word
change.

**Backend caveat:** native associations are **Postgres-only**. Neo4j and MongoDB classify collection fields by
declared type, accept either declaration, and store both their own way — so the interface-typed field buys
you nothing there, but it is what makes one entity portable across all three. Check
[`persistence-support-matrix.md`](persistence-support-matrix.md) — it has the per-backend tables for JPA
annotations, derived-finder capabilities, and collection types — before relying on any of this.

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

### Tagging — marks participation, weaves nothing

| Annotation | Target | Meaning |
|---|---|---|
| `@Taggable` | class | Unwoven marker — same shape as `@JavAIGraphNode`, not the fully-woven shape of `@JavAIVectorizable`. Declares that instances of this class can carry Tags, but synthesizes no methods and no interface implementation; independent of `@JavAIVectorizable`/`@JavAIGraphNode` (a class can carry any subset of all three). Always pair it with hand-implementing the *interface* `Taggable` (empty, marker-only) — the annotation alone doesn't give you that interface, and generic tagging APIs (`taggedWith`'s `candidateTypes` parameter) are bounded by it. **Shares its simple name with that interface** (`dev.xtrafe.javai.annotations.Taggable` the annotation vs. `dev.xtrafe.javai.tagging.Taggable` the interface) — different packages, so `@Taggable class Foo implements Taggable` compiles with one `import` covering both simple names, but write the interface fully-qualified (`implements dev.xtrafe.javai.tagging.Taggable`) if your file already imports something else named `Taggable`, or if you're generating code without import statements at all. |
| `@TagIgnore` | field | Excludes an otherwise-`@dev.xtrafe.javai.annotations.PromptContext` field from the text a classification prompt sees, without affecting what that field renders as for ordinary RAG completions. A field with no `@PromptContext` to begin with needs no `@TagIgnore` — it was never classifier-visible either way. |
| `@Taggregate` | field, class | Mirrors `@Summary`'s grammar exactly, in the tagging lineage. On a **field** holding a `Taggable` reference or a JavAI collection of them: absorb that target's taggings into the declaring object's aggregate — the container gains derived `Tagging` rows (`source = "aggregate"`, mean-contribution affinities), maintained by `JavAITagRepository`. On a **class**, `@Taggregate(concatenate = true)`: this type's tags render as one deterministic string and embed as a **tag-text vector**, searchable via `tagTextIndex()`; `concatenate` is meaningful only at this placement. Requires exactly what `Taggable` requires — the marker interface and an `@Id UUID` — on container and members alike; never `@JavAIVectorizable`, on either. Nothing is woven: the aggregate and the tag text live repository-side. See "Taggregate" below. |

See "Tagging" below for the full instance-based API (`JavAITagRepository`) these annotations feed into.

### Codegen Guidance — a distinct feature, see the sibling file

`@Requires`/`@Ensures`/`@Invariant`, `@Intent`, `@AgentWritable`/`@Frozen`/`@HumanOnly`,
`@Nondeterministic`/`@Costly`, `@Provenance` — these don't affect runtime behavior at all. They constrain
what *you*, the AI agent, may read, generate, or modify in code that carries them. Full rules in
`JavAI_Codegen_Guidance.md`.

## Vectors JavAI cannot compute: `@ExternalVector`

Everything above assumes JavAI can produce a vector on demand — a read of a stale value calls the configured
provider and waits. Some vectors do not work that way. An image embedding is produced by a different model,
in a different process, from bytes JavAI must never read, and it arrives seconds or minutes later. Declaring
that field `@Vectorize` would be wrong in every particular: it would embed the *filename*, block a read on a
provider that cannot answer, and land a 1152-dimension vector in the middle of 1024-dimension text ones.

```java
@Entity
@JavAIVectorizable
@ExternalVector(name = "pixels", keyField = "contentHash", model = "siglip2-so400m-p14-384/pp1")
public class Image extends Asset {

    @Vectorize private String caption;   // JavAI computes this one, as usual
    private String contentHash;          // identifies the bytes; JavAI never reads them
}
```

**Declare it on the type, not the field.** The field identifying the content is usually inherited from a
shared base, while only some subclasses have a vector of that kind — and an annotation on a field applies to
everything inheriting it and cannot be overridden on one subclass. Type-level also lets one class declare
several (`@ExternalVector` is repeatable): a video with both a keyframe vector and an audio vector, naming
the same key field with two different models.

### Reading one

```java
EmbeddingVector pixels = image.pixelsVector();   // or image.externalVector("pixels")
if (pixels.isAbsent()) {
    // Either nothing has been supplied yet, or the bytes changed since it was.
}
```

| | |
|---|---|
| Before anything is supplied | `EmbeddingVector.absent()` |
| After `contentHash` changes | `absent()` again — the stored vector describes *different content* |
| Provider calls | **none, ever** |
| Blocking | **none, ever** — under every `EmbeddingConsistencyMode`, and inside a save |

⚠️ **Do not reach for `EVENTUAL_CONSISTENCY` to get this.** That mode still blocks a vector's *first* read
(there is no prior value to serve) and still yields to the accuracy a save forces — so both would end up
waiting on a provider that cannot produce this vector, and asking the *text* provider for it. The
consistency-mode setting simply does not apply to external vectors; you need no configuration at all.

### Supplying one

From whatever consumes your pipeline's output — it needs only an id, a vector, and the key the model
actually embedded:

```java
images.supplyVector(event.assetId(), "pixels",
        new EmbeddingVector(event.values(), "siglip2-so400m-p14-384/pp1", event.dims(), event.computedAt()),
        event.contentHash());
```

**`computedFor` is what makes this safe under at-least-once delivery**, and it is not optional ceremony. If
the asset has moved on to different content since the model ran, the vector is discarded and `supplyVector`
returns `false` — a normal outcome of a slow producer racing an edit, not a failure to handle. Redelivering
the same event simply stores the same vector again.

If you already hold the object rather than just its id, `JavAIRuntime.supplyVector(image, "pixels", vector,
hash)` does the same thing in memory; save as usual afterwards.

**Fold every dimension of the model's identity into `model`** — name, weights version, preprocessing version
(`siglip2-so400m-p14-384/pp1`). Storage is partitioned by that string, so changing it makes the new vectors a
separate, additively-migratable set rather than an in-place overwrite. This is the difference between a model
upgrade you can run and one you cannot.

### Finding what is still owed

```java
List<Image> backlog = images.findPendingVector("pixels", 500);
```

Covers both causes at once, because they mean the same thing to a producer: never supplied, and supplied for
content since replaced. Intended for backfills and for re-driving after a dead-letter drain. ⚠️ It is a scan
of the type — right for those two jobs, wrong for polling per upload. For "is *this* upload processed yet",
keep your own status row.

### Searching one

Nothing new to learn — the ordinary convention, with the external vector's name:

```java
public interface ImageRepository extends JavAIRepository<Image> {
    List<Image> findNearestByPixelsVector(EmbeddingVector reference, int limit);
}

images.nearestBy("pixels").to(siglipQuery).limit(20).ranked();   // or the builder
```

⚠️ **The reference vector must come from the same model.** A query embedding produced by your text provider
cannot search an image index — not less well, but not at all. Embedding a text query into image space needs
that model's own text tower, which is your pipeline's job to expose, not JavAI's.

### Two models on one object

Once an object carries both, "this object's vector" is two questions, and each aggregate names which:

```java
image.vector();                                    // the text model, exactly as before
image.vector("siglip2-so400m-p14-384/pp1");        // the image one
album.summaryVector("siglip2-so400m-p14-384/pp1"); // what this album *looks* like
album.summaryVector();                             // what it reads like
```

They are separate on purpose. Two models' vectors cannot be combined — their cosine similarity is not a
weaker answer but no answer — and JavAI refuses rather than producing a meaningless number. Combining two
models' *rankings* (reciprocal rank fusion and friends) is a real technique, but the weighting is specific to
your domain, so it is your code, not the library's.

### The one rule it is exempt from

Elsewhere in JavAI, a `@Vectorize` field mutated by anything other than its woven setter goes silently stale
forever. An `@ExternalVector` has no such exposure: its validity is re-derived on every read by comparing the
key field's current value, so a `contentHash` written by reflection, by a framework, or by any other route is
caught exactly like one written through a setter. That is affordable here precisely because the key is a
short identifier standing in for content JavAI never touches.

## Tagging: `JavAITagRepository`, an instance, not a woven mechanism

Tagging is orthogonal to everything above — a class can be `@Taggable`, `@JavAIVectorizable`, both, or
neither, and none of Vector Core's woven-method machinery applies here. Every tagging operation goes through
a `JavAITagRepository` **instance** you construct yourself, never a static call and never a woven method on
the tagged class. If you find yourself looking for `article.addTag(...)`, stop — it doesn't exist by design;
you want `tagging.addTag(article, someTag)` instead, where `tagging` is a `JavAITagRepository` you built.

### Making a class taggable

```java
@Entity
@Taggable
public class Employee implements dev.xtrafe.javai.tagging.Taggable {
    @Id private UUID id;
    @dev.xtrafe.javai.annotations.PromptContext private String jobTitle;
    @dev.xtrafe.javai.annotations.PromptContext private String department;
    @TagIgnore @dev.xtrafe.javai.annotations.PromptContext private String internalNotes; // visible to
                                                                                           // completions, not the classifier
    // getters/setters ...
}
```

`@Taggable` + the hand-implemented marker interface is the whole story — there is no third piece to add.
Every taggable instance also needs a `@jakarta.persistence.Id`-annotated `UUID` field (`JavAITagRepository`
reflects on it to build a `TaggableRef`), matching the same fixed-`UUID`-identity convention
`JavAIRepository` already requires.

### `Tag` and `TagSet` — ordinary persisted entities, not a special case

```java
JavAIPersistenceConfig config = JavAIPersistenceConfig.builder()
        .backend(JavAIPersistenceConfig.Backend.POSTGRES)/* ... */.build();
TagSetRepository tagSetRepo = JavAIPI.repository(TagSetRepository.class, config);
TagRepository tagRepo = JavAIPI.repository(TagRepository.class, config);

TagSet securitySet = new TagSet(/* ... */);
tagSetRepo.save(securitySet);

Tag urgent = new Tag(securitySet, "en", "Urgent");   // slug ("urgent") derived once, here, from "Urgent" --
                                                       // there is no setSlug; renaming is delete-and-recreate
tagRepo.save(urgent);
```

**Localizing a whole taxonomy at once.** A `Tag` or `TagSet` takes its entire translation bundle in one go,
as a `Map` or as JSON — which is what you want when the catalog lives in translation files:

```java
Tag zeroDay = Tag.fromLocalizedNamesJson(securitySet, """
        {"en": "Zero-day", "fr": "Faille zero-day", "de": "Zero-Day-Lücke", "ja": "ゼロデイ"}""");

zeroDay.setLocalizedNames(Map.of("es", "Día cero"));   // merges; every existing locale is left alone
```

Three things to know. The slug is derived from **the English entry** (`en`, or a variety like `en-US`) if
there is one, else the first entry that yields a usable slug — so identity doesn't depend on which key
happened to lead the JSON. `getSlugLocale()` tells you which one it used. And the slug is **required**: a
bundle where nothing slugifies (all CJK, say — this library doesn't transliterate) is rejected at
construction, because the slug is a tag's only vectorized field and without one it can never be found.

`getLocalizedNames()` returns an **unmodifiable** snapshot; use `setLocalizedNames(...)` to change anything.

`Tag`/`TagSet` are ordinary `@Entity @JavAIVectorizable` classes persisted through the same
`JavAIPI.repository(...)` mechanism as any other JavAI entity — `TagRepository`/`TagSetRepository` are
just plain, empty `JavAIRepository<Tag>`/`JavAIRepository<TagSet>` marker interfaces. A `Tag`'s constructor
registers itself on its owning `TagSet`'s own tag list automatically (`tagSet.getTags().add(this)`), which is
also what makes `TagSet.summaryVector()` a live, decay-weighted aggregate over every tag ever created against
it, and what `classify()` (below) reads as a `TagSet`'s current candidate tags. A `Tag`'s slug is unique
**within its owning `TagSet` only**, not globally — two different `TagSet`s may each have a tag slugged
`urgent`.

### Constructing a `JavAITagRepository`

```java
JavAITagRepository tagging = new JavAITagRepository(tagRepo, config);              // structural surface only
JavAITagRepository tagging = new JavAITagRepository(tagRepo, config, cortex);      // + classify()/classifyAll()
JavAITagRepository tagging = JavAITagRepository.create(config);                    // realizes TagRepository for you
JavAITagRepository tagging = JavAITagRepository.create(config, cortex);            // same, + a Cortex
```

`Cortex` is supplied only at construction, never via a later setter — a repository built without one still
works fully for structural queries and similarity search; only `classify`/`classifyAll` need it, and throw
`IllegalStateException` if called on an instance that wasn't given one. **Construct one `JavAITagRepository`
per backend/data store you want tagging maintained against** — multiple instances coexist freely (the same
"independent proxies, no dual-write" posture `javai-persistence` documents for ordinary multi-backend
persistence), and this library makes **no attempt to keep tag sets in different backends synchronized with
each other** — reconciling two stores maintaining the same taxonomy is entirely your own responsibility if
you do that.

### Applying tags

Continuing the `Article`/`Comment` domain from the "Worked example" section below — assume both are also
annotated `@Taggable implements dev.xtrafe.javai.tagging.Taggable`, the way this repository's own e2e
fixtures are:

```java
TagSetRepository tagSetRepo = JavAIPI.repository(TagSetRepository.class, config);
TagRepository tagRepo = JavAIPI.repository(TagRepository.class, config);
JavAITagRepository tagging = JavAITagRepository.create(config);

TagSet topics = tagSetRepo.save(new TagSet("topics"));
Tag security = tagRepo.save(new Tag(topics, "en", "Security"));
Tag featured = tagRepo.save(new Tag(topics, "en", "Featured"));

ArticleRepository articles = JavAIPI.repository(ArticleRepository.class, config);
CommentRepository comments = JavAIPI.repository(CommentRepository.class, config);

Article breach = articles.save(new Article("Anatomy of a breach", "..."));
Article recipe = articles.save(new Article("Weeknight pasta", "..."));
Comment reply = comments.save(new Comment("alice", "This matches what we saw last quarter."));

tagging.addTag(breach, security);       // one instance, one tag
tagging.addTag(breach, featured, 0.9);  // a second, independent tag on the same instance, with an affinity
tagging.addTag(reply, security);        // a *different* @Taggable type carrying the same tag
tagging.addTag(recipe, featured);

tagging.hasTag(breach, security);    // true
tagging.removeTag(recipe, featured); // undoes one association; every other tagging above is untouched
```

Every `addTag`/`removeTag` call assumes `breach`/`recipe`/`reply` were already saved through their own
ordinary `JavAIRepository` first — tagging never creates the tagged row/node/document itself, only the
association on top of it.

### Structural queries and classification

| Method | What it does |
|---|---|
| `JavAIList<Tag> tagsOf(Object instance)` | Every tag currently applied to `instance`. |
| `List<Tagging> taggingsOf(Object instance)` | Every tagging on `instance`, carrying what a bare `Tag` cannot: `affinity` (how strongly) and `source` (`"manual"`/`"auto"`/`"aggregate"` — who applied it). On a `@Taggregate` container this read also trues the aggregate up first (see "Taggregate" below). |
| `List<RankedTaggableRef> rankedByTags(List<Tag> tags, List<Class<? extends Taggable>> candidateTypes, int limit)` | Exact multi-tag ranking: every instance carrying at least one query tag, scored `Σ` of its affinity for each (`null` counting 1.0), descending — one indexed query, freely spanning `TagSet`s. The structural counterpart to querying `tagSimilarityIndex()` with `tagQueryVector(tags)`. |
| `JavAIList<TaggableRef> taggedWith(Tag tag, List<Class<? extends Taggable>> candidateTypes)` | Every instance, across the given `@Taggable` types, carrying `tag` — genuinely heterogeneous, one query per backend regardless of how many `candidateTypes` you pass. |
| `void addTag(Object instance, Tag tag)` / `addTag(Object instance, Tag tag, double affinity)` | Applies `tag` to `instance` (`source = "manual"`), optionally with an affinity/match-strength score. Idempotent — a given `(tag, instance)` pair has zero or one association by construction. |
| `void removeTag(Object instance, Tag tag)` | Removes the association, if present. |
| `boolean hasTag(Object instance, Tag tag)` | Structural presence check. |
| `ClassificationResult classify(Object instance, TagSet tagSet)` | One LLM call via the constructor-supplied `Cortex`: marshals `instance`'s `@PromptContext` fields (minus any `@TagIgnore`'d ones), shows the model only `tagSet`'s candidate **slugs**, and diffs the result against `instance`'s existing `source = "auto"` taggings *for this `TagSet` specifically* — adds/updates/removes as needed, never touching `source = "manual"` taggings even for tags in the same set. Client-invoked only; never triggered automatically by a `TagSet` edit. |
| `List<ClassificationResult> classifyAll(Collection<?> instances, TagSet tagSet)` | Convenience batch form — still one LLM call per instance internally, not a fan-out you hand-loop yourself. |
| `ClassificationResult applyClassification(Object instance, TagSet tagSet, List<AppliedTag> results)` | The same reconciliation as `classify`, for a classifier that **is not an LLM** — an image tagger, a rules engine, anything returning `(tag, confidence)` of its own. Needs no `Cortex`. ⚠️ A tag from another `TagSet` throws here, where `classify` silently discards a hallucinated slug: a model inventing a slug is expected noise, but a caller passing the wrong set creates an automatic tagging no later run could ever retract. |
| `VectorIndex<TaggableRef> tagSimilarityIndex()` | See "Tag-similarity search" below. |

`ClassificationResult` is `record ClassificationResult(TaggableRef instance, TagSet tagSet, List<AppliedTag>
appliedTags)`, where `AppliedTag` is `record AppliedTag(Tag tag, Double affinity, String reasoning)` —
`affinity`/`reasoning` are nullable, since a classifier is free to say "this applies" without a strength
score or explanation.

⚠️ **Prefer `applyClassification` to a loop of `addTag`** when a classifier hands you several tags at once.
The tag-summary index is recomputed once per call there and once per tag in a loop, and each recomputation
resolves every association the instance already has — so twenty tags applied one at a time cost on the order
of two hundred id lookups instead of twenty.

⚠️ **Tag an instance you loaded, not one you reached through a lazy association.** JavAI resolves a
persistence proxy before recording a tag, so both work — but resolving forces the load, which on a detached
graph raises `LazyInitializationException`. If you hold an id, load it and tag that.

**Homogeneous vs. heterogeneous `taggedWith`** — the same method either way; the shape of `candidateTypes`
is what decides which you get, continuing the `breach`/`recipe`/`reply` example above:

```java
// Homogeneous: candidateTypes is a single type, so every result is that one type.
JavAIList<TaggableRef> securityArticles = tagging.taggedWith(security, List.of(Article.class));
// -> just breach's TaggableRef. recipe was never given this tag; reply carries it too, but Comment
//    isn't in candidateTypes, so it's excluded from this particular query, not just unmatched.

// Heterogeneous: multiple @Taggable types in one call, no per-type looping or manual union.
JavAIList<TaggableRef> everythingSecurityRelated =
        tagging.taggedWith(security, List.of(Article.class, Comment.class));
// -> both breach (an Article) and reply (a Comment) come back from this single query.
```

### Tag-similarity search

The other query shape — "find other objects, of any type, whose *whole collection* of applied tags looks
similar to this one" — has no equivalent in `JavAIRepository`'s per-type `findNearestBy<Field>Vector`
convention, so it's its own primitive: a maintained, read-mostly `VectorIndex<TaggableRef>` spanning every
`@Taggable` type at once, populated automatically as a side effect of `addTag()`/`removeTag()`/`classify()`
(its own `add`/`remove` refuse — you never populate it yourself):

```java
VectorIndex<TaggableRef> index = tagging.tagSimilarityIndex();
JavAIList<TaggableRef> similar = index.nearestN(article.summaryVector(), 20);

// For an ad hoc collection of tags rather than a single reference vector:
EmbeddingVector adHoc = VectorMath.centroid(tags.stream().map(Tag::vector).toList());
JavAIList<TaggableRef> similarByTags = index.nearestN(adHoc, 20);
```

**Heterogeneous by default, homogeneous by client-side filter** — `tagSimilarityIndex()` takes no type
parameter (the underlying vector is a property of the *tagging*, not of the tagged type), so a plain
`nearestN`/`filterByMinSimilarity` call naturally returns a mix of every `@Taggable` type that happens to
rank close to the reference:

```java
EmbeddingVector securityVector = ((JavAIVectorizable) security).summaryVector();

// Heterogeneous: breach (an Article) and reply (a Comment) can both come back side by side, ranked purely
// by how similar each one's whole tag collection is to the reference -- no type restriction at all.
JavAIList<TaggableRef> similarOfAnyType = index.nearestN(securityVector, 10);

// Homogeneous: filter the same result down to one @Taggable type yourself -- there's no "candidateTypes"
// parameter here the way taggedWith has one, since ranking is over the aggregate tag vector, not a
// per-type index.
List<TaggableRef> similarArticlesOnly = similarOfAnyType.stream()
        .filter(ref -> ref.taggableType().equals(Article.class.getName()))
        .toList();
```

`TaggableRef` is `record TaggableRef(String taggableType, UUID taggableId)`, where `taggableType` is the
tagged instance's **fully-qualified** class name (`instance.getClass().getName()`) — deliberately not the
simple name, to avoid a real collision this project hit between two unrelated `Tag` classes in the same
codebase. If you need to resolve a `TaggableRef` back to a loaded instance, load it through the ordinary
`JavAIRepository` for `Class.forName(ref.taggableType())`, the same way you'd load anything else by id —
`JavAITagRepository` itself never loads or returns full instances, only `TaggableRef` handles.

The underlying vector is a decay-weighted, affinity-scaled sum over `Tag.summaryVector()` for every tag
applied to an instance (so a tag's own recursive tags — a `Tag` can itself be `@Taggable` — contribute too),
recomputed eagerly on every `addTag`/`removeTag`/`classify` call rather than lazily, since combining
already-cached tag vectors is pure arithmetic with nothing to defer.

For an ad hoc collection of tags, `tagging.tagQueryVector(List.of(tagA, tagB))` builds a query vector with
the same weighted-sum construction the index itself uses — the fuzzy counterpart of `rankedByTags` over the
same tags.

### Taggregate — a container's tags, derived from its members'

An `Album` of fifty tagged images is itself *about* something; `@Taggregate` fields make that explicit. Mark
the fields whose targets contribute (a single `Taggable` reference, or a JavAI collection of them — the
owning field decides, never the member), and the container gains ordinary `Tagging` rows with
`source = "aggregate"`: per tag, the **mean contribution over members** (a member's affinity, `null`
counting 1.0, absent counting 0) — so a tag on every member at 0.99 aggregates to 0.99, on one member of
ten to 0.099, and an untagged member dilutes everything. Every tag query (`taggedWith`, `taggingsOf`,
`rankedByTags`, the vector indexes) then works on containers with no new query shape, and containers of
containers compose — a member's own aggregate rows are inputs too, one level deep, recursion-free.

```java
public class Album implements dev.xtrafe.javai.tagging.Taggable {
    @Id private UUID id;
    @Taggregate private JavAISet<MediaImage> mediaImages;   // members' tags flow up
}
```

**The lineage rule**: container and members need exactly what tagging always needs — the `Taggable` marker
interface and an `@Id UUID` — never `@JavAIVectorizable`, on either side. Woven and unwoven types mix
freely in one graph; nothing about the aggregate is woven; a `@MappedSuperclass` field declaration applies
to every subclass.

**The container must be a persisted entity with ordinary mapped associations** — a `@OneToMany`,
`@ManyToMany` or a to-one reference. That is not an extra requirement so much as where membership now
lives: JavAI reads it from the join tables Hibernate already maintains, rather than keeping a copy.

**Staleness needs nothing from you.** Tagging a member (`addTag`/`removeTag`/`applyClassification`) marks
every container holding it and recomputes them after your transaction commits — including containers of
containers, and including a container this process never loaded. There is no reconciler to write, no
loader callback to supply, no bootstrap pass to run, and no cold start: a container saved a moment ago and
never touched since is correct the first time one of its members is tagged.

| Method | What it does |
|---|---|
| *(automatic)* | Everything above. Tag a member; the containers are right. |
| `int rebuildTaggregates()` | ⚠️ **The after-a-restore repair, not a routine call.** Rebuilds every container from its members. Needed because promotion, a restored backup and direct SQL write rows the tagging calls never observe, and after those the aggregates are wrong with nothing to notice. Cost is proportional to your whole world — a maintenance path, never a request one. |

⚠️ **One honest consequence.** Adding or removing a *member* changes what the aggregate should say (the
mean dilutes) without any tag being mutated, so nothing recomputes at that instant. The next tag mutation
beneath that container corrects it, and `rebuildTaggregates()` corrects it without one. The alternative —
recomputing on every `save()` of anything that happens to be contained — is a cost on a write path most
applications would never need it on.

### Concatenated tag text — tags as searchable language

Independent of aggregation, any taggable type can opt in with `@Taggregate(concatenate = true)` at class
level: its tags render as one deterministic string — display names (`en`, slug fallback), strongest first
(affinity descending, then slug), `", "`-joined, capped at the top 50 — embedded and stored beside the
text, recomputed at the same trigger points as the tag-summary vector.

| Method | What it does |
|---|---|
| `String tagText(Object instance)` | The stored rendered string, or `null` if none. |
| `EmbeddingVector tagTextVector(Object instance)` | Its stored embedding, or absent. |
| `VectorIndex<TaggableRef> tagTextIndex()` | The cross-type index over every stored tag-text vector — embed a statement, `nearestN` it, get back the instances whose *tags read most like it*. Like `tagSimilarityIndex()`, its own `add`/`remove` refuse. |

Because the text embeds through the same model as any other text in your system, pixel-derived machine
tags rendered as words land in the same embedding space as captions and bios — "albums about lakes" is one
`tagTextIndex().nearestN(...)` call, no cross-model fusion. Containers that aggregate get this for free;
a non-aggregating class uses the class-level placement alone.

## Installing the library

**Published to Maven Central** — add the dependency coordinates below directly to your own project, no
extra repository configuration needed. Building from source is only necessary if you want an unreleased
commit rather than a tagged version:

```sh
git clone <this repository>
cd javai
mvn install   # builds and installs all 9 modules to the local ~/.m2, in dependency order
```

Then add the **full module set** to your own project's `pom.xml`, at the version declared in this
repository's root `pom.xml` (currently `0.1.10-SNAPSHOT` — check there directly rather than assuming it
hasn't changed). Install everything rather than picking a subset — the modules are small and designed to
interoperate, and not reasoning about which subset a given task needs is one less decision to make:

```xml
<dependency>
  <groupId>io.github.dcaudell</groupId>
  <artifactId>javai-vector</artifactId>
  <version>0.1.10-SNAPSHOT</version>
</dependency>
<dependency>
  <groupId>io.github.dcaudell</groupId>
  <artifactId>javai-model</artifactId>
  <version>0.1.10-SNAPSHOT</version>
</dependency>
<dependency>
  <groupId>io.github.dcaudell</groupId>
  <artifactId>javai-substrate</artifactId>
  <version>0.1.10-SNAPSHOT</version>
</dependency>
<dependency>
  <groupId>io.github.dcaudell</groupId>
  <artifactId>javai-supervision</artifactId>
  <version>0.1.10-SNAPSHOT</version>
</dependency>
<dependency>
  <groupId>io.github.dcaudell</groupId>
  <artifactId>javai-collections</artifactId>
  <version>0.1.10-SNAPSHOT</version>
</dependency>
<dependency>
  <groupId>io.github.dcaudell</groupId>
  <artifactId>javai-persistence</artifactId>
  <version>0.1.10-SNAPSHOT</version>
</dependency>
<dependency>
  <groupId>io.github.dcaudell</groupId>
  <artifactId>javai-completion</artifactId>
  <version>0.1.10-SNAPSHOT</version>
</dependency>
<dependency>
  <groupId>io.github.dcaudell</groupId>
  <artifactId>javai-tagging</artifactId>
  <version>0.1.10-SNAPSHOT</version>
</dependency>
```

`javai-annotations` isn't listed above because it's transitively required by every other module — it comes
along automatically.

For a Gradle project, the equivalent `build.gradle.kts` dependency block is:

```kotlin
dependencies {
    implementation("io.github.dcaudell:javai-vector:0.1.10-SNAPSHOT")
    implementation("io.github.dcaudell:javai-model:0.1.10-SNAPSHOT")
    implementation("io.github.dcaudell:javai-substrate:0.1.10-SNAPSHOT")
    implementation("io.github.dcaudell:javai-supervision:0.1.10-SNAPSHOT")
    implementation("io.github.dcaudell:javai-collections:0.1.10-SNAPSHOT")
    implementation("io.github.dcaudell:javai-persistence:0.1.10-SNAPSHOT")
    implementation("io.github.dcaudell:javai-completion:0.1.10-SNAPSHOT")
    implementation("io.github.dcaudell:javai-tagging:0.1.10-SNAPSHOT")
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

## Vector search: narrowing, ranking and paging

The base convention is `findNearestBy<Field>Vector(EmbeddingVector reference, int limit)` — `<Field>` naming
the woven accessor (`bodyVector()` → `findNearestByBodyVector`), with `findNearestByVector` /
`findNearestBySummaryVector` / `findNearestByConcatenatedTextVector` for the whole-object variants.

**You can also narrow it by an ordinary relational predicate, keep each hit's similarity, and page it.**
Two idioms, which compile to the same query, so they cannot disagree:

```java
public interface MediaNoteRepository extends JavAIRepository<MediaNote> {
    // narrowed: everything after Vector is the ordinary derived-finder grammar
    List<MediaNote> findNearestByCaptionVectorAndKindIs(EmbeddingVector reference, int limit, Kind kind);
    List<MediaNote> findNearestByCaptionVectorAndKindInAndPublishedTrue(
            EmbeddingVector reference, int limit, Collection<Kind> kinds);

    // ranked: keeps the similarity each hit was ranked on
    List<Ranked<MediaNote>> findNearestByCaptionVectorAndKindIs(
            EmbeddingVector reference, Kind kind, Limit limit);

    // paged: a trailing Pageable supplies the window and the offset (so no int limit is declared)
    List<MediaNote> findNearestByCaptionVector(EmbeddingVector reference, Pageable pageable);
}
```

```java
// or build it at runtime, when the predicate isn't known when the interface is written
List<Ranked<MediaNote>> hits = notes.nearestBy("caption")     // nearest()/nearestBySummary() also exist
        .to(reference)
        .where("kind").in(Kind.IMAGE, Kind.SHORT)
        .and("published").isTrue()
        .offset(20).limit(20)
        .ranked();                                            // or .results() for bare entities
```

Prefer the method-name form when it fits: it is validated when the repository is created rather than when it
is called, and the query is visible in the interface. Reach for the builder when the predicate is composed at
runtime, or the shape isn't worth a method.

**Three things to know before relying on it:**

- **The limit applies *after* the predicate.** Asking for the nearest 3 of some kind gives you 3 of them, not
  "however many of the overall nearest 3 happened to be that kind". This is the difference between the
  feature and the over-fetch-and-discard it replaces, so don't reintroduce the over-fetch out of habit.
- **Narrowing is Postgres and MongoDB only. Neo4j refuses it**, and refuses loudly when the repository is
  created — its vector index answers only "the K nearest", so a predicate could only be applied to what the
  index already chose. If you are on Neo4j: use `JavAIVectorizable.query(...)` to rank in memory after an
  ordinary derived finder narrows, or filter in the caller and accept the over-fetch explicitly. Ranked
  results and paging *do* work on Neo4j.
- **`Ranked.similarity()` is plain cosine in `[-1, 1]`** on every backend — the same number `similarityTo`
  gives you in process, so a threshold means the same thing whichever store answered. (`Ranked.distance()` is
  `1 - similarity` if you would rather think in distances.)

⚠️ **MongoDB only:** a vector search index created before this feature existed lacks the `_id` filter path
narrowing needs, and index definitions are not amended in place. If a narrowed search fails on an index an
older version created, drop it and let JavAI recreate it.

## Registering entity types

A `JavAIRepository` is realized with `JavAIPI.repository(YourRepository.class, config)`. That call registers
the repository's entity type, and recursively anything it references.

On the **Postgres** backend, the first actual repository call builds a Hibernate `SessionFactory` whose
metadata is then immutable — so a type nothing knew about by that point can never be mapped. The simplest way
to never think about this is to let the configuration declare its own entities:

```java
JavAIPersistenceConfig config = JavAIPersistenceConfig.builder()
    .backend(JavAIPersistenceConfig.Backend.POSTGRES)
    .postgresUrl(url).postgresUsername(user).postgresPassword(password)
    .entityPackages("com.example.domain")   // scanned for @Entity, up front
    .build();
```

With that, repositories can be created in any order, at any time — including lazily, long after the
application has started. Use `.entityType(Foo.class)` / `.entityTypes(...)` for a type living outside the
scanned packages.

If an `@Entity` under a scanned package belongs to a *different* persistence unit, keep it out with
`.excludeEntityType(Foo.class)`, `.excludeEntityPackages("com.example.reporting.*")`, or `@PersistenceIgnore`
on the class. Being non-vectorized is not a reason to exclude anything — a plain `@Entity` is registered and
served exactly like a vectorized one, it just has no vectors.

Without it, realize every repository before calling a method on any of them. A late call that introduces
nothing new is harmless; one that introduces an unknown type fails, and says which call built the factory.

Neo4j and MongoDB have no such constraint at all — they hold no boot-time metadata.

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
| Postgres schema naming | snake_case by default (`emailVerified` → `email_verified`); override with `JavAIPersistenceConfig.Builder.physicalNamingStrategy(...)` or the general `.hibernateProperty(key, value)` passthrough — see below |
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

**Input size is handled for you.** Every embedding model caps how much text it will accept, and providers
disagree about what happens past that cap — some truncate silently, some reject. JavAI bounds text before
sending it, identically on every provider, so an over-long field yields a vector rather than an exception on
one provider and a quietly partial vector on another. Each provider discovers its model's real limit where
the backend can report it (Ollama's `/api/show`, TEI's `/info`, vLLM's `/v1/models`), falling back to a table
of published limits, and to a conservative 512 tokens for a model it doesn't recognize:

```java
// Nothing required in the common case. Pin the limit when you know better than the table --
// worth doing for OpenAI/Replicate, which publish no endpoint to discover it from:
new EmbeddingProviderOpenAI(apiKey, "text-embedding-3-small", 8_191);
new EmbeddingProviderOllama(URI.create("http://localhost:11434"), "your-model", 32_768);
EmbeddingProviderReplicate.builder().apiToken(token).model("owner/model").maxInputTokens(512).build();
```

**Want one embedding of a whole document rather than of each field?** That is
`concatenatedTextVector()` — it assembles real text across an object graph and embeds it once, where
`summaryVector()` combines already-computed vectors arithmetically. An embedding model can pick up
relationships across a whole document that vector arithmetic cannot.

It is opt-in, in three independent places, because only you know whether folding a `Song`'s lyrics into an
`Album` means anything in your domain:

```java
@JavAIVectorizable
@Summary(concatenate = true)               // 1. embed my own @Vectorize fields as text
public class Chapter {
    @Vectorize private String heading;
    @Vectorize private String prose;

    @Summary(concatenate = true)           // 2. absorb this child's text into mine
    private Chapter continuation;

    @Summary(concatenate = true)           // 3. aggregate these members' text into mine
    @OneToMany(cascade = CascadeType.ALL)  //    (interface-typed + annotated: see "Collection fields" above)
    private JavAIList<Footnote> footnotes = new JavAIArrayList<>();
}
```

Then search it with `List<Chapter> findNearestByConcatenatedTextVector(EmbeddingVector, int)` on your
repository. If the entity does not participate, that method is rejected when the repository is created — not
silently returning nothing forever.

Three things worth knowing. Text is assembled **parent first**, and each object contributes **exactly once**
even if reachable by several paths (unlike `summaryVector()`, where a node reachable twice deliberately
counts twice). Cycles are safe. And a type that says nothing costs nothing: no text, no embedding, no stored
columns.

**Seeding a lot of objects at once?** Vectors are normally computed lazily, one text per HTTP round trip,
discovered deep inside a read — which is fine for ordinary use and slow for bulk loads: a 1,400-item
reference set is 1,400 sequential round trips. `JavAIRuntime.precomputeVectors` gathers the texts first,
de-duplicates them, and sends them in batches instead:

```java
List<Tag> allTags = loadTagCatalog();
JavAIRuntime.precomputeVectors(allTags);        // a few batched round trips, not 1,400 sequential ones
// ...every vector() read afterwards is served from cache
```

Batching is a pure speed-up with no behavioural difference, so it is always safe to call. Ollama, OpenAI,
vLLM and TEI send one request per batch; Replicate falls back to a loop (its request shape is defined per
model, so there is nothing generic to batch against) and is simply no faster than before.

Two things to know about the current behaviour. Truncation is **silent** — text past the limit is dropped
with no exception and no log line, so if you are embedding documents that may run long and you need to know
when that happens, check length yourself before handing text to JavAI. And because JavAI has no tokenizer,
the token limit is converted to characters at a deliberately pessimistic 3 chars/token; a text near the
boundary may lose a little it did not strictly have to. Supplying an explicit `maxInputTokens` does not
change the estimate — it changes only which limit is being estimated against.

There's no `EmbeddingProviderAnthropic`/`EmbeddingProviderGroq` alongside `Cortex`'s Anthropic/Groq
implementations — neither vendor has a native embeddings API (Anthropic recommends Voyage AI instead;
Groq's API has no embeddings endpoint). `EmbeddingProviderReplicate` is also a different case from the
other four: Replicate has no vendor-wide embeddings contract, so it defaults to one popular model and input
field name that you should verify against whatever model you actually run — see `javai-vector/README.md`'s
"Hosted-vendor providers" section for the full detail.

**Transactions, in detail** — since **0.1.5** (Postgres), a `JavAIRepository` call joins a transaction that is
already in progress instead of always opening its own:

- **In a Spring application**, write ordinary `@Transactional` code and nothing else. Several repository
  calls in one such method commit or roll back together, later calls see earlier ones' uncommitted writes,
  and the annotation's attributes (`isolation`, `readOnly`, `rollbackFor`/`noRollbackFor`, `propagation`,
  `timeout`) govern JavAI's writes because they run on the caller's own session and connection. **One wiring
  requirement**: Spring's transaction manager and JavAI must hold the *same* `SessionFactory` instance — the
  match is by identity. There are two ways to arrange that, and which one you want depends on who owns the
  factory; see the next bullet.
- **Choose who owns the `SessionFactory`.** Both directions work, and the choice has a real consequence:
  - **JavAI owns it** (no `.sessionFactory(...)` call) — ask for it with
    `JavAIPI.sessionFactory(config)` (**since 0.1.6**, Postgres only) and wire your transaction manager onto
    that instance. **Prefer this.** It keeps the mapping-time hooks JavAI can only apply to a factory it
    builds itself, which is what makes an interface-typed `@OneToMany JavAIList<T>` a real JavAI collection
    rather than a plain Hibernate bag.
    ```java
    @Bean SessionFactory javAiSessionFactory(JavAIPersistenceConfig config) {
        return JavAIPI.sessionFactory(config);
    }
    @Bean PlatformTransactionManager transactionManager(SessionFactory factory) {
        return new JpaTransactionManager(factory);        // see the note below
    }
    ```
    ⚠️ Use **`JpaTransactionManager`**, not `HibernateTransactionManager`. A `SessionFactory` *is* a
    `jakarta.persistence.EntityManagerFactory`, so the JPA manager takes it directly.
    `HibernateTransactionManager`'s `(SessionFactory)` constructor eagerly unwraps a `javax.sql.DataSource`
    to share connections with plain JDBC, and a JavAI-built factory configures Hibernate's own connection
    provider from raw `jakarta.persistence.jdbc.*` settings — so it throws `UnknownUnwrapTypeException`.
    ⚠️ Asking for the factory **builds** it, which **freezes the entity set**. Call
    `JavAIPI.repository(...)` for every repository your application needs *before* this bean is created, or
    the later ones are rejected (registration-before-use, same rule as calling a repository method).
  - **Spring owns it** — build it yourself and hand it over with
    `.sessionFactory(entityManagerFactory.unwrap(SessionFactory.class))`. Works, but a factory JavAI didn't
    build skips those mapping hooks, so you trade working JavAI collections for working transactions. Only
    pick this if the application genuinely must own the factory for other reasons.
- **Outside Spring**, wrap the calls: `JavAIPI.inTransaction(config, () -> { repoA.save(a); repoB.save(b); })`.
  One commit, or one rollback if the body throws. Nesting joins the outer body rather than starting a second
  transaction, and the scope is thread-bound.
- **With neither**, each call is its own transaction, exactly as before 0.1.5.

**⚠️ Saving many entities? Use `saveAll`, not a loop.** Every `save` embeds only what its own entity's
subgraph needs, so `for (Article a : fleet) articles.save(a);` costs one round trip to the embedding provider
*per entity* — the slowest part of a bulk write, and the one thing wrapping the loop in a transaction does
not help with.

```java
articles.saveAll(fleet);                             // one batched round trip, then the writes
articles.saveAll(fleet, SummaryPolicy.QUEUE_ONLY);   // same, deferring the @Summary recomputation
```

Measured: twelve two-field entities cost 24 provider round trips saved in a loop, **1** through `saveAll`.
Each entity is persisted by exactly the same path either way; it simply finds its vectors already computed.
On Postgres the batch is **one transaction** (all or nothing) and the embeddings happen *before* it opens.
Neo4j and MongoDB batch the embeddings identically but write each entity independently, so a failure part-way
leaves the earlier ones saved — the same limit `inTransaction` has on those backends.

Two edges worth knowing: a write inside `readOnly = true` fails loudly (Postgres rejects the INSERT), and
`PROPAGATION_NESTED` is unavailable under `JpaTransactionManager` — Spring's Hibernate JPA dialect has no
savepoint manager, so Spring refuses it before JavAI is reached. **Neo4j and MongoDB have no equivalent**:
every call is its own unit of work there, and `JavAIPI.inTransaction` throws rather than pretending, so
multi-call flows against those backends must be designed to be safely retryable.

**⚠️ Raising the isolation level on a JavAI-owned `SessionFactory` needs two settings, applied together.**
This is worth knowing before you reach for `@Transactional(isolation = …)`, because the failure is loud,
immediate, and looks unrelated to isolation: a bare `JpaTransactionManager` uses `DefaultJpaDialect`, which
cannot prepare a JDBC connection and therefore **refuses every annotated call that asks for a non-default
isolation level** rather than quietly degrading to the default. Both settings are required — either one
alone still fails:

```java
@Bean PlatformTransactionManager transactionManager(SessionFactory factory) {
    HibernateJpaDialect dialect = new HibernateJpaDialect();
    dialect.setPrepareConnection(true);              // already Spring's default; see the note below
    JpaTransactionManager manager = new JpaTransactionManager(factory);
    manager.setJpaDialect(dialect);                  // (1) the dialect that can prepare a connection
    return manager;
}

JavAIPersistenceConfig.builder()
    .backend(JavAIPersistenceConfig.Backend.POSTGRES)
    // (2) the dialect prepares the connection once at transaction start and needs it held to the end;
    //     Hibernate's default releases it after each statement, and Spring refuses the transaction
    //     rather than run it at an isolation level it could not guarantee.
    .hibernateProperty("hibernate.connection.handling_mode", "DELAYED_ACQUISITION_AND_HOLD")
    .postgresUrl(...).postgresUsername(...).postgresPassword(...)
    .build();
```

`setPrepareConnection(true)` is **not** a third requirement — it is already Spring's default, verified by
measurement rather than inherited from the API docs. It is written out above because it is the other half of
what Spring's own refusal message names (*"make sure that its 'prepareConnection' flag is on … and that the
Hibernate connection release mode is set to ON_CLOSE"*), so it is worth being explicit if something in your
configuration might turn it off.

Note the second setting is fixed when the factory is built, so it must be on the `JavAIPersistenceConfig`
before the first repository call. None of this applies when **Spring** owns the factory — a
`LocalContainerEntityManagerFactoryBean` already configures a preparing dialect, which is why raised
isolation works there with no extra wiring. All of the above is pinned by
`JavAIOwnedSessionFactoryTransactionTest` rather than left as prose.

This matters most as the alternative to `@Version` when you want a concurrency guarantee the caller cannot
forget: isolation is enforced by the database on every transaction, where optimistic locking depends on each
writer going through an entity that carries the annotation.

**Postgres schema naming, in detail** — since **0.1.5**, the `SessionFactory` JavAI builds applies
`CamelCaseToUnderscoresNamingStrategy`, so `emailVerified` maps to the column `email_verified` and an entity
`OrderLine` to the table `order_line`, exactly as Spring Boot would. Two things follow:

- **If your schema was created by JavAI 0.1.4 or earlier** and has any multi-word field or class name, its
  columns are the old all-lowercase form (`emailverified`). `hbm2ddl=update` never renames — it would *add*
  the new columns beside the old ones — so either rename them yourself, or pin the previous behavior:
  ```java
  JavAIPersistenceConfig.builder()
      .backend(JavAIPersistenceConfig.Backend.POSTGRES)
      .physicalNamingStrategy(new PhysicalNamingStrategyStandardImpl())   // pre-0.1.5 naming
      .postgresUrl(...).postgresUsername(...).postgresPassword(...)
      .build();
  ```
- **Any other Hibernate setting** goes through `.hibernateProperty(key, value)` (or `.hibernateProperties(map)`),
  applied after JavAI's own settings, so it wins on a key collision. This is the supported way to influence
  the factory JavAI builds without supplying your own `SessionFactory` — which you generally shouldn't, since
  a factory JavAI didn't build skips the mapping-time hooks that JavAI collection fields depend on.

Neo4j and MongoDB are unaffected either way: they classify fields by declared type and have no JPA column
naming to configure.

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
import dev.xtrafe.javai.model.JavAIList;
import dev.xtrafe.javai.model.JavAILinkedHashMap;
import dev.xtrafe.javai.model.JavAIMap;
import jakarta.persistence.*;
import java.util.UUID;

@Entity
@JavAIVectorizable
public class Article implements JavAIGraphNode {   // implements is required -- @JavAIGraphNode alone is not

    @Id
    private UUID id;

    @Vectorize
    @PromptContext
    private String title;

    @Vectorize
    @PromptContext
    private String body;

    // Interface-typed + @OneToMany -> a native Hibernate association. Non-final, because Hibernate
    // substitutes its own PersistentJavAIList into the field.
    @OneToMany(cascade = CascadeType.ALL)
    @Summary
    private JavAIList<Comment> comments = new JavAIArrayList<>();

    // A second to-many of the SAME element type, so it needs its own join table named explicitly --
    // Hibernate would otherwise derive `article_comment` for both. A map also wants @MapKeyColumn.
    @OneToMany(cascade = { CascadeType.PERSIST, CascadeType.MERGE })
    @JoinTable(name = "article_related_comment")
    @MapKeyColumn(name = "related_key")
    private JavAIMap<String, Comment> relatedComments = new JavAILinkedHashMap<>();

    public void setTitle(String title) { this.title = title; }   // re-vectorizes lazily on next vector() read
    public void setBody(String body) { this.body = body; }
    public JavAIList<Comment> getComments() { return comments; }
}
```

Both collection fields are interface-typed, because since 0.1.10 that is the only shape Postgres maps — see
"Collection fields on a persisted `@Entity`" above. Drop the `@Entity`/`@Id`/`@OneToMany` lines and `comments`
can just as well be a plain `final JavAIArrayList<Comment>`; vectors, `@Summary` propagation and `query()`
don't depend on persistence at all, and the concrete type is only a problem when Hibernate has to map it.

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
