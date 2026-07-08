# javai-agent

Extension area: **Acceleration Substrate**. Whitepaper: §4.1, §4.2, §5.6. Full detail:
[`doc/spec/acceleration-substrate.md`](../doc/spec/acceleration-substrate.md).

Depends on `javai-annotations` + `javai-runtime` (it weaves calls into runtime hooks). Everything below the
line an application programmer is expected to think about: the mechanism that makes the other five
extension areas real, without being part of their public surface. Nothing here changes the *behavior*
described in the other `doc/spec/*.md` files — it only changes how fast, or by what mechanism, that
behavior is delivered.

> **This is the highest-novelty, highest-risk piece of Phase 0.** Build and prove a minimal spike here —
> a woven setter on a toy class, correctly setting `FieldDirty`, walking `dependents()`, and lazily
> recomputing on next read — before committing to the full `javai-runtime`/`javai-collections` build-out.
> Everything downstream assumes this mechanism works; nothing here is implemented yet.

## Primitives

| Tool / Mechanism | Kind | Role | Phase |
|---|---|---|---|
| `javai-agent` / weaver | Build plugin (Maven/Gradle) or `-javaagent` | Build- or load-time bytecode enhancer (ByteBuddy-based) — the Hibernate-enhancement pattern applied to vectorization | **Phase 0** |
| `javaic` | Compiler driver / javac plugin | Front end: strict Java-superset parser + codegen | Phase 1+ |
| `javai` (CLI) | Launcher wrapper | Convenience wrapper around `java`; auto-configures the JavAI runtime jar on the classpath | Phase 1+ |
| IntelliJ / VS Code plugin | IDE tooling | Syntax highlighting and inline diagnostics | Later phase |
| `invokedynamic` backend dispatch | Runtime mechanism | Chooses a similarity/embedding backend per call site without new bytecode instructions | Phase 2+ |
| Panama FFI + GPU backend (cuvs-java) | Runtime mechanism | GPU-accelerated similarity search, drop-in behind the same SPI | Phase 2+ |
| JavAIVM (optional) | Distribution | GraalVM-based, deeper JIT awareness — accelerated path, never required | Phase 3 |

Only the first row is in scope right now. Everything below it is documented so a Phase 0 design decision
doesn't accidentally foreclose it, not because it's on the near-term roadmap.

## What the weaver actually does

```java
// What the developer writes -- and all they ever see:
public void setBody(String body) {
    this.body = body;
}

// What javai-agent (or javaic, from Phase 1 on) actually weaves in.
// Never written, never seen, not part of any area's public surface:
public void setBody(String body) {
    this.body = body;
    this.markDirty();                       // Vector Core's internal hook
    JavAIRuntime.propagateDirty(this);       // back-edge walk
}

// Choosing an accelerated backend is a config change, not a code change --
// the call site above is untouched whether this resolves to CPU or GPU:
//   javai.similarity.backend=cpu     (default, Phase 0)
//   javai.similarity.backend=cuda    (Phase 2+, same invokedynamic call site)
```

## Mechanism notes for implementation

- Build on ByteBuddy's `EnhancementContext`-style SPI — the same one Hibernate uses for dirty-checking:
  proven, not novel. Support both delivery modes (load-time `-javaagent` and build-time Maven/Gradle
  plugin); teams differ on which they prefer.
- JVMTI-based field-watch was evaluated and rejected: 1–2 orders of magnitude more overhead than bytecode
  instrumentation, and `Instrumentation.redefineClasses` can't add fields to an already-loaded class anyway.
- When a real compiler (`javaic`) exists in Phase 1, build it on javac's own parser and Compiler Tree API via
  the sanctioned Plugin SPI (Manifold's model), not an independent ANTLR grammar and not Lombok's
  internal-API-hacking approach — that's what makes the strict-superset claim credible rather than
  aspirational.
- Output is always standard JVM bytecode: no new opcodes, verifiable on any JDK 21+. This is not optional —
  see the hard interop rule in the root [`SPEC.md`](../SPEC.md).

## What's actually implemented

The full weaving contract, not just the mechanism spike that preceded it:

- `JavAIWeaver` — installs a load-time ByteBuddy `AgentBuilder` transformer matching
  `@JavAIVectorizable`-annotated classes. Discovers *every* `@Vectorize` and `@Summary` field (not just
  one), makes the woven type actually `implement` `JavAIVectorizable` + `JavAIDirtyTracking`, adds one
  synthesized state field, and wires `vector()`/`summaryVector()`/`similarityTo()`/`query()`/
  `fieldVector(String)`/per-field `<name>Vector()` accessors — all via `MethodCall` delegating to
  `javai-runtime`'s `JavAIRuntime` statics. No algorithmic logic lives here; see `JavAIRuntime`'s javadoc
  for why.
- `VectorizeFieldSetterAdvice` / `SummaryOnlyFieldSetterAdvice` — instrument each annotated field's
  conventional `setXxx` setter: the original assignment is untouched, matching the worked example in
  doc/spec/acceleration-substrate.md, then `registerDependency`/`propagateDirty` fire unconditionally and
  `markFieldDirty` fires only for `@Vectorize` fields (a `@Summary`-only field is a graph edge, not a
  contributor to this object's own `vector()`).
- `ConstructorExitAdvice` — wires dependency edges for whatever a woven object's fields already point to
  by the time construction finishes. Needed for the common case a setter-based edge alone can't cover: a
  `@Summary` collection field initialized inline (`private final JavAIArrayList<Comment> comments = new
  JavAIArrayList<>();`) and never reassigned, with elements added later through the collection itself.
- `VectorizationWeavingTest` — loads fixtures through an isolated, child-first classloader (with
  `PersistenceHandler.MANIFEST` so ByteBuddy's type pool can resolve cross-fixture field types) *after*
  the transformer installs, proving the weaving is genuinely load-time. Covers: multiple `@Vectorize`
  fields producing distinct per-field vectors, the `Clean → FieldDirty → EmbeddingRecomputing → Clean`
  cycle from doc/spec/vector-core.md, `summaryVector()` propagating through *both* a single `@Summary`
  reference and a `@Summary` collection (the part the original spike didn't cover), and cycle safety on a
  self-referential fixture.

Deliberately still out of scope: non-conventional setters, and multiple annotated fields sharing one
setter.

Byte Buddy was bumped from the `1.15.10` scaffolding placeholder to `1.18.11` while building the original
spike: `1.15.10` cannot parse class files compiled by this repo's JDK 26 toolchain ("Java 26 (70) is not
supported"). `ByteBuddyAgent.install()` also needs `-Djdk.attach.allowAttachSelf=true` to self-attach on
modern JDKs — wired into this module's `maven-surefire-plugin` configuration.
