# e2e-client-test

A standalone client project -- deliberately **not** one of JavAI Extensions' eight Phase 0 reactor modules,
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

`CompletionE2ETest` additionally proves `javai-completion`'s connector layer end to end against this same
project's domain: a real `OllamaCortex` (via `LocalCompletionDefaults`) grounds a completion in real
`Article`/`Comment` objects wrapped as `ContextableObject`s inside a `PromptContext`, and separately proves
that marshalling one of those real, woven objects only surfaces its `@PromptContext`-annotated fields
(`title`/`body`/`text`/`author`), never an entity `id` or any internal woven state (a cached embedding
vector, dirty-tracking bookkeeping). The completion model (`qwen3:8b`) is baked into the same monolithic
image as the embedding model — no extra container, no extra model pull.

## Persistent container + seeded sample data

`MonolithicInfrastructure`'s container is deliberately **not** torn down after `mvn test` finishes, unlike
every other Testcontainers-managed container in this reactor — `.withReuse(true)`, so the next run attaches
to the same already-running container instead of paying the full image-build-plus-model-pull cost again.

Domain *data* does **not** persist the same way, though — every run, `SampleDataSeeder`
(`src/test/java/.../fixtures/`) truncates every Postgres table and wipes the whole Neo4j graph, then
re-seeds ~50 [DataFaker](https://www.datafaker.net/)-generated articles (four themed topics — Business,
Music, Currency, Color — real English vocabulary, not random noise, chosen so the generated text still
carries real embeddable signal) across both backends. This is deliberate, not a missed optimization: every
pre-existing e2e test inserts its own hand-written fixtures fresh with no deduplication, an assumption that
only held before because the whole database reset with every fresh (non-persistent) container — once the
container started surviving across runs, those fixtures began accumulating as duplicate rows and caused
real self-rank test failures under embedding noise; see `SampleDataSeeder`'s own class javadoc for the full
story. Resetting the (cheap) domain data every run while keeping the (expensive) container/image itself
persistent gets both properties at once. The seeded topics are deliberately distinct from `ArticleFixtures`'s
own topics (Cybersecurity, Cooking, Sports, Space) — an earlier version mirrored those same four and caused
a separate real, reproducible test failure from semantic overlap with a hand-written fixture; see the same
javadoc.

**Required one-time setup, per machine, for reuse to actually work:** Testcontainers ignores
`withReuse(true)` unless `~/.testcontainers.properties` (a file *outside* this repo — Testcontainers' own
per-user config, not something a commit can carry, and deliberately off by default since Testcontainers'
own docs call reuse "not suited for CI") contains:
```properties
testcontainers.reuse.enable=true
```
Without this, the container still works — it just goes back to being cleaned up and rebuilt every run, same
as before this change.

**Trade-off worth knowing:** since the container persists, editing `docker/Dockerfile` no longer takes
effect automatically on the next run. Force a rebuild by removing the persisted container yourself:
```sh
docker ps -a --filter ancestor=javai-e2e-monolithic --format '{{.ID}}' | xargs -r docker rm -f
```

## Provider choice

Which container starts and which `JavAIEmbeddingProvider` gets configured is decided once, by
`dev.xtrafe.javai.vector.LocalEmbeddingDefaults` -- this test has no platform-specific logic of its own,
it just asks that class what to do. Full story (why a second provider exists at all, the exact upstream
bug, how the override works) is in
[`doc/spec/vector-core.md`](../doc/spec/vector-core.md)'s "Provider selection across platforms" section --
short version: Ollama on macOS, TEI (the whitepaper's Phase 0 default) on Linux/Windows, both against the
same reference model (`Qwen/Qwen3-Embedding-0.6B`). Force a specific one regardless of host OS with
`-Djavai.embedding.provider=ollama` (or `tei`).

## Prerequisites

1. Docker running locally (Testcontainers needs it).
2. The eight reactor modules installed to your local Maven repository:
   ```sh
   cd .. && mvn install
   ```
3. For the container to persist across runs (see "Persistent container" above) rather than rebuilding every
   time, add `testcontainers.reuse.enable=true` to `~/.testcontainers.properties` once. Optional — tests
   pass either way, this just controls whether the expensive image build repeats.

## Running

```sh
mvn test
```

First run builds the monolithic image (Postgres+pgvector, Neo4j, Ollama with both models baked in — slow,
several-GB pull); every subsequent run (with reuse enabled per the prerequisite above) attaches to the same
persisted container and skips the build. Sample data is reset and re-seeded on *every* run regardless (see
"Persistent container + seeded sample data" above) — cheap compared to the image build, and necessary for
existing tests' fresh-database assumptions to hold.

## What's deliberately not here

No CI wiring, no Testcontainers Docker Compose module (a single `GenericContainer` is simpler for one
sidecar), and no packaged `-javaagent` jar. Weaving is installed via `ByteBuddyAgent.install()` self-attach
from a `JavAIWeavingLauncherSessionListener` (`META-INF/services`-registered), not from `@BeforeAll` --
installing it there doesn't work, because JUnit's own test discovery reflectively touches (and thereby
unwovenly loads) `Article`/`Comment` before any `@BeforeAll` callback runs; see that class's javadoc. A
real deployment would use a `-javaagent:javai-substrate.jar` JVM flag or a build-time Maven/Gradle plugin
instead (see `doc/spec/acceleration-substrate.md`); packaging that is future work, not required to prove
the functional behavior here.
