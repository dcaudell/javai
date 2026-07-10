# javai-vector

Extension area: **Vector Core** (part 1 of 2 — see `javai-model` for the other half). Whitepaper:
§4.1–§4.3 (mechanism), §4.5 (embedding generation), §5.1 (primitives). Full detail:
[`doc/spec/vector-core.md`](../doc/spec/vector-core.md).

Depends on `javai-annotations` only. Pure vector/embedding functionality, nothing else: computing an
embedding, keeping it current, and the dirty-state bookkeeping primitives used to track staleness. No
collection types, no RAG-integration primitives, no `JavAIVectorizable` contract or engine — those live in
[`javai-model`](../javai-model/README.md), a deliberate physical split explained below.

**Module-placement note (why this module is narrower than "Vector Core" the whitepaper describes):**
`JavAIVectorizable` (the contract) and `JavAIRuntime` (the engine implementing it, including the `query()`
graph walk) are dependency-direction hostages of `JavAIList`: `JavAIVectorizable.query()` returns
`JavAIList<T>`, and `JavAIList` in turn `extends JavAIVectorizable` right back. Two types with a genuine
mutual reference can never live in separate modules without an illegal cycle — confirmed this isn't
avoidable by extracting `query()` into its own interface: `JavAIRuntime.summaryVector()`'s cycle-safe walk
checks `value instanceof JavAIVectorizable` to decide whether a `@Summary`-annotated collection field
should contribute its own `summaryVector()` (real, tested behavior), so `JavAIList` needs the *whole*
`JavAIVectorizable` contract, not just `query()`. Since `JavAIList`/`Set`/`Map` have to live upstream of
`javai-collections` for the same compile-order reason they always have, `JavAIVectorizable`/`JavAIRuntime`
follow them into `javai-model` rather than staying here. What's left in this module is exactly the subset
with zero reference to `JavAIList`/`Contextable` at all — confirmed by grepping every file here, not
assumed. See `javai-model`'s own package-info.java for the full trace and
[`doc/module-dependency-graph.md`](../doc/module-dependency-graph.md) for the complete physical module graph.

## Public API

| Element | Kind | Purpose |
|---|---|---|
| `EmbeddingVector` | Record | A versioned vector: `values`, `modelId`, `dims`, `computedAt` — not a bare `float[]` |
| `VectorMath` | Static utility | `normalize`, `addWeighted`, `cosineSimilarity`, `centroid` — CPU similarity backend |
| `JavAIEmbeddingProvider` | SPI interface | Pluggable, versioned embedding-model provider |
| `JavAIDirtyTracking` | Interface | `addDependent`/`dependents()`, `isFieldDirty`/`markFieldDirty`/`clearFieldDirty`, `isSummaryDirty`/`markSummaryDirty`/`clearSummaryDirty` |
| `DirtyTrackingSupport` | Concrete implementation | The entire durable per-object dirty-tracking state, as one object — a woven class gets one synthesized field of this type; concrete collections in `javai-model` hold one directly |
| `EndpointRateLimiter` | Public utility | Cross-instance, cross-module 429/backoff coordination, keyed by normalized endpoint URL — see "Rate limiting" below |
| `RetrySupport` | Public utility | Synchronous retry loop built on `EndpointRateLimiter`; used by both this module's embedding providers and, via the module dependency, `javai-completion`'s `Cortex` implementations |
| `TooManyRequestsException` / `RetryAfterParser` | Public types | The shared 429 signal and `Retry-After` header parser every provider's own detection code funnels into |

## What's actually implemented

- `EmbeddingVector` — real record with a compact constructor validating `dims == values.length` and a
  non-blank `modelId`. Tested (`EmbeddingVectorTest`): stores values/modelId, rejects mismatched dims,
  requires a modelId.
- `VectorMath` — tested (`VectorMathTest`): normalization, weighted addition, cosine similarity, centroid.
- `JavAIDirtyTracking`/`DirtyTrackingSupport` — the shared dirty-state primitive every woven class and every
  concrete JavAI collection (in `javai-model`) uses; its cache accessors (`cachedVector()`/`cacheVector()`/
  etc.) are `public`, not package-private, specifically so `javai-model`'s `JavAIRuntime` and concrete
  collection types (a different module now) can reach them.
- `JavAIEmbeddingProvider` has five real implementations, not just the interface: `EmbeddingProviderOllama`
  and `EmbeddingProviderTextEmbeddingsInference` (Hugging Face's TEI), plus `EmbeddingProviderOpenAI`,
  `EmbeddingProviderVLlm`, and `EmbeddingProviderReplicate` — all plain `java.net.http.HttpClient` clients,
  no JSON library dependency. `LocalEmbeddingDefaults` picks between the first two per host OS (see its own
  javadoc for why: a confirmed TEI/Candle CPU bug on this project's reference model); the other three are
  constructed directly by the consumer. See "Hosted-vendor providers" below for why there's no
  `EmbeddingProviderAnthropic`/`EmbeddingProviderGroq` alongside `javai-completion`'s `CortexAnthropic`/
  `CortexGroq` — those two vendors have no native embeddings API to wrap.
- All five providers retry a `429` via `RetrySupport`/`EndpointRateLimiter` — see "Rate limiting" below.

## Rate limiting: 429s, exponential backoff, coordinated across instances and modules

`EndpointRateLimiter` is a static registry, keyed by normalized endpoint URL (scheme + authority, not path),
shared by both embedding providers here **and** every `Cortex` in `javai-completion` — two independently
constructed instances pointed at the same base URL, even across that module boundary, share the same
limiter, so a 429 seen by one backs off the other too (`EndpointRateLimiterCrossInstanceTest`, in both
modules, proves this with a hermetic `HttpServer` and a timing assertion). Lives here, not in
`javai-completion`, specifically so both modules can share one registry — `javai-completion` depends on this
module transitively, never the reverse. `RetrySupport.withRetry` drives the actual loop (up to five
attempts, preferring the server's `Retry-After` header via `RetryAfterParser`, falling back to exponential
backoff otherwise); each embedding provider's own `send()` method detects the 429 directly off its raw
`HttpResponse` and throws `TooManyRequestsException`, which only `RetrySupport` catches.

## Provider selection across platforms (discovered building the E2E test, not in the whitepaper)

This module ships two real `JavAIEmbeddingProvider` implementations:

| Implementation | Backend | Status |
|---|---|---|
| `EmbeddingProviderTextEmbeddingsInference` | Hugging Face TEI (§4.5.2) | The Phase 0 default — `docker/docker-compose.yml`'s `cpu`/`cuda` profiles |
| `EmbeddingProviderOllama` | Ollama (GGUF via llama.cpp) | Not in the whitepaper — added for the platform gap below |

The reason a second implementation exists at all: TEI's Candle backend has a confirmed, unresolved upstream
bug running the reference model, `Qwen/Qwen3-Embedding-0.6B` (§4.5.1), on CPU — "Intel MKL ERROR: Parameter
8 was incorrect on entry to SGEMM" — reported on native x86_64/AMD hardware, not only under emulation (see
[huggingface/text-embeddings-inference#667](https://github.com/huggingface/text-embeddings-inference/issues/667)
and [#636](https://github.com/huggingface/text-embeddings-inference/issues/636); no upstream fix as of this
writing). It reproduces reliably on macOS, where Apple Silicon additionally needs x86_64 emulation to run
TEI's `cpu-1.9` image at all (it has no arm64 build).

## Hosted-vendor providers (mirroring `javai-completion`'s `Cortex` vendor set)

`javai-completion` ships a `Cortex` for six vendors (OpenAI, Anthropic, Groq, vLLM, Ollama, Replicate).
`javai-vector` mirrors that set for embeddings **only where the vendor actually has an embeddings API**:

| Implementation | Backend | Status |
|---|---|---|
| `EmbeddingProviderOpenAI` | OpenAI's hosted `/v1/embeddings` | Not yet verified against a live endpoint — no API key was available at implementation time (same caveat as `CortexOpenAI`) |
| `EmbeddingProviderVLlm` | Self-hosted vLLM's OpenAI-compatible `/v1/embeddings` | Not yet verified against a live endpoint — no self-hosted instance was available |
| `EmbeddingProviderReplicate` | Replicate's create-prediction-then-poll API | Best-effort — see below; not yet verified against a live endpoint |

**No `EmbeddingProviderAnthropic` or `EmbeddingProviderGroq` exist, on purpose.** Neither vendor has a native
embeddings API to wrap: Anthropic has none at all and officially recommends Voyage AI as a third-party
embeddings partner instead; Groq's own API reference (`console.groq.com/docs/api-reference`, checked when
this table was written) lists Chat completions, Responses, Audio, Models, Batches, Files, and Fine Tuning —
no Embeddings category. Fabricating a client against a nonexistent endpoint would be worse than not having
one, so this project doesn't.

**`EmbeddingProviderReplicate` is a genuinely different case from the other four.** Every other provider in
this family wraps one fixed, vendor-wide contract. Replicate has none — each hosted model defines its own
`cog predict()` input/output schema. `EmbeddingProviderReplicate` defaults to a popular embedding model
(`beautyyuyanli/multilingual-e5-large`) and an input field name (`"text"`) that's a reasonable starting
point, not a confirmed contract — both are overridable via its `Builder` (`model(...)`,
`inputFieldName(...)`), and you should verify them against whatever model you actually run. Output parsing
tolerates both a flat `[0.1, 0.2, ...]` array and a nested one-row `[[0.1, 0.2, ...]]` batch, the two shapes
commonly seen across Replicate embedding models.

Ollama runs the same reference model through a genuinely different stack, unaffected by TEI's bug, on an
image that's natively arm64. `dev.xtrafe.javai.vector.LocalEmbeddingDefaults` is the one place this decision
is made, so it can't drift between "which provider class gets constructed" and "which container actually
gets started":

- Defaults to Ollama on macOS, TEI everywhere else (Linux, Windows, unrecognized).
- Overridable via the `javai.embedding.provider` system property (`ollama` or `tei`).
- Exposes everything a caller needs to both start the right container (`dockerImage()`, `containerPort()`,
  `healthCheckPath()`, `modelIdentifierForContainerStartup()`) and construct the matching provider against
  it (`create(URI)`, `modelLabel()` for the `EmbeddingVector.modelId()` it will produce).

`e2e-client-test`'s `ArticleGraphEmbeddingE2ETest` is built entirely on top of this — it has no
platform-specific logic of its own, just asks `LocalEmbeddingDefaults` what to do.
