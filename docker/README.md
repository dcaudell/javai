# Embedding Generation — Docker Pre-Work

Pre-work only: this stands up the embedding-generation sidecar the whitepaper's §4.5 and
[`doc/spec/vector-core.md`](../doc/spec/vector-core.md)'s `JavAIEmbeddingProvider` SPI describe, so it
exists to build and test against. No code in `javai-runtime` talks to it yet — `JavAIEmbeddingProvider`'s
Phase 0 HTTP client hasn't been written, and this repo is pre-implementation per `SPEC.md`'s "Current
status" section.

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

## Why the model revision is required, not defaulted

Whitepaper §4.5.3 is explicit that bit-identical vectors across hardware/execution providers aren't a
realistic guarantee — what's realistic is *reproducible* output for a fixed model file on fixed hardware,
plus cosine similarity's robustness to the small numerical noise that remains. The practical mitigation is
pinning the TEI image tag and the exact model revision together, so "the same model" really does mean the
same weights; `docker-compose.yml` fails fast (via Compose's `${VAR:?message}` syntax) rather than
silently floating on whatever `main` currently resolves to.

## What's still missing

This is scaffolding, not an integration: no health-check-gated startup ordering with a future `javai-app`
service, no CI wiring, and `JAVAI_EMBEDDING_ENDPOINT`/`JAVAI_EMBEDDING_MODEL` (referenced in the commented
`javai-app` service in `docker-compose.yml`) aren't consumed by any Java code yet. Persistence-side
containers (Postgres+pgvector, Neo4j — see [`doc/spec/persistence-bridge.md`](../doc/spec/persistence-bridge.md))
are a separate, later concern and deliberately out of scope for this embedding-generation pre-work.
