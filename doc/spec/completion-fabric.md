# Completion Fabric

Module: `javai-completion`. Whitepaper: §5.3, §7.1–§7.3. Depends on `javai-collections` (+ `javai-vector`/
`javai-model` transitively).

Takes a result from Vector Core or Vector Collections — a single object, a `query()` list, or a
`nearestSubgraph()` result — and grounds an LLM completion in it, without the calling code branching on
vendor, locality, sync-vs-async, or whether that subgraph came from the live in-process object graph or a
persisted store. `toContext()` is defined once, on the result types themselves, so the completion call
looks identical either way.

## Design goals

- Prompt-and-context oriented, not conversation-oriented: a request is an instruction plus informing
  material, not a growing message history with system/user/assistant turns.
- Provider-agnostic: the same call works against a hosted frontier model or a local model, without
  branching application code.
- Locality- and concurrency-transparent: a local in-process model and a network round-trip to a hosted API
  look identical to the caller.
- Zero-effort context construction: a subgraph returned from a `KnowledgeGraph` query is promptable with
  no manual serialization step.

## Prior art — don't rebuild what's already solved

Spring AI's `ChatClient`/`ChatModel` abstraction already provides a mature, portable completion layer for
Java (OpenAI, Anthropic, Bedrock, Vertex AI, Ollama, Mistral, swappable via configuration). JavAI Extensions
does **not** write its own provider clients — it defines a completion-and-context layer native to the
object graph and wraps `ChatModel` implementations underneath, the same "depend on, don't fork" posture
used for persistence and GPU acceleration. The innovation here is the object graph, automatic context
generation, and semantic serialization — not another API client.

## Primitives

| Element | Kind | Purpose |
|---|---|---|
| `PromptContext` | Record (`javai-model`), also fulfills `List<Contextable>` | Composed informing material — an ordered bag of `Contextable` entries, assembled into one String on demand; also a full `List<Contextable>` in its own right, so whole lists of entries can be `add`ed/`addAll`ed onto an already-built instance, not just supplied at construction |
| `Contextable` | Interface (`javai-model`) | `toContext(PromptContext): String` — anything renderable as prompt material; `JavAIList`/`Set`/`Map` implement it directly |
| `ContextableObject<T>` | Record (`javai-model`) | Wraps an arbitrary object as a `Contextable` via GSON's default marshalling |
| `CompletionRequest` | Value type (`javai-completion`) | `List<String>` of prompt strings + `PromptContext` + `promptParams` (a Handlebars template model, `Map<String, Object>`) + generation parameters (max tokens, temperature, optional output schema) |
| `CompletionRequest.render()` | Method (`javai-completion`) | Joins the prompt strings, appends the assembled `PromptContext`, then renders the whole combined text as a Handlebars template against `promptParams` — see "Prompt templating" below |
| `CompletionResult` | Value type (`javai-completion`) | Text result, or a schema-typed result when structured output was requested |
| `Cortex` | Interface (`javai-completion`) | Same shape as `JavAIEmbeddingProvider`/`JavAISimilarityBackend` — pluggable, config-selected backend per provider. Named `JavAICompletionProvider` in earlier drafts of this spec; `Cortex` is the name actually implemented |
| `complete(CompletionRequest): CompletionResult` | Method | Default call — blocking, virtual-thread-backed; sync-looking regardless of provider |
| `completeStreaming(CompletionRequest, Flow.Subscriber)` | Method | Token-streaming variant, backed by `java.util.concurrent.Flow` — opt-in, not the default |

**Revised from this spec's original vision:** `toContext(): PromptContext` was originally described as a
method directly on `JavAIList<T>`/`SubgraphResult<N,E>`, returning a `javai-completion` type. Taken
literally, that's an illegal reverse dependency — those two types live in modules upstream of
`javai-completion` per `SPEC.md`'s dependency graph. The actual implementation instead puts `Contextable`
(the method contract) and `PromptContext` (the return type) both in `javai-model`, alongside
`JavAIList`/`JavAISet`/`JavAIMap`, which implement `Contextable` directly — no reverse dependency, and the
"identical call whether in-memory or persisted" goal is preserved. `KnowledgeGraph`/`SubgraphResult`
(`javai-collections`) do not implement `Contextable` yet — deferred pending a cycle-safe design, since
GSON's default marshalling has no protection against the cycles a graph can legitimately contain (see
`javai-model`'s own README).

The object-level text used for prompting is not a new serialization system: producing an embedding already
requires deriving text from an object, so that cached text is reused directly as prompt material. Which
fields surface into it is governed by the same `@SearchVisibility`/`@Summary` annotations Vector Core uses.

## Prompt templating: Handlebars, with a non-default delimiter and escaping strategy

`CompletionRequest.render()` joins the prompt strings, appends the assembled `PromptContext`, then renders
the whole combined text as a [Handlebars](https://github.com/jknack/handlebars.java) template against
`promptParams` — so a placeholder resolves from `promptParams` whether it originated in a prompt string or
inside a context entry's own marshalled JSON. Two deliberate deviations from Handlebars' own defaults,
confirmed empirically before landing on them:

- **`EscapingStrategy.NOOP`, not the library default.** Handlebars' default HTML-entity-escapes substituted
  values (`"` → `&quot;`), which would corrupt the real JSON `PromptContext.defaultMarshall()` produces.
- **`%%...%%`, not `{{...}}`.** `{{`/`}}` shows up constantly in real prompt/context content (code samples,
  Go/Jinja/Mustache documentation), so the default delimiter would make unintended text collide with real
  templating syntax. `%` essentially never appears in JSON or English prose.

See `javai-completion`'s own README and `CompletionRequest`'s class javadoc for the full rationale and the
collision tests (`CompletionRequestTest`) that lock in this behavior.

## Worked examples

```java
// An in-memory query result (Vector Core's query()) grounds a completion -- real and tested today,
// since JavAIList implements Contextable directly:
JavAIList<Comment> concerned = article.query(article.bodyVector(), Comment.class);

CompletionResult brief = cortex.complete(
    CompletionRequest.builder()
        .prompt("Summarize reader concerns in two sentences.")
        .context(PromptContext.builder()
                .sourceLabel("Comment.query() results")
                .entries(concerned.stream().map(ContextableObject::new).toList())
                .maxLength(2000)     // opt-in budget; unbounded if omitted
                .build())
        .maxTokens(200)
        .build());
```

```java
// Grounding a completion in a KnowledgeGraph subgraph query -- NOT yet real: SubgraphResult doesn't
// implement Contextable yet (deferred pending a cycle-safe design, since a graph can legitimately
// contain cycles that GSON's default reflective marshalling has no protection against). Shown here as
// the still-aspirational target once that follow-on pass lands:
SubgraphResult<Article, RelatesTo> hits =
    knowledgeGraph.nearestSubgraph(queryVector, 12, /* hops */ 2);

CompletionRequest req = CompletionRequest.builder()
    .prompt("Summarize the security implications of these findings for a non-expert reader.")
    .context(PromptContext.builder().entries(List.of(hits)).build())   // aspirational
    .maxTokens(800)
    .build();

CompletionResult result = cortex.complete(req);   // blocking, virtual-thread-backed
cortex.completeStreaming(req, chunk -> ui.append(chunk));   // opt-in streaming
```

Provider and locality are pure configuration, never a code branch:

```
javai.completion.provider=anthropic   model=claude-...
javai.completion.provider=openai      model=gpt-...
javai.completion.provider=ollama      model=llama3.2   (local)
```
