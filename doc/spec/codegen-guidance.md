# Codegen Guidance

Annotation definitions live in `javai-annotations`; there's no separate `javai-codegen` module. Whitepaper:
§5.5, Appendix B.

**Read `doc/JavAI_Codegen_Guidance.md` in full before generating or modifying any code that carries these
annotations — it applies to work done *in this repository*, not only to JavAI's end users.** This file is
a short pointer; that one is the operative instructions.

The other hat of the two-hat annotation system Vector Core uses (§5.1's `@Vectorize`/`@SearchVisibility`
control what gets embedded; these control how an LLM or coding agent is allowed to read, generate, or
modify code) — a hard pass/fail oracle where one is practical, a natural-language hint where it isn't.

## Primitives

| Annotation | Target | Purpose |
|---|---|---|
| `@Requires` / `@Ensures` / `@Invariant` | method / class | Compiler-checked contracts — a hard pass/fail oracle for agent-written code |
| `@Intent("...")` | method / class | Natural-language statement of purpose, loosely checkable by an LLM oracle where hard contracts aren't practical |
| `@AgentWritable` / `@Frozen` / `@HumanOnly` | class / method / field | Edit-scope permission for autonomous agents, independent of runtime visibility |
| `@Nondeterministic` / `@Costly` | method | Effect tags for model/embedding calls, forcing explicit retry/fallback handling |
| `@Provenance` | compiler-applied | Records generating model + timestamp on agent-written code — never hand-written |

## Example

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
is off-limits regardless of how good the proposed fix looks. See `doc/JavAI_Codegen_Guidance.md` for the
full rule set and a pre-submission checklist.
