# Changelog

Notable changes to JavAI Extensions, newest first. Follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/)
loosely and [Semantic Versioning](https://semver.org/spec/v2.0.0.html) exactly.

This file starts at 0.1.5. Releases before it (0.1.0 through 0.1.4) are described by their git history and
their GitHub releases; nothing is reconstructed here after the fact, since a changelog written from memory is
worth less than the commit log it would be guessing from.

Each entry names the module it affects, because this repository releases all nine modules together at one
version -- a given release usually changes only one or two of them.

## [Unreleased]

### Fixed

- **`javai-persistence` (Postgres): Hibernate's `@Any` mapping no longer fails to boot (OMI-212).** Building
  the `SessionFactory` threw `UnknownEntityTypeException` for any entity type named only by
  `@AnyDiscriminatorValue(entity = …)`. Because that failure is at boot rather than at first use of the
  association, it took out *every* repository call in the configuration -- five unrelated integration test
  classes at once, downstream.

  **Mechanism:** related-type discovery walks each field's declared type. An `@Any` field's declared type is
  deliberately a plain interface with no shared table -- that is the entire point of the mapping -- so the
  walk learned nothing and the concrete targets were never registered. Nothing about the mapping itself was
  missing; JavAI registers entities through ordinary Hibernate annotation scanning, so `@Any` worked as soon
  as the targets were known. The gap was purely discovery.

  Discovery now also registers the types named by `@AnyDiscriminatorValue` (and its repeatable container).
  Note that `@Any` does not cascade by default -- add `@Cascade` if the owner should save its target.

- **`javai-persistence` (Neo4j, MongoDB): an `@Any` field is rejected at registration instead of being
  silently dropped.** Measured before the change: Neo4j saved the owning entity happily and returned `null`
  for the association on reload. Both backends' mapping is hand-rolled with no discriminator concept, and
  reference detection keys off the declared field type -- a plain interface matches neither the reference
  nor the simple-value path, so the field fell into the documented "silently skipped" boundary. Losing data
  quietly is worse than refusing the mapping, so both now fail loudly and name the backend that does support
  it, mirroring how `KnowledgeGraph` fields are already rejected in the opposite direction.

- **`javai-model`, `javai-vector`, `javai-persistence`: JavAI made roughly three embedding calls where one
  was correct (OMI-187).** Reported as untenably slow seeding of a large `TagSet` (~1,400 tags could not be
  seeded in ten minutes). Instrumenting the embedding provider showed 36 calls to seed 12 tags -- 12 correct,
  24 wasted -- in the repeating sequence `[tag.slug, tagSet.slug, ""]`. Two independent defects:

  **1. An empty JavAI collection embedded the empty string, on every read.** `CollectionVectorSupport`
  computed a centroid by calling `embed("")` whenever a collection held nothing vectorizable, to obtain a
  correctly-dimensioned vector. Because real providers reject a genuinely empty input,
  `EmbeddingProviderOllama`/`EmbeddingProviderOpenAI` substitute a single space -- so each empty collection
  cost a live model round trip to embed *a space*, and then contributed that arbitrary, content-free
  direction to every ancestor's `summaryVector()` at the usual decay weight. Both the cost and the
  distortion were invisible to the test suite, whose fake provider happens to hash `""` to the zero vector.

  Measured across 12 in-memory graph shapes (deep chains, wide fan-out, diamonds, cycles, self-reference,
  nested collections, every collection type): **696 wasted calls, every one of them the empty string**, at
  exactly one per empty collection reachable from the read. Now zero -- see `EmbeddingVector.absent()` below.

  **2. A loaded or merged entity re-embedded vectors it already had.** Vector caches live in the woven
  `$javai$state` *instance* field, so they are keyed to object identity and do not survive a persistence
  round trip. Hibernate's `merge()` copies mapped field values onto a managed copy and leaves the tracking
  state behind, so the copy looked brand new and recomputed. Confirmed by measurement rather than inference:
  identical waste under all three `EmbeddingConsistencyMode`s, which rules out a dirty-flag cause, since
  `mustBlockUnderObjectLock` short-circuits on `!slot.everComputed()` regardless of mode.

  Fixed by carrying the vectors across instead of recomputing them -- `JavAIRuntime.transferComputedVectors`
  on both sides of `merge()`, plus reading stored vectors back into a loaded entity's slots on all three
  backends. Only clean, already-computed slots move, so a field the caller actually changed is still
  embedded fresh (pinned by `savedVectorIsAlwaysAccurateUnderImmediateConsistency`).

  Neo4j and MongoDB were never affected -- neither backend merges -- which is now measured rather than
  assumed (`TagSetSeedEmbeddingCostAllBackendsTest`).

  **Net: seeding 12 tags went from 36 embedding calls to 12.**

  Deliberately *not* fixed by caching. The empty-string case is not computed at all rather than memoized,
  which also removes the summary-vector distortion that memoizing would have preserved permanently; and
  reuse across instances is decided from cache state already being maintained, never by hashing field
  content, which would not survive JavAI eventually vectorizing something larger than a slug.

- **`javai-persistence` (all three backends): a `@Vectorize` field that loses its content no longer leaves a
  stale vector behind.** Vectors are stored per field and written by upsert; nothing ever deleted one except
  deleting the whole entity. That was unreachable while every field always produced *some* vector, and became
  reachable the moment `EmbeddingVector.absent()` existed -- skipping the write left the previous save's row
  in place, so an ANN search kept returning the entity as a similarity match for content it no longer had.
  A wrong answer rather than a slow one, and invisible from the in-memory object, which stayed correct
  throughout. Postgres deletes the row, Neo4j removes the property, MongoDB `$unset`s the field -- each
  scoped to the current model, so another model's vectors are untouched.

  Reaching it also required the last of the `embed("")` sources: a null or blank `@Vectorize` field rendered
  as `""` and was embedded (as a space, after the providers' substitution) rather than being absent. It is
  now absent, which both removes the call and makes "this field has no content" representable at rest.

### Added

- **`javai-persistence`: `JavAIPersistenceConfig.Builder.entityType(Class)` / `.entityTypes(Collection)`.**
  An escape hatch for types JavAI's discovery cannot see. Registering `@Any` targets fixes the reported bug;
  this fixes the class it belongs to -- discovery finds related types through declared field types, which is
  complete for ordinary associations and silently blind to anything reached another way, and there was
  previously no way at all to say "also register this type". Honoured by all three backends; registration is
  recursive and idempotent, exactly as for a discovered type.

- **`javai-vector`: `EmbeddingVector.absent()`** -- the vector of *nothing*, for an object or collection with
  no embeddable content. Zero dimensions ("all dimensions and none"), so arithmetic skips it, similarity
  ranks it last, and persistence writes no row for it. A value rather than a null, so nothing has to
  null-check a return.
- **`javai-vector`: `JavAIEmbeddingProvider.modelId()`** -- which model a provider embeds with, without
  performing an embedding to find out. Needed to decide whether an already-stored vector is still valid.
  A `default` returning `null`, deliberately: adding an abstract method to a published SPI would break every
  third-party implementation, and a provider that does not override it simply recomputes as before. All five
  bundled providers override it.
- **`javai-model`: `JavAIRuntime.hydrateFieldVector` / `transferComputedVectors` / `currentModelId`** -- the
  entry points `javai-persistence` uses to serve stored or already-computed vectors instead of recomputing.
- **`javai-vector`: `JavAIEmbeddingProvider.embedAll(List<String>)`**, and **`javai-model`:
  `JavAIRuntime.precomputeVectors(Collection<?>)`** -- OMI-187's bulk-seed path.

  This addresses a different problem from the waste above, and a larger one. Eliminating redundant calls
  cannot fix latency that is inherent to *how* the necessary calls are issued: lazy computation discovers one
  text at a time, deep inside a read, so seeding 1,400 tags is 1,400 sequential HTTP round trips even when
  every single one is warranted -- roughly 14s at the ~10ms per embed this ticket measured. Every real
  embedding API accepts an array; nothing could use that, because nothing gathered the texts first.

  `precomputeVectors` gathers across a collection of objects, de-duplicates (a value shared by several
  objects is embedded once and seeded into every slot holding it), skips fields whose slot is already clean
  (so it is safely re-runnable), and issues chunked `embedAll` calls. Recommended seeding shape:

  ```java
  JavAIRuntime.precomputeVectors(allTags);           // a few batched round trips
  for (Tag tag : allTags) tagRepository.save(tag);   // finds every vector already on file
  ```

  `embedAll`'s `default` loops over `embed`, so an existing provider stays correct and simply gains nothing;
  overriding it is a pure latency optimization with no semantic difference, which is why callers can use it
  unconditionally. `EmbeddingProviderOllama` now issues a genuine batched request (its `/api/embed` already
  accepted an array -- only this client was sending scalars); the other bundled providers still use the
  looping default.
- **`javai-vector` test-jar (`dev.xtrafe.javai.vector.testsupport`)** -- one shared `FakeEmbeddingProvider`
  replacing six byte-identical per-module copies, plus `RecordingEmbeddingProvider`/`EmbeddingLedger`, the
  instrument OMI-187 was diagnosed with. The ledger asserts a three-part invariant (REDUNDANT / PHANTOM /
  OMITTED) rather than a bare call count; the OMITTED class exists so a "fix" cannot pass by embedding less
  than correctness requires. Consumable from every module and from `e2e-client-test`.

### Changed

- **`doc/ai-guidance/persistence-support-matrix.md`: added an `@Any` row.** It was previously in the worst
  category for a consumer -- not documented as unsupported, and *nearly* working, so it read as a usage
  error rather than a library gap. The row also records two things that are inherent to the mapping rather
  than to JavAI: `@Any` cannot carry a foreign key (its id column points into several tables, so referential
  integrity is traded for the polymorphism), and its discriminator values are strings in your data, so
  renaming a target class is a data migration. The portability note now also points out that `KnowledgeGraph`
  and `@Any` pull in opposite directions -- Neo4j-only and Postgres-only respectively -- so an entity
  declaring both cannot be persisted on any single backend.

- **`javai-vector`: `VectorMath.centroid(List.of())` returns `EmbeddingVector.absent()` instead of throwing
  `IllegalStateException`.** That throw was the reason callers fabricated a vector for the empty case in the
  first place. Absent members are skipped rather than averaged in.
- **`javai-vector`: `VectorMath.cosineSimilarity` returns `Double.NEGATIVE_INFINITY`** when either side is
  absent, rather than throwing on the dimension mismatch. Matches how `CollectionVectorSupport.similarityOf`
  already treats a non-vectorizable element, so ranking code needs no special case.
- **`javai-vector`: a JavAI collection's centroid tracks its own staleness** (`DirtyTrackingSupport`'s new
  `centroidDirty`). It previously shared `SummaryDirty` with the collection's summary vector, and only
  `summaryVector()` cleared it -- so a collection read through `vector()` alone recomputed its centroid on
  every read, forever. One flag cannot serve two readers; whichever clears it starves the other.
- **`SPEC.md`: the mutation rule is now stated explicitly** ("only JavAI may change a `@Vectorize` field"),
  including why it is deliberate and how persistence makes breaking it durable rather than process-local.
- **A null or blank `@Vectorize` field now yields no vector rather than the embedding of a space.** Its
  `fieldVector()` is `EmbeddingVector.absent()`, it costs no provider call, and it contributes nothing to any
  `summaryVector()`. Previously such a field produced a real vector of meaningless content.

## [0.1.6] - 2026-07-23

### Fixed

- **`javai-persistence` (Postgres): saving a `@JavAIVectorizable` entity with a *lazy* singular association
  to another `@JavAIVectorizable` entity no longer fails (OMI-161).** It died with
  `null value in column "owner_id" of relation "javai_vectors__<model>" violates not-null constraint`.

  **Mechanism:** `save()` merges the caller's detached instance, and Hibernate's merged copy holds an
  *uninitialized proxy* for a `FetchType.LAZY` singular association rather than loading the target. That
  proxy subclasses the real entity, so it satisfied `instanceof JavAIVectorizable` and the related-entity
  walk tried to write vectors for it -- but a proxy holds no field state, so reading its `@Id` reflectively
  produced `null`. `getClass()` was wrong for the same reason (`Target$HibernateProxy$xyz`), so even a
  correct id would have been filed under an `owner_type` no later lookup could match.

  **Who was affected:** any Postgres entity with `@ManyToOne(fetch = LAZY)` or `@OneToOne(fetch = LAZY)`
  pointing at another `@JavAIVectorizable`. Eager associations were never affected, which is why this went
  unnoticed for so long -- both `@ManyToOne` and `@OneToOne` default to `EAGER`, and every singular
  association in this project's own fixtures (and in `javai-tagging`'s shipped `Tag -> TagSet`) is eager, so
  there was no lazy singular association to a vectorizable anywhere in the suite. Neo4j and MongoDB are
  unaffected: neither has Hibernate proxies.

  **Behavior now:** an *uninitialized* proxy is skipped -- an untouched association means nothing changed,
  and its vectors were already written when it was saved through its own repository; initializing it would
  cost a SELECT, and potentially a real embedding call, per association per save. An *initialized* proxy is
  unwrapped and written under its real entity class, so touching a related entity
  (`owner.getTarget().setLabel(...)`) still updates its vector rather than silently going stale.

### Added

- **`javai-persistence`: `JavAIPI.sessionFactory(JavAIPersistenceConfig)` (OMI-160).** Returns the Hibernate
  `SessionFactory` this module built for that config -- the same instance every repository sharing it runs
  on. Postgres only; the other backends throw rather than return null.

  **Why:** a `JavAIRepository` call joins a Spring `@Transactional` unit of work only when Spring's
  transaction manager and JavAI hold the *same* `SessionFactory` instance (the match is by identity). Until
  now the only way to arrange that was `JavAIPersistenceConfig.Builder.sessionFactory(...)`, i.e. Spring
  builds the factory and hands it over -- which skips the mapping-time hooks JavAI can only apply to a
  factory it builds itself, so an interface-typed `@OneToMany JavAIList<T>` maps as a plain Hibernate bag.
  Applications had to choose between working JavAI collections and working transactions. They no longer do:
  let JavAI own the factory, ask for it, and wire a transaction manager onto it.

  ```java
  @Bean SessionFactory javAiSessionFactory(JavAIPersistenceConfig config) {
      return JavAIPI.sessionFactory(config);
  }
  @Bean PlatformTransactionManager transactionManager(SessionFactory factory) {
      return new JpaTransactionManager(factory);
  }
  ```

  Two things to know, both now covered by tests rather than only prose. Use **`JpaTransactionManager`**, not
  `HibernateTransactionManager`: the latter's `(SessionFactory)` constructor eagerly unwraps a
  `javax.sql.DataSource` to share connections with plain JDBC, and a JavAI-built factory configures
  Hibernate's own connection provider from raw `jakarta.persistence.jdbc.*` settings, so it throws
  `UnknownUnwrapTypeException`. And asking for the factory **builds** it, which freezes the entity set --
  call `JavAIPI.repository(...)` for every repository the application needs before this bean is created,
  the same registration-before-use rule that already applies to invoking a repository method.

### Documentation

- **`doc/ai-guidance/JavAI_Usage_Guide.md`** now covers both ownership directions for the `SessionFactory`
  and which to prefer, rather than presenting `Builder.sessionFactory(...)` as the only route to Spring
  transactions.
- **`doc/ai-guidance/persistence-support-matrix.md`** gains "Two traps that look like JavAI bugs and
  aren't": a convenience getter colliding with a nested derived-finder path (Spring Data's `PartTree`
  discovers properties from getters, so `getIdentityId()` makes `findByIdentityId` bind as one segment
  instead of `identity.id`), and `@Summary` on a `FetchType.LAZY` association only summarizing inside a
  session. Both are documented rather than changed -- the second deliberately, since silently dropping an
  unloadable `@Summary` child would make `summaryVector()` depend on session state.

## [0.1.5] - 2026-07-23

### Changed -- BREAKING

- **`javai-persistence` (Postgres only): the default physical naming strategy is now
  `CamelCaseToUnderscoresNamingStrategy`.** A camel-cased field maps to a snake_cased column --
  `emailVerified` becomes `email_verified`, where releases up to 0.1.4 produced `emailverified`. Table names
  follow the same rule, so an entity class `TestCrew` now maps to `test_crew`, and any join table derived
  from it moves with it. This matches Spring Boot's default and ordinary SQL convention.

  **Why:** pointing a `JavAIRepository` at a table some other tool already created under the conventional
  naming produced a table carrying *both* conventions at once — `hbm2ddl=update` added JavAI's
  `emailverified` alongside the existing `email_verified` rather than recognizing it, and the subsequent
  insert populated JavAI's column while leaving the original `NOT NULL` one null. Adopting JavAI for an
  entity with an existing table therefore looked like a silent column rename against live data.

  **Who is affected:** any deployment whose Postgres schema was created by JavAI at 0.1.4 or earlier *and*
  contains an entity with a multi-word field or class name. A single-word schema (`title`, `body`, `article`)
  is unaffected — the two strategies agree there.

  **What to do**, whichever fits:
  - *Keep the existing schema as-is:* pin the old behavior explicitly.
    ```java
    JavAIPersistenceConfig.builder()
        .backend(POSTGRES)
        .physicalNamingStrategy(new PhysicalNamingStrategyStandardImpl())   // pre-0.1.5 naming
        // ...
        .build();
    ```
  - *Adopt the new naming:* rename the affected columns/tables (`ALTER TABLE ... RENAME COLUMN
    emailverified TO email_verified`) before deploying. `hbm2ddl=update` will otherwise add the new columns
    beside the old ones and leave the old data stranded — it never renames, and never drops.

  Neo4j and MongoDB are unaffected: both classify fields by declared type and have no equivalent of JPA
  column naming.

### Added

- **`javai-persistence` (Postgres): repository calls now join a caller's transaction (OMI-146).** Previously
  every call opened its own Hibernate `Session` and committed independently, so several calls could never
  form one atomic unit of work — a Spring `@Transactional` service method composing four repositories got
  four transactions, and a failure on the last left the first three permanently committed.

  A call now runs on the caller's session when one is active, and opens its own only when there isn't:
  - **Spring `@Transactional`**, with no JavAI-specific API at the call site. Requires that JavAI share the
    application's own factory (`Builder.sessionFactory(emf.unwrap(SessionFactory.class))`), since that is
    what there is to join. Works under `JpaTransactionManager` and `HibernateTransactionManager`, at class
    and method level, and honors the annotation's attributes — `isolation`, `readOnly`,
    `rollbackFor`/`noRollbackFor`, and the propagation modes, including correctly *not* joining under
    `REQUIRES_NEW`/`NOT_SUPPORTED`, where the caller has deliberately stepped outside the transaction.
  - **`JavAIPI.inTransaction(config, body)`** (new), for callers not running under Spring: every repository
    call in the body shares one session and commits, or rolls back, once. Nesting joins the outer body
    (`PROPAGATION_REQUIRED` semantics) rather than opening a second transaction. Thread-bound.

  Vector rows are written on the caller's own connection before their commit, so they now roll back with the
  caller's transaction rather than being committed separately.

  Behavior is unchanged when there is no ambient transaction, which is every pre-0.1.5 usage. Neo4j and
  MongoDB are unaffected: each call remains its own unit of work, and `inTransaction` throws there rather
  than implying an atomicity it doesn't provide. Two documented edges: a write inside `readOnly = true` fails
  loudly (Postgres rejects the INSERT), and `PROPAGATION_NESTED` is unsupported under `JpaTransactionManager`
  because Spring's Hibernate JPA dialect exposes no savepoint manager — Spring refuses it before JavAI is
  involved.

  Adds `org.springframework:spring-orm` as an **optional** dependency: it is what holds the resource holders
  a Spring transaction binds. Optional dependencies are not transitive, and the bridge class is only loaded
  after a runtime presence check, so a non-Spring consumer's classpath is unaffected.
- **`javai-persistence`: `JavAIPersistenceConfig.Builder.physicalNamingStrategy(PhysicalNamingStrategy)`** —
  overrides the naming strategy applied to the `SessionFactory` this module builds, including pinning the
  pre-0.1.5 behavior above.
- **`javai-persistence`: `JavAIPersistenceConfig.Builder.hibernateProperty(String, Object)` and
  `.hibernateProperties(Map)`** — a general passthrough for any Hibernate setting this builder exposes no
  typed method for. Applied *after* the settings this module sets itself (`jakarta.persistence.jdbc.*`,
  `hibernate.hbm2ddl.auto`), so an explicitly-named key wins over JavAI's own default. Inert when
  `Builder.sessionFactory(...)` supplies a factory this module didn't build.

  Together these close a real gap: before, a consumer who needed correct column naming had to supply their
  own `SessionFactory`, which skips the two mapping-time hooks (`attachJavAICollectionTypes`,
  `buildAutoTransientOverrideXml`) that JavAI collection fields depend on — so correct naming and collection
  support were mutually exclusive.

[Unreleased]: https://github.com/dcaudell/javai/compare/v0.1.6...HEAD
[0.1.6]: https://github.com/dcaudell/javai/compare/v0.1.5...v0.1.6
[0.1.5]: https://github.com/dcaudell/javai/compare/v0.1.4...v0.1.5
