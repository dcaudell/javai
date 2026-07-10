# javai-completion

Extension area: **Completion Fabric**. Whitepaper: §5.3, §7.1–§7.3. Full detail:
[`doc/spec/completion-fabric.md`](../doc/spec/completion-fabric.md).

Depends on `javai-collections` (+ `javai-vector`/`javai-model` transitively). Covers both halves of
`doc/spec/completion-fabric.md`: the **connector layer** — naming, constructing, and tuning the objects that
actually talk to an LLM backend, called a **`Cortex`** per this project's own naming (the spec calls this
`JavAICompletionProvider`) — six providers (OpenAI, Anthropic, Groq, vLLM, Ollama, Replicate), easy to
construct several of at once, local or remote, each with its own provider-specific tuning parameters — and
the **RAG-integration half**: grounding a completion in a `JavAIList`/`Set`/`Map` via `PromptContext`, which
this module consumes from `javai-model` rather than owning — see "RAG integration" below for why.

## Primitives

| Element | Kind | Purpose |
|---|---|---|
| `Cortex` | Interface | The connector to one LLM backend — `complete()`, `completeStreaming()`, `providerId()`, `modelId()`. Renames the spec's `JavAICompletionProvider`. |
| `OpenAICortex` / `AnthropicCortex` / `OllamaCortex` / `GroqCortex` / `VLlmCortex` / `ReplicateCortex` | `Cortex` implementations | One per provider, each with its own builder — see "Provider coverage" below |
| `CompletionRequest` | Value type + builder | A `List<String>` of prompt strings + optional `PromptContext` + `promptParams` (a Handlebars template model) + generation parameters + an open-ended `providerOptions` bag for tuning parameters specific to one provider/model |
| `CompletionResult` | Value type | Text result + `providerId`/`modelId`/`completedAt` |
| `LocalCompletionDefaults` | Static utility | The one place this repo decides which local chat model `OllamaCortex` defaults to (`qwen3:8b`) |

`PromptContext`/`Contextable`/`ContextableObject` — the RAG-integration primitives `CompletionRequest`
carries a `PromptContext` of — live in `javai-model`, not here; see "RAG integration" below.

## Cortex construction: no central registry

Each Cortex is an independent object, constructed directly via its own builder — no facade, no
registration step, no bootstrap ordering requirement (deliberately **not** a parallel to
`javai-persistence`'s `JavAIPI.repository(...)`, whose proxy/registration dance exists specifically because
Hibernate's `SessionFactory` metadata is immutable once built; nothing here has an equivalent shared,
boot-once resource):

```java
Cortex gpt    = OpenAICortex.builder().apiKey(System.getenv("OPENAI_API_KEY")).model("gpt-4.1").build();
Cortex claude = AnthropicCortex.builder().apiKey(System.getenv("ANTHROPIC_API_KEY")).model("claude-sonnet-5").build();
Cortex local  = OllamaCortex.builder().endpoint(URI.create("http://localhost:11434")).model("qwen3:8b").build();

// All three are used the same way, right away, side by side -- local and remote both look identical:
CompletionResult fromGpt = gpt.complete(request);
CompletionResult fromClaude = claude.complete(request);
CompletionResult fromLocal = local.complete(request);
```

## Provider coverage: Spring AI where it exists, hand-rolled only where it must

| Provider | Backing | Wire shape |
|---|---|---|
| OpenAI | `spring-ai-openai`'s `OpenAiChatModel` | native |
| Groq | same `OpenAiChatModel`, repointed | **OpenAI-compatible** (`base-url=api.groq.com/openai/v1`) — Groq's own documented integration path |
| vLLM | same `OpenAiChatModel`, repointed | **OpenAI-compatible** (`base-url=http://host:8000/v1`) — vLLM implements this specifically to be an OpenAI drop-in |
| Anthropic | `spring-ai-anthropic`'s `AnthropicChatModel` | native |
| Ollama | Spring AI's low-level `OllamaApi` directly (not `OllamaChatModel`) | native — see below for why the lower-level client |
| Replicate | hand-rolled HTTP client | job-submission + poll, not chat-completions-shaped at all |

OpenAI/Groq/vLLM share one real implementation (`OpenAiCompatibleCortexSupport`, package-private) behind
three distinct public classes — three separate connector types were asked for, and that's the vocabulary
that should show up in code, even though underneath all three just configure a repointed `OpenAiChatModel`.

**Ollama uses Spring AI's low-level `OllamaApi`, not the higher-level `OllamaChatModel`/`OllamaOptions`
pair — deliberately, not an oversight.** As of Spring AI 1.0.9, `OllamaOptions` doesn't yet expose Ollama's
real `think` field (the wire-level toggle for extended reasoning on models that support it, e.g. Qwen3),
while `OllamaApi.ChatRequest.Builder` does. Dropping to the lower-level client here is still exclusively
Spring AI's own official code, just one layer down, and only for this one provider where the higher layer
doesn't yet cover what's needed.

**Ollama's streaming path is hand-rolled, not `OllamaApi.streamingChat()` — a second deliberate deviation
from "wrap Spring AI," found empirically, not assumed.** Against this project's own real Ollama container,
`OllamaApi.streamingChat()`'s reactive decode straight into the `ChatResponse` record hangs indefinitely —
no `onNext`, no `onError`, no `onComplete` — while decoding the identical NDJSON response as plain text
lines completes in under a second. This is a known class of async-parser limitation with custom
deserializers (here, `java.time.Instant`) that an ordinary, fully buffered synchronous parser doesn't hit.
`OllamaCortex` sidesteps it: read raw NDJSON lines via `WebClient.bodyToFlux(String.class)` (proven fast and
reliable), then parse each already-complete line synchronously with a plain `Gson` — GSON, not Jackson,
matching this project's `javai-vector`/`javai-model` (which take no Jackson dependency of their own; see `PromptContext`'s
own javadoc). `ChatResponse`'s snake_case wire fields need `FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES`,
and its `createdAt` field needs a custom `Instant` adapter, since GSON has no built-in `java.time` support
either. `OllamaCortexRealContainerTest` is what caught the original hang, and re-confirmed the GSON-based
fix against a real container afterward — a hermetic (fake-server) test could not have caught either, since
the bug is specific to how a reactive decoder's async tokenizer behaves against a real chunked HTTP response.

**Replicate is the one deliberate deviation from "wrap Spring AI, never write a provider client."**
Justified, not a shortcut: no Spring AI `ChatModel` exists for Replicate, and its API is structurally
different from every other provider here — a call creates a *prediction* (a job), resolved either
synchronously (via the `Prefer: wait` header, used here) or by polling the prediction's own status URL
until it reaches a terminal state. `ReplicateCortex` hand-rolls this the same way `javai-vector`'s
`OllamaEmbeddingProvider` hand-rolls its own small, fixed-shape JSON rather than pulling in a library.
`completeStreaming()` on this one Cortex is a first-pass simplification: it computes the full result via
`complete()` and delivers it as a single chunk, rather than parsing Replicate's real SSE token stream —
correct per the `Cortex` contract, just coarser-grained than the other five providers' real per-token
streaming. Worth revisiting once this connector is verified against a live endpoint.

## Proprietary tuning parameters: `providerOptions`

`CompletionRequest.providerOptions()` is an open-ended `Map<String, Object>` bag — every `Cortex`
implementation documents which keys it actually reads, and an unrecognized key is silently ignored (not an
error), since the same request is often run against several Cortices from several providers at once. Two
concrete, real, tested examples backing this requirement, not just a claim:

- **Anthropic**: `"thinking_budget_tokens"` (an `Integer`) enables extended-thinking mode with that token
  budget, via `AnthropicChatOptions.thinking(ENABLED, budget)`.
- **Ollama**: `"enable_thinking"` (a `Boolean`) sets Ollama's own `think` request field — the one this
  module's real (Testcontainers-backed) test proves actually changes the model's real response (a non-empty
  reasoning trace appears in `message.thinking()`), not just gets accepted and ignored. Any other
  `providerOptions` key on `OllamaCortex` passes straight through as an Ollama request option unmodified
  (e.g. `"num_ctx"`, `"repeat_penalty"`).
- **OpenAI/Groq/vLLM**: `"reasoning_effort"` (a `String`: `"low"`/`"medium"`/`"high"`) maps onto
  `OpenAiChatOptions.reasoningEffort(...)`.

## RAG integration: `PromptContext` lives in `javai-model`

`doc/spec/completion-fabric.md`'s original vision put `toContext(): PromptContext` directly on
`JavAIList<T>`/`SubgraphResult<N,E>`. Taken literally, that's a dependency-direction problem: those types
live in modules upstream of `javai-completion` per `SPEC.md`'s own dependency graph, so a method on them
returning a `javai-completion` type would be an illegal reverse dependency — this is why that half stayed
deferred for an earlier pass.

The fix: `Contextable` (`String toContext(PromptContext prompt)`) and `PromptContext` itself both live in
`javai-model`, alongside `JavAIList`/`JavAISet`/`JavAIMap`, all three of which implement `Contextable`
directly (delegating per-element, so an element's own custom rendering is respected rather than GSON
reflecting the whole collection as one opaque JSON array). This module simply consumes `PromptContext` from
`javai-model`, the same way it already consumes `JavAIList` — no reverse dependency, no facade:

```java
JavAIList<Comment> concerned = article.query(article.bodyVector(), Comment.class);

// Comment doesn't implement Contextable itself, so each result is wrapped in ContextableObject, which
// falls back to GSON's default marshalling; a domain type that implements Contextable directly (its own
// custom, non-JSON rendering) can be added to entries() unwrapped instead.
List<Contextable> entries = concerned.stream().map(ContextableObject::new).toList();

CompletionRequest request = CompletionRequest.builder()
        .prompt("Summarize reader concerns in two sentences.")
        .context(PromptContext.builder()
                .sourceLabel("Comment.query() results")
                .entries(entries)
                .maxLength(2000)      // opt-in budget; unbounded if omitted
                .build())
        .maxTokens(200)
        .build();
```

**`Comment`'s own fields need `@`[`dev.xtrafe.javai.annotations.PromptContext`](../javai-annotations/src/main/java/dev/xtrafe/javai/annotations/PromptContext.java)
for this to render anything.** `ContextableObject`'s GSON marshalling is now allowlist-based — only fields
carrying that annotation are serialized, everything else (an entity's `id`, a woven class's cached
embedding vector, dirty-tracking state) is excluded by default. Without at least one annotated field on
`Comment`, the example above would render each entry as an empty `{}`. `e2e-client-test`'s own
`Article`/`Comment` domain classes annotate their meaningful text fields (`title`/`body`/`text`/`author`)
for exactly this reason — see `CompletionE2ETest` there for the real, working version of this example.

`PromptContext`'s assembly rules (stop-at-first-overflow, no partial entries, silent omission, `sourceLabel`
printed once as a header if set, nested `PromptContext`s honoring their own budget rather than the outer
context's) and the `@PromptContext` field-allowlist are fully documented on `PromptContext`'s own class
javadoc in `javai-model` — see that module's own README for the full writeup, since the type lives there.

**Deliberately not covered by this pass:** `Contextable` on `KnowledgeGraph`/`SubgraphResult`/`VectorIndex`
(`javai-collections`) — those are graph-shaped by design, and GSON's default reflective marshalling has no
cycle guard equivalent to Vector Core's own `enterSummaryComputation`/`exitSummaryComputation`. Rushing a
default `toContext()` onto a type that can legitimately contain cycles risks a real stack overflow; this is
flagged as deferred pending its own cycle-aware design, not silently dropped.

## Multi-prompt requests and Handlebars templating

`CompletionRequest.prompt()` is a `List<String>`, not a single string — `Builder.prompt(String)` appends one
at a time (the common case, unchanged from before), `Builder.prompt(List<String>)` merges a whole list at
once. `CompletionRequest.render()` is what every `Cortex` implementation actually calls to get the final
outbound text (rather than reading `prompt()`/`context()` separately): join the prompt strings
(`"\n\n"`-separated), append the assembled `PromptContext` if one's set, then run the *entire* combined text
through a [Handlebars](https://github.com/jknack/handlebars.java) template with `promptParams()` (a
`Map<String, Object>`) as the model object:

```java
CompletionRequest request = CompletionRequest.builder()
        .prompt("Summarize reader concerns about %%topic%% in two sentences.")
        .context(context)  // may itself contain %%placeholders%%, e.g. inside a marshalled JSON entry
        .promptParam("topic", "the new pricing model")
        .maxTokens(200)
        .build();

Cortex cortex = ...;
CompletionResult result = cortex.complete(request);  // internally calls request.render()
```

A `%%topic%%` placeholder resolves from `promptParams` no matter whether it originated in a prompt string or
inside a `PromptContext` entry's own marshalled JSON — the entire combined text is one Handlebars template.

**Two deliberate deviations from Handlebars' own defaults**, both confirmed empirically before landing on
them (see `CompletionRequestTest`'s collision tests):

- **`EscapingStrategy.NOOP`, not the library default.** Handlebars' default strategy HTML-entity-escapes
  substituted values (`"` → `&quot;`, `&` → `&amp;`). Since `PromptContext.defaultMarshall()` produces real
  JSON — full of `"` characters — rendering under the default strategy would corrupt that JSON on every
  request combining context with `promptParams`. NOOP leaves every substitution verbatim.
- **`%%...%%`, not Handlebars/Mustache's own `{{...}}`.** `{{`/`}}` shows up constantly in real prompt/context
  content — code samples, Go/Jinja/Mustache documentation, JSX — so the default delimiter would make
  ordinary, unintended text collide with real templating syntax (an unresolved `{{key}}` silently renders as
  empty string, Handlebars' own missing-variable behavior). `%` essentially never appears in JSON or English
  prose, so switching the delimiter is a stronger fix than escaping alone — confirmed empirically that once
  the delimiter is `%%`, literal `{{curly}}` text passes through `render()` completely untouched.

A genuinely malformed/unterminated `%%` (not a real placeholder, just a stray token) throws
`HandlebarsException` (unchecked) rather than silently producing garbled output.

## Local Docker model

`LocalCompletionDefaults` defaults `OllamaCortex` to **`qwen3:8b`** (5.2 GB, 40K context) — deliberately
more powerful than the smallest workable option, per explicit direction to prioritize capability over
minimal footprint: current (2026) local-LLM benchmarking consistently ranks it the strongest dense model
under 8B parameters, and it runs acceptably under Ollama's Metal acceleration on Apple Silicon.
`qwen3:4b` (2.5 GB) is the documented one-tier-down fallback if `8b` proves impractically slow in a given
environment — swapping is a one-line change (`javai.completion.local.model` system property or
`LocalCompletionDefaults`'s own constant), not a design change.

Baked into two places, both reading `LocalCompletionDefaults`' model constant so neither can drift from the
other:
- `e2e-client-test/docker/Dockerfile` — the same monolithic container already serving embeddings (Ollama
  already serves both embeddings and chat completions from one running instance), now also pulling
  `qwen3:8b` at image-build time.
- `javai-completion/docker/Dockerfile` — this module's own, smaller, Ollama-only image (no
  Postgres/Neo4j needed for a completions-only test), used by `OllamaCortexRealContainerTest`.

Both bake the model in at image-build time rather than pulling it at container-start time, matching this
project's established pattern (`javai-vector`'s own embedding-provider images do the same) — `mvn test`
never depends on network access at run time, only at (one-time) image-build time.

## What's actually implemented

`Cortex`, `CompletionRequest`/`CompletionResult`, and all six connector classes described above, plus
`LocalCompletionDefaults`. `PromptContext`/`Contextable`/`ContextableObject` (consumed from `javai-model`)
are real and tested there — see that module's own README. Every Cortex is covered by hermetic tests
(request/option-mapping against a fake HTTP server, `com.sun.net.httpserver.HttpServer` — the same pattern
`OllamaEmbeddingProviderTest` already established). `OllamaCortexRealContainerTest` is this module's one
real-backend proof (Testcontainers, this module's own Dockerfile baking in `qwen3:8b`) — there's no
meaningful way to fake whether a real completion, real streaming, or a real tuning parameter's effect
actually works. `e2e-client-test` now wires a real `OllamaCortex` into its own `Article`/`Comment` domain
too (`CompletionE2ETest`), grounding a completion in real, woven objects via `PromptContext`/
`ContextableObject` against the same shared container that project already runs for embeddings/persistence.

**Not yet verified against a live endpoint**: OpenAI, Anthropic, Groq, and Replicate (no API keys were
available at implementation time) and vLLM (its own Docker images are CUDA-first and don't run under
Docker Desktop on Apple Silicon, so there's no way to stand one up in this environment). All five are
implemented against their documented APIs and covered by hermetic tests, but that's a narrower claim than
"proven against the real thing" — stated plainly rather than implying more confidence than earned.

**Not yet built, and documented rather than silently dropped:**
- `Contextable` on `KnowledgeGraph`/`SubgraphResult`/`VectorIndex` (`javai-collections`) — see "RAG
  integration" above for why (GSON's default marshalling isn't cycle-safe, and those types are
  graph-shaped by design).
- Structured/schema-typed `CompletionResult` (a second, schema-bound variant, mentioned in the spec's
  primitives table).
- Real per-token streaming for `ReplicateCortex` (see "Provider coverage" above).
- Real-endpoint verification for OpenAI/Anthropic/Groq/Replicate (pending API keys) and vLLM (pending a
  GPU-capable host).
