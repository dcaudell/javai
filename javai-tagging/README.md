# javai-tagging

Extension area: **Tagging**. Full detail: [`doc/spec/tagging.md`](../doc/spec/tagging.md).

Depends on `javai-collections`, `javai-persistence`, and `javai-completion` (plus `javai-substrate`
directly -- see "This module weaves itself" below). `@Taggable` objects, recursive `Tag`/`TagSet`
taxonomies, LLM-based classification via `Cortex`, and a persistence-backed similarity index over every
tagged instance's aggregate tag vector.

## Primitives

| Element | Kind | Purpose |
|---|---|---|
| `@Taggable` / `@TagIgnore` | Annotations (`javai-annotations`) | Marks a class as taggable; excludes a `@PromptContext` field from the classification prompt specifically |
| `Tag` | `@Entity @JavAIVectorizable @Taggable` | Slug (vectorized identity, derived once at creation), localized display names, description, owning `TagSet` -- recursively `@Taggable` itself |
| `TagSet` | `@Entity @JavAIVectorizable` | A dynamic, persisted collection of `Tag`s; `@Summary tags` means `summaryVector()` is a free decay-weighted aggregate over every member tag |
| `TaggableRef` | Record | `(taggableType, taggableId)` -- `taggableType` is a fully-qualified class name, not the simple name (see "TaggableRef.taggableType" below) |
| `TagRepository` / `TagSetRepository` | Interfaces | Plain `JavAIRepository<Tag>`/`JavAIRepository<TagSet>` marker interfaces, realized via the ordinary `JavAIPI.repository(Class, JavAIPersistenceConfig)` mechanism |
| `JavAITagRepository` | Instance wrapper (not a static facade) | Wraps an already-realized `TagRepository` with the tagging-association surface: `addTag`/`removeTag`/`hasTag`/`tagsOf`/`taggedWith`, `classify`/`classifyAll`, `tagSimilarityIndex()`. One instance per backend/data store -- see "No global tagging state" below |
| `ClassificationResult` | Record | What one `classify()` call applied -- `AppliedTag(tag, affinity, reasoning)` per matched candidate |
| `VectorIndex<TaggableRef>` | Interface realization (`javai-collections`) | `tagSimilarityIndex()`'s return type -- read-mostly, maintained automatically, not caller-populated |

## Tag/TagSet persistence: unchanged `JavAIRepository`, no bespoke code

`Tag`/`TagSet` are ordinary `@Entity @JavAIVectorizable` classes, persisted the exact same way any other
JavAI entity is -- via `JavAIPI.repository(...)`, the existing `javai-persistence` mechanism, completely
unmodified. Only the *association* (`Tagging`) and the tag-summary-vector index need new, backend-specific
code, since `JavAIRepository`'s CRUD contract has no concept of either.

**`Tag`'s constructor registers itself on its owning `TagSet`'s own `tags` list** (`tagSet.getTags().add(this)`)
-- a real, load-bearing detail, not incidental: `TagSet.getTags()` is what `JavAITagRepository.classify()`
reads as a `TagSet`'s current candidate tags, and without this the list would silently stay empty for every
`Tag` created after its `TagSet`. This also keeps `TagSet`'s own `summaryVector()` a live aggregate over
every tag actually created against it, with no separate "register the tag" step a caller could forget.

## No global tagging state -- `JavAITagRepository` is an instance, not a facade

Earlier revisions of this module exposed a `JavAITagging` static facade (`configureTagging(...)` setting an
ambient "current backend" pointer every other static method read). That design is gone: `JavAITagRepository`
is a plain object, constructed from a `TagRepository` (already realized via `JavAIPI.repository(...)`) and
the `JavAIPersistenceConfig` it should use --

```java
JavAIPersistenceConfig config = JavAIPersistenceConfig.builder()
        .backend(JavAIPersistenceConfig.Backend.POSTGRES)./* ... */.build();
TagRepository tagRepository = JavAIPI.repository(TagRepository.class, config);
JavAITagRepository tagging = new JavAITagRepository(tagRepository, config);
// or, if you don't already have a TagRepository proxy of your own:
JavAITagRepository tagging = JavAITagRepository.create(config);

tagging.addTag(article, urgentTag);
boolean tagged = tagging.hasTag(article, urgentTag);
```

Every field (the wrapped `TagRepository` delegate, the resolved `TaggingBackend`, an optional `Cortex` for
classification) is set once, at construction, from arguments the caller supplies -- there is no ambient
"current backend" anywhere to switch, race, or leave misconfigured for a later, unrelated caller.
**Multiple `JavAITagRepository` instances, one per backend, coexist freely** -- construct one per
`JavAIPersistenceConfig` you want tagging maintained against, same "two independent proxies, no dual-write"
posture `javai-persistence` documents for ordinary multi-backend persistence. This module makes **no attempt
to keep tag sets in different backends synchronized with each other** -- if you maintain the same taxonomy
in two stores, reconciling them is entirely the caller's own responsibility.

`Cortex` is supplied at construction too (`new JavAITagRepository(tagRepository, config, cortex)`, or
`JavAITagRepository.create(config, cortex)`), not via a later settable mutator -- a `final` field removes any
cross-thread-visibility question a mutator would otherwise raise, and a repository used only for the
structural surface (no classification) never needs one at all; `classify`/`classifyAll` throw
`IllegalStateException` if called without one.

## `Tagging`: the association, per backend

`Tagging` itself is a plain value type, **not** `@Entity`/`@JavAIVectorizable` -- its persisted shape is
genuinely different per backend, closer to `javai-persistence`'s own "raw escape hatch under a framework"
`RepositoryBackend` pattern than to an ordinary entity:

- **Postgres**: a plain `taggings` table (own lazy JDBC `Connection`, not the Hibernate `SessionFactory`
  managing `Tag`/`TagSet`'s own tables -- no startup ordering dependency between the two), unique on
  `(tag_id, taggable_type, taggable_id)`. `addTag` is a single `INSERT ... ON CONFLICT ... DO UPDATE`,
  which is what actually enforces "zero or one association per `(tag, instance)` pair."
- **Neo4j**: a native `TAGGED_WITH` relationship from the tagged node to the `Tag` node -- `MERGE` on the
  bare relationship pattern (no discriminating properties in the pattern itself) enforces the same
  zero-or-one invariant. `taggableType`/`taggableId` are stored as relationship properties too, redundant
  with the connected node's own identity, so `taggedWith(...)` can filter by fully-qualified type name
  without reconciling against Neo4j's own simple-name node-label convention.
- **MongoDB**: an array of `{tagId, affinity, source, createdAt}` entries embedded directly on the tagged
  document (field `_javaiTaggings`) -- matching the existing `{type, id}` reference-pointer convention
  `RepositoryBackendSpringDataMongo` already uses for collection-typed fields. `addTag` is a pull-then-push
  (no single-operation "upsert one array element by subdocument key" without `arrayFilters`); both halves
  are independently idempotent.

None of the three backends ever creates the tagged node/document/row itself -- `addTag`/`removeTag` assume
the instance was already saved through the ordinary `JavAIRepository` path first. This is the same
"tagging doesn't own the tagged object's own persistence" boundary the structural-surface tests
(`JavAITaggingPostgresE2ETest`/`Neo4jE2ETest`/`MongoE2ETest`) exercise directly.

## `TaggableRef.taggableType`: fully-qualified name, not simple name

The spec's own original wording suggested the simple class name; this implementation deliberately uses the
fully-qualified name instead, after finding a real simple-name collision already present in this exact
codebase (`dev.xtrafe.javai.e2e.domain.Tag` vs. this module's own `Tag`) and confirming two of the three
persistence backends' own existing conventions (Postgres's `owner_type` column, Mongo's `{type, id}`
reference-pointer convention) already use the fully-qualified name -- only Neo4j's own *unrelated* node-label
convention uses the simple name. `TypeNames.simpleNameOf(String)` bridges the two, used only where a backend
must address a Neo4j label or Mongo collection name rather than store/compare a `TaggableRef` itself.

## Classification via `Cortex`

`JavAITagRepository.classify(instance, tagSet)` is client-invoked only -- never triggered automatically by a
`TagSet` edit. It marshals `instance`'s `@PromptContext` fields (minus any `@TagIgnore`'d ones, via the
small `ClassifierContext` reflection utility -- deliberately not a change to `javai-completion`'s own
`PromptContext`/`ContextableObject` marshalling, since `@TagIgnore` has no meaning to general RAG
completions), builds a prompt showing the model only `tagSet`'s candidate **slugs** (never localized names
or descriptions -- slugs are meant to be stable, language-independent, machine-facing identifiers), calls
the configured `Cortex`, and parses the response as a JSON array of `{slug, affinity?, reasoning?}`
(tolerant extraction: the first `[` to the last `]` substring, so incidental prose/markdown fences around
the array don't break parsing).

Diffs the result against `instance`'s existing `source = "auto"` associations **for tags in this specific
`TagSet`**: a returned slug is added or has its affinity updated; a previously-`"auto"` association for a
tag in this set that the model didn't return this time is removed; a slug the model returns that isn't one
of `tagSet`'s own candidates is silently discarded (a hallucination, not an error). `source = "manual"`
taggings, including ones for tags in this very set, are never read or touched by a `classify()` call --
proven directly by `JavAITaggingClassificationE2ETest.reclassifyNeverTouchesManuallyAppliedTags`.

`FakeCortex` (test-only) queues canned JSON responses rather than mocking any backend -- this module's own
diff/marshalling logic is what needs proving here, not whether a real model understands the task (a lower,
separate concern, unlike similarity search which genuinely can't be faked either way).

## Tag-summary vector index

One vector per tagged instance, aggregating everything it's tagged with -- an instance need not itself be
`@JavAIVectorizable` to have one of these, a wholly separate, persistence-only concept from Vector Core's
own per-object-graph `summaryVector()`:

```
tagSummaryVector(instance) = normalize(
    Sum over each Tagging t on instance:
        (t.affinity() != null ? t.affinity() : 1.0) * t.tag().summaryVector()
)
```

Reuses `Tag.summaryVector()` (not `Tag.vector()`) so a tag's own recursive tags contribute automatically, at
the same decay weighting Vector Core already defines. **Recomputed eagerly, not lazily**, after every
`addTag()`/`removeTag()`/`classify()` call -- combining already-cached tag vectors is pure arithmetic (no
`embed()` provider round trip to defer), so there's no reason for a third dirty-flag family alongside
`FieldDirty`/`SummaryDirty`. An instance with zero remaining Taggings is removed from the index entirely,
not left with a stale vector.

`JavAITagRepository.tagSimilarityIndex()` returns a `VectorIndex<TaggableRef>` (`javai-collections`'s own "bare
similarity-search container for cases that don't need full graph semantics"), spanning **every**
`@Taggable` type at once -- `nearestN`/`filterByMinSimilarity` are exactly the query shape needed, with no
new collection interface. Deliberately read-mostly: its own `add`/`remove` refuse, since it's populated
automatically as a side effect of tagging mutations, not by caller `add()`/`remove()` calls the way an
ordinary in-memory `JavAIVectorIndex` is built.

**Per-backend realization**, following the exact per-model-table precedent `javai-persistence` already
establishes:

- **Postgres**: `javai_tag_summary_vectors__<model>`, keyed by `(owner_type, owner_id)` -- same
  `ModelIds.sanitize` keying as `javai_vectors__<model>`, an exact-precision `pgvector` `<=>` distance query
  (no ANN approximation to account for in tests).
- **Neo4j**: a `tagSummaryVector__<model>` property on the tagged instance's own node (the spec's own
  explicit requirement -- "on whatever node represents the instance"), with a **shared secondary label**
  (`JavAITagged`) added to every node the first time its tag-summary vector is written. One native vector
  index, scoped to that shared label, is what lets a single query span every distinct `@Taggable` entity
  type at once, sidestepping Neo4j's per-label vector-index scoping without tracking which entity labels
  have ever been tagged. Removing the label alone (not enumerating and removing every model-qualified
  property) is what excludes a node from every model's index once it has zero remaining Taggings.
- **MongoDB**: a **dedicated** `_javaiTagSummaryVectors` collection, not a field embedded on each tagged
  document's own per-type collection -- deliberately, mirroring Postgres's own dedicated table rather than
  `RepositoryBackendSpringDataMongo`'s per-type collection convention, since a single Atlas `$vectorSearch`
  index is scoped to one collection. One document per `(taggableType, taggableId)` (unique-indexed), a
  `tagSummaryVector__<model>` field per model, a single Atlas Search vector index on this one collection.

Both Neo4j's and MongoDB's own native vector indexes are **approximate** (HNSW-family) -- a self-match's own
returned similarity isn't guaranteed to land at exactly `1.0` the way Postgres's exact `<=>` comparison is,
and MongoDB's `mongot` indexes near-real-time, not synchronously with the write (the same eventual-consistency
gap `RepositoryBackendSpringDataMongoTest` already documents for ordinary field/summary vectors). Both test
classes account for this: a looser similarity threshold than Postgres's own equivalent test, and a
poll-until-visible helper before asserting on a Mongo query result.

## What the classifier sees

Only each candidate `Tag`'s **slug**. Never localized display strings, never the description by default --
see "Classification via `Cortex`" above.

## What's actually implemented

`@Taggable`/`@TagIgnore` (`javai-annotations`); `Taggable` (marker interface), `TaggableRef`, `Tag`,
`TagSet`, `Tagging`, `LocalizedNames` (JSON-string-backed localized display names -- see below for why not
a `Map<String, String>` field); `JavAITagRepository`'s full structural surface (`addTag`/`removeTag`/`hasTag`/
`tagsOf`/`taggedWith`) against all three backends; classification via `Cortex` (`classify`/`classifyAll`,
`ClassifierContext`, `ClassificationResult`); the tag-summary-vector index and `tagSimilarityIndex()`
against all three backends. Covered by `TagTest` (hermetic unit tests: slug derivation/immutability,
`instanceof Taggable`), `TagNearDuplicateDiagnosticTest` (hermetic: proves two different-slug, near-identical
tags coexist unblocked in one `TagSet`, discoverable only via a `similarityTo()` scan -- see doc/spec/
tagging.md's "Uniqueness"), and `JavAITaggingPostgresE2ETest`/`Neo4jE2ETest`/`MongoE2ETest` (real containers
-- there's no meaningful way to hermetically fake whether a real association/similarity search actually
round-trips or ranks correctly) plus `JavAITaggingClassificationE2ETest` (`FakeCortex` + a real Postgres
container, since the diff logic genuinely needs a real, configured `TaggingBackend`).

**A real, previously-latent bug in all three `javai-persistence` `RepositoryBackend` implementations was
found and fixed while building this module**: `writeVectors`/`saveNode`/`saveDocument` all unconditionally
cast the top-level saved entity to `JavAIVectorizable`, which is fine for every previously-tested entity
(always `@JavAIVectorizable` too) but throws a `ClassCastException` for a plain `@Taggable`-only entity --
exactly the shape this module's own spec requires be persistable via ordinary `JavAIRepository`. Fixed by
guarding the vector-writing portion of each with `instanceof JavAIVectorizable`, restructuring each so plain
(non-vectorized) properties are still written regardless. Verified via `javai-persistence`'s own full
existing test suite (unchanged, still 33/33) before this module's own tests were written against the fix.

**`localizedNames` is persisted as a JSON string (`localizedNamesJson`), not a `Map<String, String>` field.**
A real, confirmed `JdbcTypeRecommendationException` surfaced with the original `Map<String, String>` field
(Hibernate has no automatic column mapping for arbitrary `Map` types), compounded by a further discovery
that Neo4j's/MongoDB's own field-classification conventions treat *any* `Map`-typed field as
relationship/reference-forming (designed for entity-valued maps) -- meaning the same field would have also
broken those two backends, just differently. `LocalizedNames.encode`/`decode` (GSON) round-trip a
`Map<String, String>` through the single string field; `getLocalizedNames()`/`setLocalizedName(...)`
decode/encode on demand rather than caching a live map.

**`ModelIds.sanitize` was promoted from package-private to `public`** (`javai-persistence`) specifically so
this module's own tag-summary-vector backends reuse the exact same per-model sanitization
`javai-persistence` already established, rather than duplicating it and risking drift. Nine
`JavAIPersistenceConfig` accessors (`backend()`, `postgresUrl()`/`Username()`/`Password()`,
`neo4jUri()`/`Username()`/`Password()`, `mongoUri()`, `mongoDatabase()`) were similarly widened to `public`
so each `TaggingBackend` implementation can build its own independent connection from the same config a
caller already set up for `JavAIPI` -- the three `external*SessionFactory/Driver/MongoTemplate` overrides
stayed package-private; no cross-module connection-object reuse is attempted (each module opens its own
connection, an accepted Phase 0 simplification).

## A new structural situation: this module weaves itself

Every module before this one either provides weaving machinery for a *consumer's* classes (`javai-substrate`)
or never needed weaving at all. This module is the first to ship its own `@JavAIVectorizable` classes
(`Tag`/`TagSet`) *inside its own jar*, which forced a genuinely new capability: real **build-time** (Maven
plugin) weaving, not just load-time (`-javaagent`) weaving. `JavAIBuildTimeWeaverPlugin`
(`javai-substrate`), a `net.bytebuddy.build.Plugin`, delegates to the exact same `JavAIWeaver.weave(...)`
static method the load-time path already uses -- there is no second transform to keep in sync -- driven by
the official `net.bytebuddy:byte-buddy-maven-plugin`, bound to this module's own `process-classes` phase.
Proven with a real, deletable toy-class spike (a `@JavAIVectorizable` class + a JUnit test installing zero
javaagent) before `Tag`/`TagSet` themselves were written. See this module's own `pom.xml` for the exact
plugin configuration and the two required `<dependencies>` overrides (`byte-buddy` version pin,
`javai-substrate` on the plugin's own classpath).

**Not yet built, and documented rather than silently dropped:**
- Per-`TagSet` slug uniqueness is a stated invariant (see doc/spec/tagging.md's "Uniqueness"), but is not
  enforced by a database-level constraint on any of the three backends -- `Tag`/`TagSet` persistence
  deliberately reuses the ordinary, unmodified `JavAIRepository` mechanism with zero bespoke schema
  additions. A caller creating two same-slug `Tag`s in one `TagSet` today gets two rows/nodes/documents
  that coexist rather than a rejected write.
- Optional composition with `KnowledgeGraph` (surfacing Tagging associations as graph edges when an
  instance is both `@Taggable` and `@JavAIGraphNode`) is described in the spec but not implemented -- opt-in
  composition, not a required dependency in either direction, left for when a concrete use case needs it.
- No dual-write/transactional multi-backend tagging -- same "two independent bindings, no cross-store
  transaction" posture `javai-persistence` itself documents for its own multi-store pattern.
