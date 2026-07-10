# javai-annotations

Extension area: **Codegen Guidance** (+ the shared vectorization/search-visibility annotation vocabulary
used by Vector Core and Vector Collections). Whitepaper: §5.1 (vectorization annotations), §5.5, Appendix B
(codegen guidance). Full detail: [`doc/spec/vector-core.md`](../doc/spec/vector-core.md) (vectorization
annotations), [`doc/spec/codegen-guidance.md`](../doc/spec/codegen-guidance.md) (agent-permission
annotations).

Plain annotation definitions, no processing logic. No internal dependencies — this is the one module every
other module in the reactor depends on, directly or transitively, which is why it's built first.

There is no separate `javai-codegen` module: Codegen Guidance's entire public surface is the annotation
vocabulary defined here, consumed by whatever generates or edits code (a human, an IDE, or an agent like
Claude Code).

## Two annotation "hats," one module

This module carries two conceptually distinct annotation sets that happen to share a package because
neither has processing logic of its own — the weaver (`javai-substrate`) is what gives the first set behavior,
and an LLM/human reading them is what gives the second set effect.

### 1. Vectorization / search-visibility (Vector Core, Vector Collections)

Controls what gets embedded and how it's searched. See `doc/spec/vector-core.md` for the full semantics.

| Annotation | Target | Purpose |
|---|---|---|
| `JavAIVectorizable` | class | Opts a class into the woven `JavAIVectorizable` interface (see `javai-model`) |
| `Vectorize` / `VectorizeIgnore` | field | Include/exclude a field from the local embedding |
| `SearchVisibility(PUBLIC\|PROTECTED\|PRIVATE)` | field / class | Search-semantic visibility, independent of Java access modifiers |
| `Summary` | field / class | Marks contribution to a container's hierarchical summary vector |
| `EmbeddingModel("id")` | class / field | Overrides which embedding model vectorizes this element |
| `JavAIGraphNode` / `JavAIEdge` | class / record | Declares knowledge-graph participation (Vector Collections) |

### 2. Codegen Guidance — governing what an LLM agent may read, generate, or modify

A hard pass/fail oracle where one is practical, a natural-language hint where it isn't. **Read
[`doc/ai-guidance/JavAI_Codegen_Guidance.md`](../doc/ai-guidance/JavAI_Codegen_Guidance.md) in full before generating or modifying
any code carrying these — it applies to work done *in this repository*, not only to JavAI's end users.**

| Annotation | Target | Purpose |
|---|---|---|
| `Requires` / `Ensures` / `Invariant` | method / class | Compiler-checked contracts — a hard pass/fail oracle for agent-written code. Repeatable via a nested `List` annotation. |
| `Intent("...")` | method / class | Natural-language statement of purpose, loosely checkable by an LLM oracle where hard contracts aren't practical |
| `AgentWritable` / `Frozen` / `HumanOnly` | class / method / field | Edit-scope permission for autonomous agents, independent of runtime (Java/`SearchVisibility`) visibility |
| `Nondeterministic` / `Costly` | method | Effect tags for model/embedding calls, forcing explicit retry/fallback handling |
| `Provenance` | compiler-applied only | Records `generatedBy` + `timestampEpochMillis` on agent-written code — **never hand-written** |

```java
@JavAIVectorizable
@JavAIGraphNode
public class Article {

    @Requires("title != null && !title.isBlank()")
    @Ensures("result.vector() != null")
    @Intent("Normalizes whitespace before an article is (re)vectorized")
    @AgentWritable
    public Article normalize() { /* agent may rewrite this body */ return this; }

    @Frozen
    @Nondeterministic @Costly
    public EmbeddingVector regenerateEmbedding(@EmbeddingModel("e5-large-v3") String model) {
        /* do not modify -- propose changes in prose instead */
        return this.vector();
    }
}
```

An agent may rewrite `normalize()` freely so long as the contract and intent still hold; `regenerateEmbedding()`
is off-limits regardless of how good a proposed fix looks.

## What's actually implemented

All 17 annotations exist as real, compilable definitions (`AgentWritable`, `Costly`, `EmbeddingModel`,
`Ensures`, `Frozen`, `HumanOnly`, `Intent`, `Invariant`, `JavAIEdge`, `JavAIGraphNode`,
`JavAIVectorizable`, `Nondeterministic`, `Provenance`, `Requires`, `SearchVisibility`, `Summary`,
`Vectorize`, `VectorizeIgnore`), each with correct `@Retention`/`@Target`. `Requires`/`Ensures`/`Invariant`
are repeatable, each via a nested `List` container annotation. `AnnotationsSmokeTest` reflectively proves
every annotation is present, retained, and applicable to the right element kind.

This module itself carries no processing/weaving logic by design (see this README's own architecture
section above) — that's `javai-substrate`'s job, and it's real: a full ByteBuddy weaver reads `@Vectorize`,
`@Summary`, `@SearchVisibility`, and `@VectorizeIgnore` off classes annotated with this module's
`@JavAIVectorizable` and synthesizes the entire `JavAIVectorizable`/`JavAIDirtyTracking` contract at
class-load time. See `javai-substrate/README.md` for what's actually implemented there. `EmbeddingModel` is the
one annotation defined here that nothing downstream reads yet (deliberately deferred, not forgotten — see
`javai-vector`/`javai-model`/`javai-persistence` for why). The Codegen Guidance annotations
(`Requires`/`Ensures`/`Invariant`/`Intent`/`AgentWritable`/`Frozen`/`HumanOnly`/`Nondeterministic`/`Costly`/
`Provenance`) remain definitions only, with no runtime or agent-facing enforcement built yet.
