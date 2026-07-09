# javai-persistence

Extension area: **Persistence Bridge**. Whitepaper: §4.4, §4.5.4, §5.2, §6.6. Full detail:
[`doc/spec/persistence-bridge.md`](../doc/spec/persistence-bridge.md).

Depends on `javai-collections` and `javai-runtime` (not `javai-agent` -- see "What's actually implemented"
below for why that matters). Takes what Vector Core computes in memory and makes it durable and queryable
in a real store, without asking the developer to hand-manage a parallel vector index alongside their ORM.

## Primitives

| Element | Kind | Purpose |
|---|---|---|
| `JavAIPI` | Static facade | `repository(Class)` realizes a `JavAIRepository<T>` subinterface as a dynamic `Proxy`; `configurePersistence(...)` overrides the self-contained default |
| `JavAIRepository<T>` | Interface | Base CRUD (`save`/`findById`/`findAll`/`deleteById`) plus `reindexAll()`, fixed to `UUID` identity |
| `findNearestBy<Field>Vector` / `findNearestByVector` / `findNearestBySummaryVector` | Derived query convention | The *only* derived queries supported -- validated at repository-creation time, not on first call |
| `JavAIPersistenceConfig` | Value object | Backend selection + connection settings, self-contained by default (`javai.persistence.*` system properties) but overridable with an externally-built `SessionFactory`/`Driver` |
| `javai_vectors__<model>` / `javai_summary_vectors__<model>` | Postgres tables, owned by this module, one pair per model | Per-field + combined vectors, and `summaryVector()`, respectively -- never the developer's own entity table |
| `<field>Vector__<model>` / `vector__<model>` / `summaryVector__<model>` | Neo4j node properties, one set per model | Same idea as the Postgres tables, applied to a schemaless node instead |
| `ModelIds.sanitize(modelId)` | Shared helper | Turns an arbitrary model id string into the identifier fragment both backends key their per-model tables/properties/indexes on |

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
   It re-saves every entity found via `findAll()`; since `save()` always writes under whichever provider is
   *currently* configured, this populates the new model's table/properties for every existing entity, in
   one call, without the developer hand-writing a re-save loop themselves.
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

ArticleRepository repo = JavAIPI.repository(ArticleRepository.class);

repo.save(article);   // auto-vectorized on write -- every @Vectorize/@Summary vector recomputed and persisted

List<Article> hits = repo.findNearestByBodyVector(queryVector, 20);

// Model swap: same two steps regardless of whether the new model's output dimension differs --
JavAIRuntime.configureEmbeddingProvider(newProvider);
repo.reindexAll();

// Revert: one step, no reindexing --
JavAIRuntime.configureEmbeddingProvider(oldProvider);

// Swapping the persisted *backend* (not the model) is also configuration, not code:
//   javai.persistence.backend=postgres   (default)
//   javai.persistence.backend=neo4j
```

`<Field>` in `findNearestBy<Field>Vector` names the same accessor the weaver already synthesizes in memory
(`bodyVector()` -> `findNearestByBodyVector`), not the bare field name -- deliberately the same name a
developer would already call directly on a woven object. `findNearestByVector`/`findNearestBySummaryVector`
are the whole-object variants, for the object's own combined `vector()`/`summaryVector()`.

**Register every repository before using any of them.** `JavAIPI.repository(...)` accumulates entity types
under the hood; the Postgres backend's internal `SessionFactory` is built, once, lazily, on the *first*
actual method call across any repository -- Hibernate's boot-time metadata is immutable afterward, so an
entity type registered later would never be mapped. On Postgres, this is narrower than it sounds in
practice: `JavAIPI.repository(ArticleRepository.class)` alone is enough even if `Article` references
`Comment`/`Attachment` (singly or through a collection) -- related entity types reachable through an
already-registered type's own fields are discovered and registered automatically, recursively. You only need
to separately call `JavAIPI.repository(...)` for a type you intend to query *directly and independently*.

## Collections: `JavAIArrayList`/`JavAILinkedHashSet`/`JavAILinkedHashMap` fields

A field typed as one of `javai-runtime`'s concrete vector-aware collections can never be a *native*
Hibernate-mapped collection association -- Hibernate always substitutes its own `PersistentBag`/
`PersistentSet`/`PersistentMap` wrapper into a mapped collection field the instant it's persisted (confirmed
empirically: a `ClassCastException` the moment that wrapper can't be assigned back to a field statically
typed as the concrete JavAI class). Declaring the field by interface type instead wouldn't help either --
Hibernate would then silently install its own wrapper in place of the real `JavAIArrayList`, permanently and
silently discarding its vector/dirty-tracking behavior with no error at all, which is worse than a loud
failure.

**No manual `@Transient` needed, on Postgres, as of this pass.** `HibernatePostgresRepositoryBackend`
reflectively detects, for every registered entity type, which fields are shaped like a JavAI collection (a
`Collection`/`Map` that also implements `JavAIDirtyTracking` -- true of all three concrete types today, and
of any future one following the same pattern, with no code change needed) and generates an in-memory JPA
`orm.xml`-equivalent mapping document marking exactly those fields `<transient>`, fed to Hibernate via
`MetadataSources.addInputStream` alongside the ordinary annotation scanning. This is a real, spec-defined JPA
override mechanism (XML mappings logically override annotations for whatever they explicitly mention), not a
hack -- confirmed with a real container before being relied on. Such fields instead round-trip through
`javai_collection_members`, a single, shared (not per-model) table this backend owns (owner/field/member
identity, an optional string key for `Map` fields, an ordinal for order). Hydration is reflective, not
proxy-based: it adds members into whatever collection/map instance the entity's own no-arg constructor
already created (a real `JavAIArrayList`, full dirty-tracking intact) rather than replacing the field's
value, so a `final` collection field works fine.

**Known limitation: `Map` fields must be keyed by `String`, on Postgres, in this phase.** The membership
table's key column is a plain `varchar`; a `JavAILinkedHashMap<K, V>` field is only supported when `K` is
`String`. This is validated eagerly, at repository-registration time, with a clear `IllegalArgumentException`
for any other key type -- silently storing a stringified key that could never correctly round-trip back to
its original type would be a much worse outcome than an immediate, explicit error.

**Neo4j doesn't need any of this ceremony removal** -- its own reflective relationship mapping has no
`PersistentBag`-equivalent substitution problem to begin with. It does, however, have a real, separate,
currently-tracked gap of its own: relationship hydration only handles `Collection`-typed fields correctly
today, not `Map`-typed ones, and map keys aren't persisted as relationship properties at all yet -- so a
`JavAILinkedHashMap` field works on Postgres but not yet on Neo4j.

**Future direction: `UserCollectionType`.** A more ambitious fix -- letting these fields map *natively* via
Hibernate's `org.hibernate.usertype.UserCollectionType` SPI, which lets a custom `PersistentCollection`
implementation stand in for `PersistentBag`/`PersistentSet`/`PersistentMap` and could in principle preserve
JavAI's vector/dirty-tracking behavior *while* being a real, natively Hibernate-managed association -- is
deliberately not pursued yet. It's Postgres/Hibernate-only (Neo4j has no equivalent concept and would remain
on its own reflective mechanism regardless, breaking the symmetry the two backends otherwise share), and
correctly implementing a custom `PersistentCollection` is a substantial, easy-to-get-subtly-wrong undertaking
(dirty-checking snapshot semantics, session attachment/detachment, lazy-initialization) disproportionate to
a Phase 0 module whose job is proving the design space, not hardening a production ORM integration. Worth
revisiting if this module graduates past Phase 0.

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

## Dependencies pinned in the root `pom.xml`

`org.hibernate.orm:hibernate-core` + `hibernate-vector` (native `@JdbcTypeCode(SqlTypes.VECTOR)` pgvector
column mapping, confirmed on the same release train as `hibernate-core`), `org.postgresql:postgresql`, and
`org.neo4j.driver:neo4j-java-driver`. Vector query parameters bind as pgvector's own text literal format
(`?::vector`) rather than a JDBC PGobject type -- simpler and avoids connection-unwrapping uncertainty
through Hibernate's layer.

## What's actually implemented

`JavAIRepository`/`JavAIPI`/`JavAIPersistenceConfig` (`HibernatePostgresRepositoryBackend`/
`Neo4jRepositoryBackend` behind a `java.lang.reflect.Proxy`), both backends' save/findById/findAll/
deleteById/reindexAll plus the three `findNearestBy*` variants, described above. `reindexAll()` needed no
backend-specific code at all -- it's dispatched in `RepositoryInvocationHandler` purely as a
`findAll()` + `save(...)` loop over each backend's existing methods. Covered by
`HibernatePostgresRepositoryBackendTest`/`Neo4jRepositoryBackendTest` against real
`pgvector/pgvector:pg16`/`neo4j:5.26-community` containers (Testcontainers) -- there's no meaningful way to
hermetically fake whether a real similarity search actually ranks correctly, or whether two models' data
really stay physically separate.

**Deliberately not this module's own weaving.** No `javai-agent` dependency, and no ByteBuddy-based
Hibernate-enhancement shim of its own either (the whitepaper's original "ByteBuddy enhancement via
Hibernate's `EnhancementContext`-style SPI" phrasing) -- both would have added real, more fragile machinery
(a less commonly hand-rolled Hibernate SPI, or a second, module-specific weaver) to auto-manage shadow
storage on the developer's own entity, when the per-model-table design above delivers the same
auto-vectorized-on-write behavior without needing to enhance their class at all. `TestArticle` (this
module's own test fixture) shows the shape a real `@JavAIVectorizable` + `@Entity` class needs:
`javai-agent`'s weaver, when present, already declares its synthesized state field with the JVM `transient`
modifier specifically so Hibernate's default field-access mapping skips it with no annotation the developer
never sees in source.

**Not yet built, and documented rather than silently dropped:**
- `reindexAll()` loads every entity into memory via `findAll()`, matching that method's own existing scope
  -- no batching/pagination for very large tables yet.
- No automatic *detection* of a model change that triggers `reindexAll()` on its own -- it's an explicit
  call the developer makes after swapping providers, by design (see doc/spec/persistence-bridge.md's own
  framing: "a runtime configuration concern," triggered deliberately, not implicitly).
- Arbitrary Spring-Data-style derived queries (`findByTitle` and friends) -- only the `findNearestBy*`
  family above is supported, and anything else fails fast, with a clear message, at repository-creation
  time.
- `JavAILinkedHashMap` fields with a non-`String` key type (Postgres) or any populated `Map`-typed field at
  all (Neo4j) -- see "Collections" above.
- The `UserCollectionType`-based native mapping described above -- documented as a real future direction,
  not started.

`e2e-client-test` now exercises this module directly: `PersistenceE2ETest`/`ArticleFixtureVolumeE2ETest`
save/query this project's own real `Article`/`Comment`/`Attachment` domain (not this module's own flat test
fixture) against both real backends running in the same monolithic container as the rest of the e2e suite,
including the full object graph (singular references, the `JavAIArrayList`/`JavAILinkedHashMap` collection
fields above, real embeddings) and realistic-volume/semantic-similarity checks.
