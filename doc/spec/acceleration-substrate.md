# Acceleration Substrate

Module: `javai-substrate`. Whitepaper: §4.1, §4.2, §5.6. Depends on `javai-annotations` + `javai-vector` +
`javai-model`.

Everything below the line an application programmer is expected to think about: the mechanism that makes
the other five areas real, without being part of their public surface. Nothing here changes the behavior
described in the other `doc/spec/*.md` files — it only changes how fast or how it's delivered.

**This is the highest-novelty, highest-risk piece of Phase 0. Build and prove a minimal spike here —
a woven setter on a toy class, correctly setting `FieldDirty`, walking `dependents()`, and lazily
recomputing on next read — before committing to the full `javai-model`/`javai-collections` build-out.**

## Primitives

| Tool / Mechanism | Kind | Role |
|---|---|---|
| `javaic` | Compiler driver / javac plugin (Phase 1+, not Phase 0) | Front end: strict Java-superset parser + codegen |
| `javai-substrate` / weaver | Build plugin (Maven/Gradle) or `-javaagent` | Build- or load-time bytecode enhancer (ByteBuddy-based) — the Hibernate-enhancement pattern applied to vectorization |
| `javai` (CLI) | Launcher wrapper | Convenience wrapper around `java`; auto-configures the JavAI runtime jar on the classpath |
| IntelliJ / VS Code plugin | IDE tooling (later phase) | Syntax highlighting and inline diagnostics |
| `invokedynamic` backend dispatch | Runtime mechanism (Phase 2+) | Chooses a similarity/embedding backend per call site without new bytecode instructions |
| Panama FFI + GPU backend (cuvs-java) | Runtime mechanism (Phase 2+) | GPU-accelerated similarity search, drop-in behind the same SPI |
| JavAIVM (optional) | Distribution (Phase 3) | GraalVM-based, deeper JIT awareness — accelerated path, never required |

## What the weaver actually does

```java
// What the developer writes -- and all they ever see:
public void setBody(String body) {
    this.body = body;
}

// What javai-substrate (or javaic, from Phase 1 on) actually weaves in.
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

- Build on ByteBuddy's `EnhancementContext`-style SPI (the same one Hibernate uses for dirty-checking) —
  proven, not novel. Two delivery modes: load-time `-javaagent`, or build-time Maven/Gradle plugin. Support
  both; teams differ on which they prefer.
- JVMTI-based field-watch was evaluated and rejected: 1-2 orders of magnitude more overhead than bytecode
  instrumentation, and `Instrumentation.redefineClasses` can't add fields to an already-loaded class anyway.
- When a real compiler (`javaic`) exists in Phase 1, build it on javac's own parser and Compiler Tree API
  via the sanctioned Plugin SPI (Manifold's model), not an independent ANTLR grammar and not Lombok's
  internal-API-hacking approach — that's what makes the strict-superset claim credible rather than
  aspirational.
- Output is always standard JVM bytecode: no new opcodes, verifiable on any JDK 21+. This is not
  optional — see the hard interop rule in `SPEC.md`.
