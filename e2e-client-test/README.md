# e2e-client-test

A standalone client project -- deliberately **not** one of JavAI Extensions' eight Phase 0 reactor modules,
and **not** listed in the root `pom.xml`'s `<modules>`. This is downstream code exercising the library the
way any other Java project would: its own `pom.xml`, its own dependency versions, `dev.xtrafe.javai:javai-*`
consumed as ordinary Maven artifacts.

## What this proves

Real embeddings, not a fake/hermetic provider -- against a live container (Ollama on macOS, TEI on
Linux/Windows -- see "Provider choice" below), managed by this module's own `JavAIEnvironment`/
`MonolithicContainer` (`src/main/java/.../environment/`, plain `docker` CLI calls -- see "Persistent
container" below for why this isn't Testcontainers) so `mvn test` is self-contained (no manual
`docker compose up` step). `Article`/`Comment`
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

`CompletionE2ETest` additionally proves `javai-completion`'s connector layer end to end against this same
project's domain: a real `CortexOllama` (via `LocalCompletionDefaults`) grounds a completion in real
`Article`/`Comment` objects wrapped as `ContextableObject`s inside a `PromptContext`, and separately proves
that marshalling one of those real, woven objects only surfaces its `@PromptContext`-annotated fields
(`title`/`body`/`text`/`author`), never an entity `id` or any internal woven state (a cached embedding
vector, dirty-tracking bookkeeping). The completion model (`qwen3:8b`) is baked into the same monolithic
image as the embedding model — no extra container, no extra model pull.

`AgenticSupervisionE2ETest` proves `javai-supervision` end to end, both real Cortex-grounded and both
matching doc/spec/agentic-supervision.md's own worked examples: an `AsyncSupervisionListener`
(`SentimentAggregationSupervisor`) classifies each `submitFeedback` call's return value via a real
completion, fire-and-forget, and aggregates a running count across three calls rather than reacting to just
one; a `SyncSupervisionListener` (`SqlInjectionGuardSupervisor`) blocks on a real completion at PRE to
decide whether a `searchAccounts` filter is a SQL injection attempt, vetoing the call by throwing when it
is. Both listeners and the annotated `SupervisedTextOperations` methods they're scoped to live under
`src/test/java/.../supervision/`, not `domain/` — deliberately plain, no `@Entity`/`@JavAIVectorizable`,
since Agentic Supervision has no dependency on persistence or vectorization. `JavAIWeavingLauncherSessionListener`
installs `SupervisionWeaver` alongside `javai-substrate`'s `JavAIWeaver`, for the identical
early-class-loading reason already documented there. Kept to five real completions total (three sentiment
classifications, two SQL-injection checks) — real wall-clock cost varies a lot by whether `qwen3:8b` is
already loaded in the container's Ollama instance: a cold load (container just started, or idle a long
time) can cost several minutes on the *first* real completion in a run; once warm, all five typically
complete in a few seconds combined. `forkedProcessTimeoutInSeconds` is set generously (3600s) to absorb the
cold case without a spurious fork-timeout failure.

## Persistent container + seeded sample data

`JavAIEnvironment`/`MonolithicContainer` (`src/main/java/.../environment/`) own this container's whole
lifecycle directly via the `docker` CLI -- **deliberately not Testcontainers**, after a real, observed
failure mode: Testcontainers' `ImageFromDockerfile` + `GenericContainer.withReuse(true)` re-triggered a full
`docker build` on every single `mvn test` run regardless of whether a healthy container already existed,
and matched "should I reuse it" by the *resulting image's hash* -- so any routine build-cache drift (this
Dockerfile pulls unpinned `apt`/`ollama` content, which drifts over a multi-day project) silently started a
*second* container next to the still-running first one, which `withReuse` then exempted from cleanup
forever. One real session accumulated 44 dangling images (many full ~17.6GB monolithic builds) this way,
and ended up with a running container on a different image than the one actually tagged
`javai-e2e-monolithic:latest`.

`MonolithicContainer` matches by container **name** instead (`javai-e2e-monolithic`, fixed, not
Testcontainers-style random): `docker ps`/`docker ps -a` for that name first, building only if truly
absent. No cache-drift rebuild can ever silently duplicate the container again -- a rebuild only happens if
the container doesn't exist at all. Ports are fixed too (`15432`/`17474`/`17687`/`21434` on the host,
shifted off each service's common native-install default to avoid colliding with, e.g., a locally-installed
Ollama), so there's no more Testcontainers-style dynamic port-mapping lookup either. **No per-machine setup
is required for this to work** -- unlike the old Testcontainers-based reuse, which needed
`~/.testcontainers.properties` opt-in; that requirement is gone entirely.

Domain *data* does **not** persist the same way, though -- every run, `SampleDataSeeder`
(`src/main/java/.../fixtures/`, called from `JavAIEnvironment`'s one-time static initializer) truncates
every Postgres table and wipes the whole Neo4j graph, then re-seeds ~50
[DataFaker](https://www.datafaker.net/)-generated articles (four themed topics — Business, Music, Currency,
Color — real English vocabulary, not random noise, chosen so the generated text still carries real
embeddable signal) across both backends. This is deliberate, not a missed optimization: every pre-existing
e2e test inserts its own hand-written fixtures fresh with no deduplication, an assumption that only held
before because the whole database reset with every fresh (non-persistent) container — once the container
started surviving across runs, those fixtures began accumulating as duplicate rows and caused real self-rank
test failures under embedding noise; see `SampleDataSeeder`'s own class javadoc for the full story.
Resetting the (cheap) domain data every run while keeping the (expensive) container/image itself persistent
gets both properties at once. The seeded topics are deliberately distinct from `ArticleFixtures`'s own
topics (Cybersecurity, Cooking, Sports, Space) — an earlier version mirrored those same four and caused a
separate real, reproducible test failure from semantic overlap with a hand-written fixture; see the same
javadoc.

**Trade-off worth knowing, same as before:** since the container persists, editing `docker/Dockerfile` no
longer takes effect automatically on the next run. Force a rebuild by removing the persisted container
yourself:
```sh
docker rm -f javai-e2e-monolithic
```

## Provider choice

Which embedding provider gets configured is decided once, by `dev.xtrafe.javai.vector.LocalEmbeddingDefaults`
-- `JavAIEnvironment` has no platform-specific logic of its own, it just asks that class what to do (after
`MonolithicContainer` pins the override property to `ollama` unconditionally, since this specific image
bakes in Ollama regardless of host OS). Full story (why a second provider exists at all, the exact upstream
bug, how the override works) is in
[`doc/spec/vector-core.md`](../doc/spec/vector-core.md)'s "Provider selection across platforms" section --
short version: Ollama on macOS, TEI (the whitepaper's Phase 0 default) on Linux/Windows, both against the
same reference model (`Qwen/Qwen3-Embedding-0.6B`). Force a specific one regardless of host OS with
`-Djavai.embedding.provider=ollama` (or `tei`) -- though note `MonolithicContainer` always forces `ollama`
for this particular container regardless, since that's genuinely the only thing baked into this image.

## Prerequisites

1. Docker running locally, with the `docker` CLI on `PATH` (this module talks to it directly, no
   Testcontainers or other library layered in between).
2. The eight reactor modules installed to your local Maven repository:
   ```sh
   cd .. && mvn install
   ```

That's it -- no per-machine reuse opt-in file needed; `MonolithicContainer` reuses an already-running
container by name automatically.

## Running

```sh
mvn test
```

First run builds the monolithic image (Postgres+pgvector, Neo4j, Ollama with both models baked in — slow,
several-GB pull) and starts it; every subsequent run attaches to the same already-running container by name
and skips the build entirely. Sample data is reset and re-seeded on *every* run regardless (see "Persistent
container + seeded sample data" above) — cheap compared to the image build, and necessary for existing
tests' fresh-database assumptions to hold.

## What's deliberately not here

No CI wiring, no Testcontainers (see "Persistent container" above for why this module dropped it entirely),
and no packaged `-javaagent` jar. Weaving is installed via `ByteBuddyAgent.install()` self-attach from a
`JavAIWeavingLauncherSessionListener` (`META-INF/services`-registered), not from `@BeforeAll` -- installing
it there doesn't work, because JUnit's own test discovery reflectively touches (and thereby unwovenly loads)
`Article`/`Comment` before any `@BeforeAll` callback runs; see that class's javadoc. A real deployment would
use a `-javaagent:javai-substrate.jar` JVM flag or a build-time Maven/Gradle plugin instead (see
`doc/spec/acceleration-substrate.md`); packaging that is future work, not required to prove the functional
behavior here.
