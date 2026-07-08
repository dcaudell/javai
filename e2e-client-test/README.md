# e2e-client-test

A standalone client project -- deliberately **not** one of JavAI Extensions' six Phase 0 reactor modules,
and **not** listed in the root `pom.xml`'s `<modules>`. This is downstream code exercising the library the
way any other Java project would: its own `pom.xml`, its own dependency versions, `dev.xtrafe.javai:javai-*`
consumed as ordinary Maven artifacts.

## What this proves

Real embeddings, not a fake/hermetic provider -- against a live container (Ollama on macOS, TEI on
Linux/Windows -- see "Provider choice" below), managed by [Testcontainers](https://testcontainers.com) so
`mvn test` is self-contained (no manual `docker compose up` step). `Article`/`Comment`
(`src/main/java/.../domain/`) are plain classes annotated `@JavAIVectorizable` -- ordinary client code, no
reflection, no isolated classloaders, no `implements JavAIVectorizable` written by hand.
`ArticleGraphEmbeddingE2ETest` builds a real object graph (a single `@Summary` reference plus a
`@Summary` collection) and asserts:

- Per-field vectors (`fieldVector("title")`/`fieldVector("body")`) are real (1024-dim, per whitepaper
  §4.5.1) and distinct.
- `vector()`'s lazy lifecycle: dirty after a setter call, clean and changed after the next read, unchanged
  on a repeat clean read.
- `summaryVector()` propagates through **both** containment shapes: mutating the single `@Summary`
  reference changes it, and separately, mutating a comment *inside* the `@Summary` collection changes it
  too.
- `query()` finds every `Comment` reachable from the `Article`, across both the reference and the list.
- `similarityTo(self)` is ~1.0.

## Provider choice

Which container starts and which `JavAIEmbeddingProvider` gets configured is decided once, by
`dev.xtrafe.javai.runtime.LocalEmbeddingDefaults` -- this test has no platform-specific logic of its own,
it just asks that class what to do. Full story (why a second provider exists at all, the exact upstream
bug, how the override works) is in
[`doc/spec/vector-core.md`](../doc/spec/vector-core.md)'s "Provider selection across platforms" section --
short version: Ollama on macOS, TEI (the whitepaper's Phase 0 default) on Linux/Windows, both against the
same reference model (`Qwen/Qwen3-Embedding-0.6B`). Force a specific one regardless of host OS with
`-Djavai.embedding.provider=ollama` (or `tei`).

## Prerequisites

1. Docker running locally (Testcontainers needs it).
2. The six reactor modules installed to your local Maven repository:
   ```sh
   cd .. && mvn install
   ```

## Running

```sh
mvn test
```

On the Ollama path, `@BeforeAll` runs `ollama pull` inside the container each run (via `execInContainer`);
on the TEI path, the model is fetched as part of the container's own startup (`--model-id`). Either way,
first run downloads several hundred MB over the network (slow, one-time per container); there's no
persistent model cache across runs since Testcontainers doesn't reuse containers by default.

## What's deliberately not here

No CI wiring, no Testcontainers Docker Compose module (a single `GenericContainer` is simpler for one
sidecar), and no packaged `-javaagent` jar. Weaving is installed via `ByteBuddyAgent.install()` self-attach
from a `JavAIWeavingLauncherSessionListener` (`META-INF/services`-registered), not from `@BeforeAll` --
installing it there doesn't work, because JUnit's own test discovery reflectively touches (and thereby
unwovenly loads) `Article`/`Comment` before any `@BeforeAll` callback runs; see that class's javadoc. A
real deployment would use a `-javaagent:javai-agent.jar` JVM flag or a build-time Maven/Gradle plugin
instead (see `doc/spec/acceleration-substrate.md`); packaging that is future work, not required to prove
the functional behavior here.
