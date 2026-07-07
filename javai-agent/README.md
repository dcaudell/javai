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

Nothing yet. `package-info.java` documents the module's purpose and Phase-0-spike-first ordering.
`DependencyWiringTest` only proves the classpath wires up correctly (`javai-annotations`, `javai-runtime`,
and ByteBuddy all resolve and a `new ByteBuddy()` instantiates) — it does not exercise any weaving logic,
because none exists. The next concrete task in this repo is the spike described above.
