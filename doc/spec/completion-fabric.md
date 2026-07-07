# Completion Fabric

Module: `javai-completion`. Whitepaper: §5.3, §7.1–§7.3. Depends on `javai-collections` (+ `javai-runtime`
transitively).

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
| `PromptContext` | Value type | Composed informing material — text, a single object's cached description, or a subgraph's serialized form |
| `CompletionRequest` | Value type | Instruction + `PromptContext` + generation parameters (max tokens, temperature, optional output schema) |
| `CompletionResult` | Value type | Text result, or a schema-typed result when structured output was requested |
| `JavAICompletionProvider` | SPI interface | Same shape as `JavAIEmbeddingProvider`/`JavAISimilarityBackend` — pluggable, config-selected backend |
| `toContext(): PromptContext` | Method | On `JavAIList<T>` and `SubgraphResult<N,E>` alike — identical call whether the subgraph is in-memory or persisted |
| `complete(CompletionRequest): CompletionResult` | Method | Default call — blocking, virtual-thread-backed; sync-looking regardless of provider |
| `completeStreaming(CompletionRequest, Flow.Subscriber)` | Method | Token-streaming variant, backed by `java.util.concurrent.Flow` — opt-in, not the default |

The object-level text used for prompting is not a new serialization system: producing an embedding already
requires deriving text from an object, so that cached text is reused directly as prompt material. Which
fields surface into it is governed by the same `@SearchVisibility`/`@Summary` annotations Vector Core uses.

## Worked examples

```java
// Grounding a completion in a KnowledgeGraph subgraph query:
SubgraphResult<Article, RelatesTo> hits =
    knowledgeGraph.nearestSubgraph(queryVector, 12, /* hops */ 2);

CompletionRequest req = CompletionRequest.builder()
    .prompt("Summarize the security implications of these findings for a non-expert reader.")
    .context(hits.toContext())     // auto text + relationship serialization, budget-aware
    .maxTokens(800)
    .build();

CompletionResult result = completionProvider.complete(req);   // blocking, virtual-thread-backed
completionProvider.completeStreaming(req, chunk -> ui.append(chunk));   // opt-in streaming
```

```java
// An in-memory subgraph (Vector Core's query()) grounds a completion exactly
// the same way a persisted one (Persistence Bridge's repository query) would --
// both return types implement toContext(). No branch for either case.
JavAIList<Comment> concerned = article.query(article.bodyVector(), Comment.class);

CompletionResult brief = completionProvider.complete(
    CompletionRequest.builder()
        .prompt("Summarize reader concerns in two sentences.")
        .context(concerned.toContext())
        .maxTokens(200)
        .build());
```

Provider and locality are pure configuration, never a code branch:

```
javai.completion.provider=anthropic   model=claude-...
javai.completion.provider=openai      model=gpt-...
javai.completion.provider=ollama      model=llama3.2   (local)
```
