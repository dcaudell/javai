# Embedding Generation — Docker

This stands up the embedding-generation sidecar the whitepaper's §4.5 and
[`doc/spec/vector-core.md`](../doc/spec/vector-core.md)'s `JavAIEmbeddingProvider` SPI describe.
`javai-vector`'s `TextEmbeddingsInferenceProvider` is the real HTTP client that talks to it. There's also
`OllamaEmbeddingProvider`, a second real implementation for the Apple-Silicon-specific gap described below.
[`../e2e-client-test`](../e2e-client-test) exercises the whole thing end to end — real embeddings, not a
fake provider — against the whitepaper's reference model, managed via Testcontainers rather than this
compose file directly.

## What this is, and isn't

Per whitepaper §4.5.2, JavAI does not hand-roll a Python/PyTorch embedding server. It depends on Hugging
Face's **text-embeddings-inference** (TEI), an actively maintained, purpose-built serving toolkit,
consistent with the "depend on, don't build" posture used elsewhere for persistence (Hibernate) and
completions (Spring AI). TEI exposes the same REST API regardless of which hardware backend is running
behind it, so `JavAIEmbeddingProvider`'s eventual implementation is a thin HTTP client that never needs to
know which image is running.

Three deployment shapes exist for TEI (§4.5.2's "one model file, one SPI, three deployment shapes"); this
repo's compose file covers the two that containerize cleanly:

| Profile | Image | Hardware | Notes |
|---|---|---|---|
| `cpu` | `text-embeddings-inference:cpu-1.9` | Any CPU | Phase 0 default — whitepaper §9.1 is explicitly CPU-only |
| `cuda` | `text-embeddings-inference:cuda-1.9` | NVIDIA GPU | Same model, same API, just faster — requires the NVIDIA Container Toolkit on the Docker host |

**Apple Silicon/Metal has no profile here.** Docker Desktop on macOS cannot pass a Metal GPU through to a
container, so TEI's `metal` image isn't reachable this way. For local Apple Silicon acceleration, run the
`text-embeddings-router` binary natively (see [TEI's releases](https://github.com/huggingface/text-embeddings-inference/releases))
instead of through this compose file — this matches Figure 4 in the whitepaper: sidecar container for
dev/cloud now, in-process later.

**The `cpu-1.9` image itself is x86_64-only — confirmed by inspection, no `linux/arm64` entry in its
manifest list.** On an Apple Silicon host, `docker pull`/`docker run` for the `cpu` profile fails outright
with "no matching manifest for linux/arm64/v8" unless you explicitly pull the amd64 variant under
emulation first:
```sh
docker pull --platform linux/amd64 ghcr.io/huggingface/text-embeddings-inference:cpu-1.9
```
This runs under Rosetta/QEMU emulation (Docker Desktop's default on Apple Silicon) — expect it to be
noticeably slower than on native x86_64 or a `cuda` host, and treat it as a local-dev fallback, not
something to rely on for realistic performance numbers.

**Worse than slow: `Qwen/Qwen3-Embedding-0.6B` doesn't actually run on TEI's CPU backend at all, on any
architecture** — a confirmed, unresolved upstream bug in TEI's Candle backend, not an Apple-Silicon-only
gap. Full details (the exact error, the upstream issue links, why a separately-suggested
`attn_implementation="eager"` fix doesn't apply here) are in
[`doc/spec/vector-core.md`](../doc/spec/vector-core.md)'s "Provider selection across platforms" section —
this file won't repeat them.

**Workaround: `--profile ollama`**, added to this compose file specifically for this gap — runs the same
reference model through Ollama's GGUF/llama.cpp stack instead, unaffected by TEI's bug, on an image that's
natively arm64 (no emulation at all on Apple Silicon). Needs `OllamaEmbeddingProvider` on the client side,
not `TextEmbeddingsInferenceProvider` — the REST contract is different.
`dev.xtrafe.javai.vector.LocalEmbeddingDefaults` (in `javai-vector`) is the single place that picks
between the two automatically based on host OS (macOS → Ollama, else → TEI, overridable via the
`javai.embedding.provider` system property) — see [`../e2e-client-test`](../e2e-client-test), which is
built entirely on top of it.

## Reference model

[`Qwen/Qwen3-Embedding-0.6B`](https://huggingface.co/Qwen/Qwen3-Embedding-0.6B) (Apache-2.0) is the Phase 0
reference/development model (whitepaper §4.5.1): runs comfortably on a laptop CPU, outputs a 1024-dim
vector, supports Matryoshka Representation Learning for later dimension truncation, and sits on a
same-family size ladder (0.6B/4B/8B) so moving to a larger sibling later is a known-path model swap rather
than an unrelated-architecture jump. Swapping models entirely (a different vendor, a hosted embedding API)
is what `JavAIEmbeddingProvider`'s provider-swap mechanism exists for — a configuration change, not a code
change, once that provider is built.

## Usage

```sh
cd docker
cp .env.example .env
# edit .env: set JAVAI_EMBEDDING_MODEL_REVISION to a real commit SHA -- see the comment in .env.example
docker compose --profile cpu up
```

Swap `--profile cpu` for `--profile cuda` on a GPU-attached host — same compose file, same model, same
API; only the image and hardware reservation differ (whitepaper §4.5.2, Example 14).

`./data/embeddings-cache` is a bind-mounted volume for TEI's downloaded model weights, gitignored so
multi-gigabyte model files never land in version control.

On Apple Silicon, use `--profile ollama` instead (see above), then pull the model once:
```sh
docker compose --profile ollama up -d
docker compose exec javai-embeddings-ollama ollama pull qwen3-embedding:0.6b
```
`./data/ollama-cache` (also gitignored) holds Ollama's downloaded model weights.

## Why the model revision is required, not defaulted

Whitepaper §4.5.3 is explicit that bit-identical vectors across hardware/execution providers aren't a
realistic guarantee — what's realistic is *reproducible* output for a fixed model file on fixed hardware,
plus cosine similarity's robustness to the small numerical noise that remains. The practical mitigation is
pinning the TEI image tag and the exact model revision together, so "the same model" really does mean the
same weights; `docker-compose.yml` fails fast (via Compose's `${VAR:?message}` syntax) rather than
silently floating on whatever `main` currently resolves to.

## What's still missing

No health-check-gated startup ordering with a future `javai-app` service, no CI wiring, and
`JAVAI_EMBEDDING_ENDPOINT`/`JAVAI_EMBEDDING_MODEL` (referenced in the commented `javai-app` service in
`docker-compose.yml`) aren't consumed by any Java code yet — `e2e-client-test` configures its embedding
provider directly rather than through those env vars, since it manages its own container via Testcontainers
instead of this compose file. Persistence-side containers (Postgres+pgvector, Neo4j — see
[`doc/spec/persistence-bridge.md`](../doc/spec/persistence-bridge.md)) are a separate, later concern and
deliberately out of scope here.
