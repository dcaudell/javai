# javai-persistence

Extension area: **Persistence Bridge**. Whitepaper: §4.4, §4.5.4, §5.2, §6.6. Full detail:
[`doc/spec/persistence-bridge.md`](../doc/spec/persistence-bridge.md).

Depends on `javai-collections`, `javai-vector`, and `javai-model` (not `javai-substrate` -- see "What's
actually implemented" below for why that matters). Takes what Vector Core computes in memory and makes it
durable and queryable in a real store, without asking the developer to hand-manage a parallel vector index
alongside their ORM.

## Primitives

| Element | Kind | Purpose |
|---|---|---|
| `JavAIPI` | Static utility | `repository(Class, JavAIPersistenceConfig)` realizes a `JavAIRepository<T>` subinterface as a dynamic `Proxy`, bound permanently to the config passed in -- no ambient "current config" to configure separately; see "No ambient configuration" below |
| `JavAIRepository<T>` | Interface | Base CRUD (`save`/`saveAll`/`findById`/`findAll`/`deleteById`) plus `reindexAll()` (whole datastore) and `reindex()` (this type only), fixed to `UUID` identity |
| `saveAll(Iterable<T>)` | Bulk write | Saves a batch with **one** embedding round trip instead of one per entity (OMI-266); one transaction on Postgres, per-entity writes on Neo4j/Mongo -- see "Batching" below |
| `findNearestBy<Field>Vector` / `findNearestByVector` / `findNearestBySummaryVector` | Vector derived query convention | Repository-level nearest-neighbor search -- validated at repository-creation time, not on first call. Optionally narrowed (`…VectorAnd<Predicate>`), ranked (`List<Ranked<T>>`) and paged (trailing `Pageable`/`Limit`) since OMI-230 |
| `NearestQuery<T>` | Builder, from `nearest()`/`nearestBy(field)`/`nearestBySummary()` | The same vector search composed at runtime instead of declared as a method name -- `where(…)`, `offset`/`limit`, `results()`/`ranked()` |
| `Ranked<T>` | Result record | A hit plus the cosine similarity it was ranked on, normalized to `[-1, 1]` on every backend |
| `findBy…` / `existsBy…` / `countBy…` / `deleteBy…` | Ordinary relational derived finders | Full Spring-Data-style finders (parsed via `PartTree`) resolved against the entity's own mapped columns, so one repository serves both an entity's relational access and its vector search; also validated at creation time -- see "Ordinary relational derived finders" below |
| `JavAIPersistenceConfig` | Value object | Backend selection + connection settings; `fromSystemProperties()` is a pure factory for the old self-contained-default convenience, but it's never auto-applied -- a caller invokes it explicitly and passes the result to `repository(...)` like any other config |
| `javai_vectors__<model>` / `javai_summary_vectors__<model>` | Postgres tables, owned by this module, one pair per model | Per-field + combined vectors, and `summaryVector()`, respectively -- never the developer's own entity table |
| `<field>Vector__<model>` / `vector__<model>` / `summaryVector__<model>` | Neo4j node properties, one set per model | Same idea as the Postgres tables, applied to a schemaless node instead |
| `<field>Vector__<model>` / `vector__<model>` / `summaryVector__<model>` | MongoDB document fields, one set per model | Same idea again -- one collection per entity type, per-model-qualified field names within each document, not a physical collection per model |
| `ModelIds.sanitize(modelId)` | Shared helper | Turns an arbitrary model id string into the identifier fragment all three backends key their per-model tables/properties/fields/indexes on |

## Model versioning: the methodology in full

**The central design point of this module: every model gets its own table (Postgres) or its own
model-qualified property (Neo4j) -- vectors from different models are never stored under the same name,
structurally, not just by convention.** This replaced an earlier single-shared-table/property design after
hitting two real problems empirically: pgvector's HNSW index requires a *fixed*-dimension column, which a
column shared across models with different output widths can't have; and even for two models that happen to
share a dimension, comparing their vectors against each other is semantically meaningless, so physical
separation is the right model regardless of dimension.

**Swapping the embedding provider/model -- same-dimension or different-dimension, identical steps either
way:**

1. `JavAIRuntime.configureEmbeddingProvider(newProvider)`. That's the entire configuration change. Nothing
   else differs depending on whether the new model's output dimension matches the old one -- each model
   gets its own, independently and correctly dimensioned table/property/index the first time it's actually
   needed, so there is no shared-column/shared-property conflict to hit either way. This is verified
   directly: `differentDimensionModelSwapWorksTheSameAsASameDimensionSwap` in both backends' test suites
   exercises exactly this and asserts it behaves like any other swap, not a special case.
2. `repository.reindexAll()` -- the explicit trigger for "now go re-embed everything that already exists."
   It re-saves every entity of **every registered entity type**, not just the type whose repository you
   called it through: re-indexing one type in isolation would leave the store straddling two models (an
   `Article` on the new one while its `Comment`s are still on the old). Since `save()` always writes under
   whichever provider is *currently* configured, one call populates the new model's tables/properties for
   the whole datastore. The Postgres backend then **validates**: it snapshots the `(owner_type, owner_id)`
   manifest from the previously-newest vector table (located by `max(computed_at)`), re-reads after the pass,
   and throws -- naming the offenders -- if anything was left behind. A split-model store is a loud failure,
   not a silent one. (`repository.reindex()` is the narrow counterpart -- this repository's type only, no
   validation -- for when a deliberately partial re-embed is what you want.)
3. `findNearestBy*` immediately starts returning results ranked against the new model, because it resolves
   which table/property/index to query from the *reference vector's own* `modelId()` -- which is whatever
   the currently-configured provider just produced.

**Reverting needs no reindexing at all**, and this is the property the whole design is built around, not
an afterthought: call `configureEmbeddingProvider(oldProvider)` again, and stop. Nothing was ever deleted or
overwritten when the newer model was introduced -- its table/properties are separate and were left
untouched by every save made under the newer model -- so `findNearestBy*` immediately resolves back to the
old model's still-fully-populated table/properties the moment a reference vector tagged with that model
shows up again. `revertingToAPreviousProviderNeedsNoReindexing` in both test suites proves this directly:
reindex forward to a temporary model, revert, and confirm the *original* entity is still found under the
*original* reference vector -- with no second `reindexAll()` call in between.

One honest limitation this doesn't solve: if a *single* entity is mutated and re-saved (not via
`reindexAll()`, just an ordinary update) while an older model's table/properties for that same entity still
exist from before, only the *currently configured* model's copy gets refreshed -- the older model's copy is
left as a stale snapshot of that entity's prior state. This is expected, not a bug: older-model data exists
purely as a revert safety net, not a live, kept-in-sync secondary index; there's no reason to want it
perfectly current once the application has moved on to a new model, and always writing to every
model-ever-used on every save would be unbounded write amplification for no real benefit.

## Reference implementation shape

```java
interface ArticleRepository extends JavAIRepository<Article> {
    List<Article> findNearestByBodyVector(EmbeddingVector reference, int limit);
}

JavAIPersistenceConfig config = JavAIPersistenceConfig.builder()
        .backend(JavAIPersistenceConfig.Backend.POSTGRES)
        .postgresUrl(...).postgresUsername(...).postgresPassword(...)
        .build();
ArticleRepository repo = JavAIPI.repository(ArticleRepository.class, config);

repo.save(article);   // auto-vectorized on write -- every @Vectorize/@Summary vector recomputed and persisted

List<Article> hits = repo.findNearestByBodyVector(queryVector, 20);

// Model swap: same two steps regardless of whether the new model's output dimension differs --
JavAIRuntime.configureEmbeddingProvider(newProvider);
repo.reindexAll();

// Revert: one step, no reindexing --
JavAIRuntime.configureEmbeddingProvider(oldProvider);

// Swapping the persisted *backend* (not the model) is also configuration, not code -- just a different
// JavAIPersistenceConfig.Backend value passed to repository(...); JavAIPersistenceConfig.fromSystemProperties()
// reads javai.persistence.backend=postgres|neo4j|mongodb (plus the matching connection properties) if you
// want that convenience instead of building the config by hand.
```

**No ambient configuration.** `JavAIPI.repository(Class, JavAIPersistenceConfig)` takes its config
explicitly, every call -- there is no `configurePersistence(...)`-style static setter and no "current
config" pointer to race or accidentally leave pointed at the wrong backend for an unrelated later call.
Reuse the *same* `JavAIPersistenceConfig` instance across every `repository(...)` call meant to share one
backend connection/pool: `JavAIPersistenceConfig` has no `equals()`/`hashCode()` override, so the internal
per-config backend cache is keyed by reference identity -- a fresh `.builder()...build()` call with
identical field values on every invocation would silently multiply backends instead of sharing one.

`<Field>` in `findNearestBy<Field>Vector` names the same accessor the weaver already synthesizes in memory
(`bodyVector()` -> `findNearestByBodyVector`), not the bare field name -- deliberately the same name a
developer would already call directly on a woven object. `findNearestByVector`/`findNearestBySummaryVector`
are the whole-object variants, for the object's own combined `vector()`/`summaryVector()`.

**A vector search can be narrowed, ranked and paged (OMI-230).** `(reference, limit)` and nothing else made
*"the nearest N that **also** satisfy X"* inexpressible, leaving over-fetch-and-discard as the only recourse
-- unbounded, since how much to over-fetch depends entirely on the data, and blind, since the ranking
information that would have said whether to fetch more was discarded with the results. Two idioms now express
it, and they compile to the same query (`NearestSpec`) so neither can answer differently from the other:

```java
// the method-name convention, validated at repository-creation time
List<MediaNote> findNearestByCaptionVectorAndKindIs(EmbeddingVector reference, int limit, Kind kind);
List<Ranked<MediaNote>> findNearestByCaptionVectorAndKindIs(EmbeddingVector r, Kind kind, Limit limit);
List<MediaNote> findNearestByCaptionVector(EmbeddingVector reference, Pageable pageable);

// ...or the builder, for a predicate composed at runtime
notes.nearestBy("caption").to(reference)
     .where("kind").in(Kind.IMAGE, Kind.SHORT).and("published").isTrue()
     .offset(20).limit(20).ranked();
```

Everything after `Vector` is parsed by the same Spring Data `PartTree` the ordinary `findBy…` finders use, so
the whole relational vocabulary -- operators, `And`/`Or`, nested paths, `IgnoreCase` -- is available with no
second grammar, and is translated by the same backend code, so semantics are identical by construction. The
one genuine ambiguity is that `Vector` can appear inside a field name *or* a predicate property; the parser
scans right to left and requires the tail to begin with `And`, which resolves both directions
(`findNearestBySubVectorVector`, `findNearestByCaptionVectorAndVectorNameContaining`).

**The limit applies after the predicate**, which is the whole contract: N matches means N results, not
"however many of the nearest N happened to match". Postgres resolves the predicate to an id set and ranks
within it; MongoDB hands that id set to `$vectorSearch`'s own `filter`, a genuine pre-filter. **Neo4j refuses
to narrow**, at repository-creation time -- `db.index.vector.queryNodes` picks its K nearest before Cypher can
filter, so narrowing there could only ever return fewer than the requested limit and quietly answer a
different question. Ranked results and paging work on all three.

`Ranked.similarity()` is plain cosine in `[-1, 1]` everywhere -- the same number `similarityTo` gives in
process -- which is a conversion, not a passthrough: pgvector reports cosine *distance*, Neo4j and MongoDB
both report `(1 + cosine) / 2`. Each backend converts as it reads its own result.

**Let the config name its entity types, and registration ordering stops mattering (OMI-214).**
`JavAIPI.repository(...)` accumulates entity types under the hood; the Postgres backend's internal
`SessionFactory` is built, once, lazily, on the *first* actual method call across any repository (or on
`JavAIPI.sessionFactory(config)`, which builds it too) -- and Hibernate's boot-time metadata is immutable
afterward. Rather than choreograph startup around that, declare the entities on the configuration:

```java
JavAIPersistenceConfig.builder()
    .backend(Backend.POSTGRES)./* ... */
    .entityPackages("com.example.domain")   // every @Entity under here, scanned up front
    .entityType(SomethingElsewhere.class)   // for a type outside those packages
    .build();
```

The entity set is then complete before anything can be built, so repositories may be realized in any order,
at any time -- including lazily, long after startup. A Spring application needs no `@DependsOn` on its
factory bean.

Registration is also **recursive**: `JavAIPI.repository(ArticleRepository.class)` alone is enough even if
`Article` references `Comment`/`Attachment` (singly or through a collection), since related types reachable
through an already-registered type's own fields are discovered automatically. And a late `repository(...)`
call is **harmless whenever it introduces nothing new** -- re-realizing one, or asking for a type something
else already pulled in, is a no-op. It fails only for a genuinely unknown type, and the error then names the
call that *built the factory*, since that is the thing to move.

**Excluding a type from a scan.** For an `@Entity` inside a scanned package that this configuration should
not own: `excludeEntityType(Foo.class)`, `excludeEntityPackages("com.example.reporting.*")`, or
`@PersistenceIgnore` on the class. The first two are per-configuration ("not *this* config's" -- the right
tool for a type that is merely backend-specific, such as a `KnowledgeGraph` owner that belongs to Neo4j);
the annotation is global ("never JavAI's"). **Being non-vectorized is never a reason to exclude anything** --
a plain `@Entity` is a first-class citizen of a `JavAIRepository`, registered and served exactly like a
vectorized one, it simply has no vectors.

## Transactions: joining a caller's unit of work (Postgres)

Up to 0.1.4 every repository call opened its own session and committed on its own, so several calls could
never be one atomic unit -- a `@Transactional` service method composing four repositories got four
transactions, and a failure on the last left the first three permanently committed. Since 0.1.5 (OMI-146) a
call **joins a transaction that already exists** and only opens its own when there is none:

| Situation | What a repository call does |
|---|---|
| Inside a Spring `@Transactional` method, JavAI configured with that application's own `SessionFactory` | Joins it. Commits/rolls back with the caller, never separately. |
| Inside `JavAIPI.inTransaction(config, ...)` | Joins that body's session; the whole body commits once. |
| Anywhere else | Opens a session, commits, closes -- exactly the pre-0.1.5 behavior. |

**Spring consumers need no JavAI-specific API at all**, provided JavAI shares the application's factory:

```java
JavAIPersistenceConfig config = JavAIPersistenceConfig.builder()
        .backend(JavAIPersistenceConfig.Backend.POSTGRES)
        .sessionFactory(entityManagerFactory.unwrap(SessionFactory.class))   // the app's own factory
        .postgresUrl(...).postgresUsername(...).postgresPassword(...)
        .build();

@Transactional                       // ordinary Spring; nothing below knows about JavAI
public void recordEntry(...) {
    channels.save(channel);          // all three
    participants.save(participant);  //   share one
    entries.save(entry);             //     transaction
}
```

This works for `JpaTransactionManager` and `HibernateTransactionManager` alike, for class-level and
method-level `@Transactional`, and it honors the annotation's attributes because JavAI is writing through the
caller's own session and connection -- `isolation` reaches JavAI's writes, `readOnly` makes them fail loudly
(Postgres rejects the INSERT), `rollbackFor`/`noRollbackFor` decide JavAI's rows along with everything else,
and `REQUIRES_NEW`/`NOT_SUPPORTED` correctly *don't* get joined, since the caller has stepped outside the
transaction. `SpringTransactionalIntegrationTest` asserts each of these against a real Postgres.

Two things this does **not** do. Vector rows are written before the caller's commit, on the caller's own
connection, so they roll back with it -- but a *read-only* transaction therefore can't write them either.
And `PROPAGATION_NESTED` is unavailable under `JpaTransactionManager`: Spring's Hibernate JPA dialect exposes
no savepoint manager, so Spring refuses the call before JavAI is involved (it works under
`HibernateTransactionManager`, and both cases are pinned by tests).

**Non-Spring callers** get the same atomicity explicitly:

```java
JavAIPI.inTransaction(config, () -> {
    Channel channel = channels.save(findOrCreateChannel(platform));
    entries.save(new ConversationEntry(channel, text));
});   // one commit; any exception rolls back both
```

Nesting joins rather than nests (Spring's `PROPAGATION_REQUIRED` semantics), so two methods that each wrap
their own work this way stay composable. It is thread-bound, like Spring's own transaction management: work
handed to another thread inside the body is not part of the transaction. Postgres only -- Neo4j and MongoDB
throw a message saying so rather than pretending to be atomic.

**Optimistic locking (`@Version`) works on Postgres, and `save()` returns an entity you can save again**
(OMI-254). Detection was never the problem: two concurrent writers to one entity have always produced one
winner and one `OptimisticLockException`. What made the annotation unusable was the ordinary case.
`merge()` performs the write on Hibernate's own managed copy and it is *that* copy whose version gets
incremented, while `save()` deliberately returns the caller's instance (its `@Transient` JavAI collection
fields are empty on the managed copy, so returning that would hand back an entity whose collections looked
wrong). The returned object therefore still carried the pre-write version, and saving it a second time --
no concurrency, no second thread, no second transaction -- collided with the row the first save had just
written. Any code that saves a graph, mutates it and saves it again was locked out of optimistic locking
entirely, which is exactly the read-modify-write shape a detached-entity repository encourages.

`save()` now carries the post-write version back onto the caller's instance, for the whole graph rather than
the root alone (a cascaded child is written in the same flush and has its own version bumped). The invariant:
**the object `save()` hands back is safe to mutate and save again.** Detection is untouched -- the version a
concurrent writer collides on is the one `merge()` read off the detached instance beforehand -- and
`OptimisticLockingTest` asserts both halves together, since fixing the first by weakening the second would be
a worse defect than the one being fixed. Entities with no `@Version` anywhere pay nothing for this: the
graph walk is skipped unless something registered with the backend actually declares one. **Neo4j and MongoDB
have no optimistic locking**; `@Version` is persisted there as an ordinary scalar and never checked, so it
looks like protection and provides none -- raise the isolation level instead.

## Physical naming, and configuring the `SessionFactory` JavAI builds (Postgres)

**Columns and tables are snake_cased by default** (OMI-145, 0.1.5): a field `emailVerified` maps to
`email_verified` and an entity `TestCrew` to `test_crew`, matching Spring Boot's own default and ordinary SQL
convention. Up to 0.1.4 this backend set no naming strategy at all, so Hibernate's bare default produced
`emailverified` -- which broke the case that matters most in practice: pointed at a table another tool had
already created conventionally, `hbm2ddl=update` **added a second, differently-cased set of columns** beside
the existing ones, and the following insert populated JavAI's copy while leaving the original `NOT NULL`
column null. See `CHANGELOG.md` for the migration note; this is a breaking change for any pre-0.1.5 schema
with a multi-word field or class name.

Two knobs, both on `JavAIPersistenceConfig.Builder`:

```java
JavAIPersistenceConfig config = JavAIPersistenceConfig.builder()
        .backend(JavAIPersistenceConfig.Backend.POSTGRES)
        .postgresUrl(...).postgresUsername(...).postgresPassword(...)
        // 1. Typed override -- e.g. pin the pre-0.1.5 naming instead of migrating an existing schema:
        .physicalNamingStrategy(new PhysicalNamingStrategyStandardImpl())
        // 2. General passthrough -- any Hibernate setting with no typed method of its own:
        .hibernateProperty("hibernate.jdbc.batch_size", 50)
        .build();
```

The passthrough is applied **after** the settings this module sets itself (`jakarta.persistence.jdbc.url`/
`.user`/`.password`, `hibernate.hbm2ddl.auto`), so an explicitly-named key beats JavAI's own default --
deliberate, since naming a setting outright is the more specific instruction. Between the two knobs,
`physicalNamingStrategy(...)` wins over a `hibernate.physical_naming_strategy` passed as a raw property.

Both are inert when `Builder.sessionFactory(...)` supplies a factory JavAI didn't build, and inert on Neo4j
and MongoDB, which classify fields by declared type and have no equivalent of JPA column naming. Note that
supplying your own `SessionFactory` still skips the two mapping-time hooks (`attachJavAICollectionTypes`,
`buildAutoTransientOverrideXml`) that JavAI collection fields depend on -- these knobs exist so that needing
particular Hibernate settings no longer forces that trade-off.

## Collections: `JavAIList`/`JavAISet`/`JavAIMap` fields

Hibernate always substitutes its own `PersistentBag`/`PersistentSet`/`PersistentMap` into a mapped collection
field the instant it is persisted. A field typed as one of `javai-model`'s **concrete** collections therefore
can never be a native association -- confirmed empirically, as a `ClassCastException` the moment that wrapper
can't be assigned back to a field statically typed as the concrete JavAI class.

**Declaring the field by the JavAI *interface* is what resolves it (OMI-142).** The substitution then has
somewhere legal to land, and `UserCollectionType` decides *what* lands: this backend binds
`JavAIListType`/`JavAISetType`/`JavAIMapType` per collection between `buildMetadata()` and
`buildSessionFactory()`, so Hibernate installs a `PersistentJavAIList`/`Set`/`Map` -- a real
`PersistentCollection` that is also a real JavAI collection. Vectors and dirty-tracking survive; the consumer
writes only the JPA annotation they would have written anyway. The field must be **non-final**, for the same
reason: a final field cannot be substituted into.

```java
@OneToMany(cascade = CascadeType.ALL)
private JavAIList<Comment> comments = new JavAIArrayList<>();   // interface-typed, non-final, annotated
```

**The concrete-typed field is refused at registration (OMI-277).** It used to be accepted and round-tripped
through `javai_collection_members`, a table this backend owned, hydrated reflectively into whatever instance
the entity's own constructor had created. That storage was only ever read and written for the entity a
repository call *returned*: reached through an association the collection came back silently empty, and saved
through one its members were silently never written. It could not be made lazy where it stood either -- a
`final` instance of a `final` class -- so it was withdrawn rather than repaired, and the table went with it.
`validateCollectionFieldMapping` now throws a message naming the interface to use.

**No manual `@Transient` needed** on a field this backend maps itself. It generates an in-memory JPA
`orm.xml`-equivalent mapping document marking exactly those fields `<transient>`, fed to Hibernate via
`MetadataSources.addInputStream` alongside the ordinary annotation scanning -- a real, spec-defined JPA
override mechanism (XML mappings logically override annotations for whatever they explicitly mention), not a
hack, confirmed with a real container before being relied on. Since OMI-277 that means **`Point` fields
only**; JavAI collections were the other user of it, and hiding a native association from Hibernate is the
last thing wanted.

**Map keys are Hibernate's business on Postgres.** A `Map` field not keyed by `String` used to be refused
here, because the membership table's key column was a plain `varchar` and a stringified key could never
round-trip back to its original type. With that table gone a map is an ordinary JPA association, keyed
however `@MapKeyColumn`/`@MapKeyEnumerated` say, and the validator went with the storage it protected.
`NonStringMapKeyConformanceTest` (OMI-279) pins the result against the harm the old rule named: `Integer`,
`UUID` and enum keys come back as their own types, from `integer`/`uuid`/`varchar` columns, with
`PersistentJavAIMap` still substituted in. Neo4j and MongoDB still enforce the rule -- see below, where the
reason still holds.

**Neo4j doesn't need any of this ceremony removal** -- its own reflective relationship mapping has no
`PersistentBag`-equivalent substitution problem to begin with, so there was never an `@Transient`/proxy
conflict to automate away there, and either declaration shape is accepted. It **does** keep the `Map`-key
limitation Postgres has now dropped, and for a reason that is still live there: a `Map`-typed relationship
field creates one relationship per entry, with the original key stored as a `mapKey` property on the
relationship itself (not the target node, which may be reachable through more than one owner/key) -- `K` must
be `String`, validated eagerly at `registerEntityType` time, so that a key which can never round-trip back is
never silently stored. A `JavAILinkedHashMap`
field works on both backends as of this pass -- `RepositoryBackendNeo4jTest.mapFieldRoundTripsWithKeysPreserved`
and `RepositoryBackendHibernatePostgresTest`/e2e's own Postgres map test both prove it.

**MongoDB stores collection/map fields as references, not embedded documents** -- a deliberate choice, not
the more idiomatic-for-Mongo alternative. Embedding a `Comment` inside its `Article` would mean it can never
be independently found via its own `findNearestByTextVector` once nested, breaking the "every `@Entity` type
is independently queryable" symmetry both other backends share. Instead, a reference/collection field is
classified by declared type exactly like Neo4j's own relationship-field test (`Map`/`Collection`/
`JavAIVectorizable`-assignable), and stored as a `{type, id}` pointer (plus `ordinal`/`key` for
collection/map members) in an array under the field's own name on the owner's document -- the referenced
entity keeps its own top-level document in its own collection. Same `String`-only `Map`-key limitation as
the other two backends, kept for cross-backend consistency even though MongoDB documents could technically
hold richer keys. `RepositoryBackendSpringDataMongoTest.mapFieldRoundTripsWithKeysPreserved` proves this
the same way `RepositoryBackendNeo4jTest`'s equivalent test does.

**`deleteById` does not cascade** on MongoDB, unlike Postgres (which cascades collection members) -- a
referenced document simply becomes unreferenced, not deleted. Documented as a known Phase 0 boundary in
`RepositoryBackendSpringDataMongo`'s own javadoc, not an oversight.

**On Postgres, `deleteById` detaches the entity from every container holding it first (OMI-255)** -- removing
Hibernate's join rows through the mapping, so the foreign key has nothing left to refuse. It was added
because only the membership-table half of this existed at the time, so deleting an entity succeeded or died
depending purely on which of the two storage shapes its container declared -- not a distinction a caller
deleting something should have to know about. Only one shape remains (OMI-277), and this is it. Membership
only: no other entity is deleted. A *singular*
reference at the entity is still refused by the foreign key, deliberately -- clearing someone else's field
is a change to their data rather than a cleanup, and refusing loudly is the better answer.

**On Postgres, `UserCollectionType` makes interface-typed JavAI collections native JPA associations** --
see "JavAI collections as native JPA associations (OMI-142)" under "What's actually implemented" below for
the mechanism. Neo4j and MongoDB have no equivalent concept and stay on the reflective, declared-type
classification described above.

## `KnowledgeGraph<N, E>` fields -- Neo4j only

`KnowledgeGraph` (`javai-collections`) stays a pure, in-memory collection with zero persistence awareness of
its own -- no `persisted(...)`-style method, no repository/backing-store argument anywhere on it. A
`KnowledgeGraph`-typed field persists the same way any other JavAI collection field does: declare it as an
ordinary field on an `@Entity`/`@JavAIVectorizable` owner, and `RepositoryBackendNeo4j`'s existing reflective
field mapper picks it up automatically, no separate ceremony for the field itself (though, like any
relationship target, the node type still needs its own `JavAIPI.repository(...)` call at some point so
Neo4j's label registry can resolve it).

```java
class ResearchTopic implements JavAIVectorizable {
    KnowledgeGraph<Concept, RelatesTo> graph = new JavAIKnowledgeGraph<>();
    // ...
}
```

`RepositoryBackendNeo4j` maps such a field to two field-name-scoped relationship types (derived from the
field's own name via the same `relationshipType(...)` helper the rest of this backend already uses):
`<FIELD>_MEMBER` (owner → node, establishing graph membership -- this is what makes an isolated node with no
edges still round-trip, and what lets hydration tell which nodes belong to *this* field/owner even if the
same node type is also reachable through some other `KnowledgeGraph` field elsewhere) and `<FIELD>_EDGE`
(node → node, the graph's own internal edges). Both edges MERGE on their *full* property set as part of the
match pattern itself, not via a bare-pattern MERGE followed by `SET` -- contrast `TaggingBackendNeo4j`'s
deliberate "zero or one association" bare-pattern MERGE (`javai-tagging`), which is the wrong shape here:
`KnowledgeGraph.edges(from, to)` returns a `Set<E>`, so two distinct edges (different property values)
between the same node pair must persist as two distinct relationships, while re-saving identical edge
property values must stay idempotent. Edge instances are commonly Java records (`record RelatesTo(String
reason) implements JavAIEdge {}`); since records can't be reflectively field-assigned after construction,
hydration reconstructs them via their canonical constructor from each `RecordComponent`'s stored value
instead, falling back to ordinary no-arg-constructor-plus-field-write for a hand-written (non-record) edge
class.

`save()`/hydration never construct a live/reactive graph proxy that performs I/O from inside `addNode`/
`addEdge` -- every `save()` writes the graph's current in-memory contents in one shot, and every hydration
rebuilds a fresh, plain `JavAIKnowledgeGraph` and sets it onto the field, identical in spirit to how every
other JavAI collection round-trips. `RepositoryBackendNeo4jTest` proves the full round trip: multi-node/
multi-edge graphs, an isolated node with no edges, multiple distinct edges between the same pair, an empty
graph, idempotent re-save, and `nearestSubgraph()` correctness against the rehydrated graph.

**Postgres and MongoDB reject a `KnowledgeGraph`-typed field clearly, at registration time.** Left
unguarded, Postgres's field classifiers would let it fall through to being handed to Hibernate as an
ordinary, unmappable field (a confusing boot-time exception); Mongo's `relatedEntityType` would misidentify
it as a plain referenceable entity (since `KnowledgeGraph extends JavAIVectorizable`), which has no `@Id`
and would fail deep inside id-reading code instead. Both backends now throw a clear `IllegalArgumentException`
naming the offending field and class, and pointing at Neo4j, the moment such a field is registered.

**Why Neo4j-only, not eventually all three?** Every other JavAI collection persists uniformly across all
three backends because "a collection of related entities" has some natural representation in each of them.
`KnowledgeGraph`'s actual value -- native multi-hop traversal combined with a similarity query in one call
(`nearestSubgraph`) -- has no efficient equivalent on Postgres or MongoDB; building one would mean hand-
rolling a real graph-traversal engine on top of a relational/document store, a substantial undertaking
deliberately out of scope for this project's Phase 0.

## Where vectors actually live

**Postgres**: `javai_vectors__<model>` (one row per `owner_type`/`owner_id`/`field_name`, `field_name`
being either a real `@Vectorize` field or the reserved sentinel `$vector` for the object's own combined
vector) and `javai_summary_vectors__<model>` (one row per `owner_type`/`owner_id`) -- a fresh pair of tables
per model, named via `ModelIds.sanitize(modelId)`, created the first time that model is actually used, with
its `vector` column fixed to the correct dimension from creation (known upfront, since only one model's
vectors will ever land in a given table). The developer's own `@Entity` table is never touched -- their own
JPA mapping, and any proprietary vector columns they declare for their own purposes, are completely
unaffected; no naming collision is possible since JavAI's vectors never share a table with anything else.
`findNearestBy*` ranks entirely within one model's own table (index-accelerated, bounded by `LIMIT`)
*before* touching the entity's own table at all -- rank, then hydrate exactly the winning ids, never a join
across the full vector table. `deleteById` removes a given entity's rows from *every* per-model table that
currently exists (found via `information_schema.tables`, not just tables created during the current
process's lifetime), so nothing is orphaned when an entity is deleted regardless of how many models have
ever touched it.

Two Postgres-only details that follow from those tables being *shared* rather than per-entity (OMI-255).
**The table DDL is provisioned once, on its own connection, outside any caller's transaction** -- it used to
run before every single vector write, and `CREATE INDEX IF NOT EXISTS` takes a lock conflicting with a
concurrent transaction's inserts on the same table, so two concurrent saves deadlocked on it. And **a row is
written only when its value actually changes**: a value-identical `UPDATE` still creates a row version at
`REPEATABLE READ`, which is a collision a concurrent writer pays for and nobody gains from. A third table,
`javai_summary_pending`, records which containers owe a summary recomputation; it exists only in a
deployment that uses `@Summary`, and is empty except between a write and the drain that follows it.

**Loading serves stored vectors back rather than recomputing them, for the whole loaded graph** -- the root
and every entity reachable from it (OMI-187 for the root, OMI-256 for the rest). JavAI's vector caches live
on a woven *instance* field, so they cannot survive a load: Hibernate hands back a different object for the
same logical entity every time. `findById`/`findAll`/derived finders/vector search therefore read each loaded
entity's stored vectors straight into its cache slots. Until OMI-256 that covered the root only, so the
members Hibernate materializes behind a `@OneToMany`/`@ManyToMany` arrived cold and the next save re-embedded
every one of them -- **one model call per member, per re-save**, so loading an album of fifty assets and
saving it back to change its title cost fifty embeddings for content that had not changed and whose vectors
were sitting in the database. The traversal is the one `hydrateGeoPoints` already made on the same paths, so
it initializes nothing that was not already being initialized: an untouched lazy association is still
skipped, never loaded on JavAI's initiative.

**Neo4j**: `<field>Vector__<model>` per `@Vectorize` field, plus `vector__<model>`/`summaryVector__<model>`
for the combined/summary ones (each with a `...ComputedAt__<model>` sibling) -- direct node properties,
required for Neo4j's native vector index, which needs a direct property, not a related node's. Since node
properties are schemaless, an older model's property is simply never touched once a newer model starts
writing to its own, differently-named property on the very same node -- the old property sitting right
there next to the new one *is* the version history, with no separate archival relationship/node type
needed. `@Summary` fields become relationships (type name upper-snake-cased from the field name) to
recursively-saved related nodes, which therefore need their own `@Id`. `deleteById` is a single
`DETACH DELETE`, removing every model's properties on that node in one step -- simpler than the Postgres
side here, since there's no separate per-model table to clean up.

**MongoDB**: `<field>Vector__<model>` per `@Vectorize` field, plus `vector__<model>`/`summaryVector__<model>`
(each with a `...ComputedAt__<model>` sibling) -- fields within the entity type's *one* MongoDB collection
(named from the entity's simple class name, same Phase 0 assumption as Neo4j's label), never a physical
collection per model. Every write goes through `$set` (via the driver's `updateOne(..., upsert)`), never a
whole-document `replaceOne` -- the same reason both other backends never let a newer model's write touch an
older model's storage: a full-document replace would silently destroy whatever the older model's fields
held. A MongoDB Search vector index is created lazily per `(collection, field, model)` triple the first time
it's needed, named `javai_<collection>_<field>` and scoped to that field's own dimension -- but unlike
Postgres's HNSW index or Neo4j's native vector index (both usable the instant they're created), a MongoDB
Search index builds *asynchronously* in the background; `RepositoryBackendSpringDataMongo` polls until it
reports `queryable: true` before the first query against it, and separately retries past a real, confirmed
startup race where a freshly-started deployment's Search Index Management service (`mongot`) isn't
reachable for the first several seconds after the deployment's own port is already accepting connections.

## Dependencies pinned in the root `pom.xml`

`org.hibernate.orm:hibernate-core` + `hibernate-vector` (native `@JdbcTypeCode(SqlTypes.VECTOR)` pgvector
column mapping, confirmed on the same release train as `hibernate-core`), `org.postgresql:postgresql`,
`org.neo4j.driver:neo4j-java-driver`, and `org.springframework.data:spring-data-mongodb` +
`org.mongodb:mongodb-driver-sync` (the latter not pulled in transitively by the former -- confirmed via
`mvn dependency:tree` -- so it's declared explicitly, pinned to the same version). Vector query parameters
bind as pgvector's own text literal format (`?::vector`) rather than a JDBC PGobject type -- simpler and
avoids connection-unwrapping uncertainty through Hibernate's layer.

## What's actually implemented

`JavAIRepository`/`JavAIPI`/`JavAIPersistenceConfig` (`RepositoryBackendHibernatePostgres`/
`RepositoryBackendNeo4j`/`RepositoryBackendSpringDataMongo` behind a `java.lang.reflect.Proxy`), all three
backends' save/saveAll/findById/findAll/deleteById/reindexAll plus the three `findNearestBy*` variants,
described above. `reindexAll()` needs almost no backend-specific code -- it's a `findAll()` + `save(...)`
loop over each backend's existing methods, chunked and batch-warmed by one shared SPI default.

**Batching: how many provider calls a write costs (OMI-266).** A flush reads one field at a time, so lazy
computation used to discover one text at a time and pay a round trip for each. Measured here, before → after:

| Flow | Texts | Round trips before | after |
|---|---|---|---|
| `save()` -- one entity, two `@Vectorize` fields | 2 | 2 | **1** |
| `save()` -- a container and twelve members | 13 | 13 | **1** |
| `saveAll()` -- twelve entities | 24 | *(no such method)* | **1** |
| `reindex()` -- whole table under a new model | 98 | 98 | **1** |
| `inTransaction` -- twelve `save` calls in a loop | 24 | 24 | 12 |

The texts are unchanged in every row: this is how many calls they arrive in, not how much work is done, and
OMI-187's exactly-once invariant is untouched. The per-save half needed **no code in this module at all** --
`JavAIRuntime.runWithSubgraphLockedForPersistence`, which all three backends already wrap `save()` in, had
already computed the whole reachable subgraph in order to lock it, and now warms it before the flush runs.
Batching is measured on each backend rather than inherited from the Postgres numbers
(`EmbeddingRoundTripCostTest`, plus a case in each of `RepositoryBackendNeo4jTest`/
`RepositoryBackendSpringDataMongoTest`).

Note those round trips happen while the subgraph is locked against mutation, and -- on Postgres and Neo4j --
inside the open transaction, so this shortens lock and transaction hold time, not just wall-clock. `saveAll`
computes its embeddings *before* the transaction opens, so a bulk write holds no transaction across them.

The last row is deliberate: batching across an `inTransaction` body would mean deferring every vector write to
commit time, across three backends and through OMI-255's summary queue. `saveAll` is the answer for that
shape, and the row is pinned by a test so the cost of not doing it stays visible.

**Transaction participation (OMI-146), Postgres.** A repository call joins a Spring `@Transactional` unit of
work or a `JavAIPI.inTransaction(...)` body when one is active, and opens its own session only when there
isn't -- see "Transactions" above. `SpringTransactionalIntegrationTest` (19 tests, real Spring contexts over a
real Postgres) and `JavAIProgrammaticTransactionTest` (5) cover both paths.

**Configurable physical naming (OMI-145), Postgres.** snake_case by default, overridable via
`Builder.physicalNamingStrategy(...)`, plus a general `Builder.hibernateProperty(...)`/`.hibernateProperties(...)`
passthrough for any other Hibernate setting -- see "Physical naming" above. `PhysicalNamingStrategyTest`
asserts the generated DDL for each case, including the precedence between the two knobs.

**JavAI collections as native JPA associations (OMI-142), Postgres.** A collection field declared by a JavAI
*interface* (`JavAIList`/`JavAISet`/`JavAIMap`), non-final, with an ordinary `@OneToMany`/`@ManyToMany`, is now
a genuine Hibernate association -- its own join table/FK, cascade, `orphanRemoval`, lazy loading -- while the
instance Hibernate substitutes into the field is a real JavAI collection (`PersistentJavAIList` and friends,
extending Hibernate's `PersistentBag`/`PersistentSet`/`PersistentMap`), so vectors and dirty-tracking survive.
**No JavAI-specific annotation is required**: the backend attaches the `UserCollectionType` itself between
`buildMetadata()` and `buildSessionFactory()`. Since OMI-277 this is the **only** shape -- a *concrete*-typed
field (`JavAIArrayList<X>`) is refused at registration, and the storage it used to get is gone. Nested finders
over a to-many hop are therefore a single Criteria JOIN throughout, the id-set-per-hop path having gone with
the side table that needed it. `KnowledgeGraph` remains Neo4j-only.

**Ordinary relational derived finders (OMI-138), on all three backends.** A repository interface may
declare Spring-Data-style finders -- `findBy`/`existsBy`/`countBy`/`deleteBy` with `And`/`Or`, the full
operator set (`GreaterThan`/`Between`/`Like`/`Containing`/`In`/`IsNull`/`True`/`IgnoreCase`/...), static
`OrderBy`, `Top`/`First`, `Distinct`, dynamic `Sort`/`Pageable`/`Limit`, and the
`List`/`Optional`/single/`Stream`/`Page`/`Slice`/`long`/`boolean` return adapters -- alongside the
`findNearestBy…` vector convention. The name grammar is delegated to Spring Data's own `PartTree`
(`spring-data-commons`, already present transitively via `spring-data-mongodb`; now declared explicitly
since it's used directly), which validates every referenced property against the entity type *by field*
(no JavaBean accessors required), so an unknown property or a bad parameter count fails fast at
repository-creation time -- the same guarantee the vector convention already gives. `DerivedFinderQuery`
owns that parse plus all return-type/`Pageable` adaptation; each backend implements only four primitives
(`findByDerivedQuery`/`countByDerivedQuery`/`existsByDerivedQuery`/`deleteByDerivedQuery`) plus a
`validateDerivedQuery` feasibility check, translating the parsed predicate into JPA Criteria (Postgres),
Cypher (Neo4j), or a driver filter (MongoDB). A **non-`@JavAIVectorizable`** `@Entity` is served by exactly
the same path (it simply writes no vectors) -- proven by `TestAccount`/`TestAccountRepository` across all
three backend test classes. Two backend-specific points worth calling out:

- **Nested-association finders** work through *both* singular associations (`findByProfileHandle`) and
  to-many collections (`findByReviewsReviewer`), on all three backends (OMI-141). Neo4j uses a self-contained
  `EXISTS { MATCH (n)-[:REL]->(x) WHERE … }` subquery; MongoDB, whose related entities are out-of-band
  `{type, id}` pointers, resolves matching related ids and expresses the predicate as `root.id IN (…)`.
  Postgres is a native Criteria join throughout, singular or to-many (OMI-277) -- the exception being a
  **geo** predicate, whose `javai_geo_points` is not a table Hibernate can join, so that one still resolves
  as an id set per hop. Collection emptiness (`findByReviewsIsEmpty`) and `Regex`/`Matches` ride the same
  paths.
- **Geo `Near`/`Within`** over a `Point` field: a Neo4j native `point` + `point.distance`, a MongoDB GeoJSON
  point + `$geoWithin`/`$centerSphere`, and (Postgres) a `javai_geo_points` side table +
  `earth_distance(ll_to_earth(…))` via the `cube`+`earthdistance` contrib extensions (bundled with the
  official Postgres base image, so no PostGIS/`hibernate-spatial` dependency and no container-image swap).
- **Value-conversion parity** on the reflective backends: Neo4j and MongoDB store `UUID`/`Instant`/`enum`
  scalars in converted (string) form, so a finder's bound arguments are run through the same conversion
  before hitting the query -- otherwise `findByUserId(uuid)` would compare a raw `UUID` against a stored
  string and silently match nothing. Postgres needs no such step (Hibernate binds Java types natively).
  See `doc/spec/persistence-bridge.md`'s "Ordinary relational derived finders" for the full note. Covered by `RepositoryBackendHibernatePostgresTest`/`RepositoryBackendNeo4jTest`/
`RepositoryBackendSpringDataMongoTest` against real `pgvector/pgvector:pg16`/`neo4j:5.26-community`/
`mongodb/mongodb-atlas-local:8.2` containers (Testcontainers) -- there's no meaningful way to hermetically
fake whether a real similarity search actually ranks correctly, or whether two models' data really stay
physically separate.

**Every `save()` on all three backends is wrapped in `JavAIRuntime.runWithSubgraphLockedForPersistence(entity, ...)`**
(`javai-model` -- see `doc/spec/vector-core.md`'s "Embedding concurrency model" section for the full
mechanism): the whole reachable subgraph is locked and every field/`concatenatedTextVector()` read forced
accurate for the duration of the flush, regardless of which `EmbeddingConsistencyMode` is globally
configured. This is what guarantees the database never sees a vector that doesn't match the field value
being written in that same save -- proven directly by
`savedVectorIsAlwaysAccurateUnderImmediateConsistency`/
`savedVectorIsAlwaysAccurateUnderEventualConsistencyDespiteASlowBackgroundProvider` on all three backend
test classes, the latter using a deliberately slow provider to prove the flush doesn't just get lucky racing
against `EVENTUAL_CONSISTENCY`'s own eager background dispatch.

**Deliberately not this module's own weaving.** No `javai-substrate` dependency, and no ByteBuddy-based
Hibernate-enhancement shim of its own either (the whitepaper's original "ByteBuddy enhancement via
Hibernate's `EnhancementContext`-style SPI" phrasing) -- both would have added real, more fragile machinery
(a less commonly hand-rolled Hibernate SPI, or a second, module-specific weaver) to auto-manage shadow
storage on the developer's own entity, when the per-model-table design above delivers the same
auto-vectorized-on-write behavior without needing to enhance their class at all. `TestArticle` (this
module's own test fixture) shows the shape a real `@JavAIVectorizable` + `@Entity` class needs:
`javai-substrate`'s weaver, when present, already declares its synthesized state field with the JVM `transient`
modifier specifically so Hibernate's default field-access mapping skips it with no annotation the developer
never sees in source.

**Also deliberately not MongoDB's own "Hibernate Extension for MongoDB."** MongoDB publishes an official
Hibernate ORM extension, which would have kept the Mongo backend structurally symmetric with the Postgres
one above. Not used: as of this writing it's Public Preview ("not recommended for production deployments,
because breaking changes might be introduced" -- MongoDB's own docs), it doesn't support JPA associations
(`@OneToOne`/`@OneToMany`/`@ManyToMany` are all unsupported -- the Postgres backend's own singular-reference
handling depends on exactly this), and vector search is reachable only through un-parameterized native MQL,
not a first-class API. Spring Data MongoDB was used instead: mature `$vectorSearch` support, and no
association model to fight in the first place, since this backend manages its own `{type, id}` reference
pointers directly (see "Collections" above) rather than asking an ORM to manage relationships it can't yet
express.

**Not yet built, and documented rather than silently dropped:**
- `reindexAll()` loads every entity into memory via `findAll()`, matching that method's own existing scope
  -- no batching/pagination for very large tables yet.
- No automatic *detection* of a model change that triggers `reindexAll()` on its own -- it's an explicit
  call the developer makes after swapping providers, by design (see doc/spec/persistence-bridge.md's own
  framing: "a runtime configuration concern," triggered deliberately, not implicitly).
- Single-statement nested-to-many/geo/emptiness resolution. These work on all three backends (OMI-141) but
  via id-set materialization (a query per hop), not one SQL/aggregation statement -- a mapped membership
  entity for Criteria subqueries (Postgres) and a `$lookup` aggregation (MongoDB) are the natural later
  optimizations. Geo uses `earthdistance` point-distance, not full PostGIS/`hibernate-spatial` geometry.
- `JavAILinkedHashMap`/any `Map` relationship field with a non-`String` key type, on any backend -- see
  "Collections" above.
- Neo4j's `registerEntityType` doesn't (yet) recursively auto-register related entity types reachable
  through a field the way the Postgres and MongoDB backends now do -- a related type still needs its own
  `JavAIPI.repository(...)` call made first (see `RepositoryBackendNeo4jTest`'s own test fixtures for the
  pattern this implies today).
- MongoDB's `deleteById` doesn't cascade to referenced entities' own documents -- see "Collections" above.
- No dual-write/transactional multi-backend `save()` -- persisting one entity type to more than one store
  simultaneously means two independently-created repository proxies, one per backend (see
  `doc/spec/persistence-bridge.md`'s own section on this).
- Native JPA associations (`UserCollectionType`) are Postgres-only. Neo4j and MongoDB classify collection
  fields by declared type and have no equivalent, so an interface-typed JavAI collection field behaves the
  same as a concrete-typed one there -- an asymmetry by necessity, not an omission (neither store has a
  Hibernate provider that could serve JPA associations).

`e2e-client-test` now exercises this module directly: `PersistenceE2ETest`/`ArticleFixtureVolumeE2ETest`
save/query this project's own real `Article`/`Comment`/`Attachment` domain (not this module's own flat test
fixture) against the Postgres and Neo4j backends running in the same monolithic container as the rest of
the e2e suite, including the full object graph (singular references, the `JavAIArrayList`/
`JavAILinkedHashMap` collection fields above, real embeddings) and realistic-volume/semantic-similarity
checks. The MongoDB backend is proven at the unit level (`RepositoryBackendSpringDataMongoTest`, a real
container) but not yet wired into the monolithic e2e container/`JavAIEnvironment` alongside the other two.
