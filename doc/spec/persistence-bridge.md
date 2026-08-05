# Persistence Bridge

Module: `javai-persistence`. Whitepaper: §4.4, §4.5.4, §5.2, §6.6. Depends on `javai-collections`,
`javai-vector`, and `javai-model`.

Takes what Vector Core computes in memory and makes it durable and queryable in a real store, without
asking the developer to hand-manage a parallel vector index alongside their ORM.

## Primitives

| Element | Kind | Purpose |
|---|---|---|
| `JavAIPI` | Internal contract | The save/query/re-index contract JavAI objects speak internally; `repository(Class, JavAIPersistenceConfig)` takes its backend config as an explicit argument, no ambient "current config" |
| `JavAIRepository<T>` | Interface | Spring-Data-style repository base; delegates to existing derived-query-method machinery |
| `findNearestBy<Field>(EmbeddingVector, int limit)` | Derived query method convention | E.g. `findNearestByBodyVector` — repository-level nearest-neighbor search |
| `findBy<Field>`/`existsBy…`/`countBy…`/`deleteBy…` | Ordinary derived finders | Full Spring-Data-style relational finders (parsed via `PartTree`), resolved against the entity's own mapped columns — so one repository serves both an entity's relational access and its vector search. See "Ordinary relational derived finders" below |
| Hibernate-based enhancement shim | Mechanism | ByteBuddy enhancement via Hibernate's `EnhancementContext`-style SPI |
| `hibernate-vector` module | Dependency | Native pgvector column mapping (`@JdbcTypeCode(SqlTypes.VECTOR)`) |
| Neo4j-facing shim | Mechanism | Parallel graph-native persistence backend, same `JavAIPI` contract |
| MongoDB-facing shim | Mechanism | `RepositoryBackendSpringDataMongo` -- Spring Data MongoDB's `MongoTemplate` for connection/database access, raw driver reads/writes/`$vectorSearch` for the vector-specific work, same `JavAIPI` contract |
| `embedding_version` side table / expand-contract migration | Pattern | Versioned vector storage across model changes, non-destructive |
| `KnowledgeGraph<N, E>` field support | Neo4j-only | An ordinary field on any `@Entity`/`@JavAIVectorizable` owner, mapped to two field-scoped relationship types; `RepositoryBackendHibernatePostgres`/`RepositoryBackendSpringDataMongo` reject it clearly at registration time -- see "`KnowledgeGraph` fields: Neo4j-only" below |

## Reference implementation shape

`JavAIPI` (JavAI Persistence Interface) is implemented once against a Hibernate-based reference shim —
ByteBuddy enhancement plus an `EnhancementContext`-style SPI — leveraging Hibernate's existing
`hibernate-vector` module rather than proposing a JPA/Jakarta EE spec change. A parallel shim targets
Neo4j, which already supports native vector indexes alongside Cypher traversal.

```java
public interface ArticleRepository extends JavAIRepository<Article> {
    List<Article> findNearestByBodyVector(EmbeddingVector reference, int limit);
}

JavAIPersistenceConfig config = JavAIPersistenceConfig.builder()
        .backend(JavAIPersistenceConfig.Backend.POSTGRES) // or NEO4J, MONGODB
        ./* connection settings */.build();
ArticleRepository repo = JavAIPI.repository(ArticleRepository.class, config);

repo.save(article);   // auto-vectorized on write

List<Article> hits = repo.findNearestByBodyVector(queryVector, 20);
```

`JavAIPI.repository(Class, JavAIPersistenceConfig)` takes its config explicitly, every call -- there is no
ambient "current config" pointer anywhere in `JavAIPI`. `JavAIPersistenceConfig.fromSystemProperties()` is a
pure factory a caller can invoke explicitly (reading `javai.persistence.backend=postgres|neo4j|mongodb` plus
the matching connection properties) for the old self-contained-default convenience, but it's never
auto-applied or cached -- swapping the persisted backend is still configuration, not code, it's just an
explicit argument now rather than a separate setter call.

## Why MongoDB is a Spring Data MongoDB shim, not a Hibernate one

MongoDB publishes its own "MongoDB Extension for Hibernate ORM," which would have kept this backend
structurally symmetric with the Postgres shim above. It was deliberately not used: as of this writing it's
Public Preview ("not recommended for production deployments, because breaking changes might be
introduced" -- MongoDB's own words), it doesn't support JPA associations (`@OneToOne`/`@OneToMany`/
`@ManyToMany` are all unsupported, which the Postgres shim's own singular-relationship handling depends
on), and vector search is reachable only via un-parameterized native MQL, not a first-class API. Spring
Data MongoDB, by contrast, has mature `$vectorSearch` support and needs no association model at all (this
backend stores related/collection-typed fields as `{type, id}` reference pointers it manages itself, the
same reference-not-embed choice `RepositoryBackendNeo4j` already makes for its own graph relationships).
See `javai-persistence/README.md`'s "MongoDB backend" section for the full mapping rules.

## `KnowledgeGraph` fields: Neo4j-only

`KnowledgeGraph<N, E>` (`javai-collections`, see `doc/spec/vector-collections.md`) is a plain, in-memory
collection — like `JavAIArrayList`/`JavAILinkedHashSet`/`JavAILinkedHashMap`, it has zero persistence
awareness of its own; it never accepts a repository or backing store, and never performs I/O from its own
`addNode`/`addEdge`/`nearestSubgraph` methods. A `KnowledgeGraph`-typed field persists exactly the way any
other JavAI collection field does: declare it as an ordinary field on an `@Entity`/`@JavAIVectorizable`
owner, and the backend's existing reflective field mapper picks it up automatically — no ceremony, no
separate registration call for the field itself.

```java
@Entity
class ResearchTopic implements JavAIVectorizable {
    @Id UUID id;
    @Vectorize String title;
    KnowledgeGraph<Concept, RelatesTo> graph = new JavAIKnowledgeGraph<>();
    // ...
}

JavAIPersistenceConfig config = JavAIPersistenceConfig.builder().backend(Backend.NEO4J)./* ... */.build();
JavAIPI.repository(ConceptRepository.class, config); // Neo4j needs the node type registered to resolve it
                                                       // by label; entityPackages(...) on the config does
                                                       // this for a whole package (OMI-214)
ResearchTopicRepository repo = JavAIPI.repository(ResearchTopicRepository.class, config);

repo.save(topic);                                     // writes nodes + edges in one shot
ResearchTopic reloaded = repo.findById(topic.getId()).orElseThrow();
reloaded.graph.nearestSubgraph(reference, 1, 2);       // works on the rehydrated graph exactly like in-memory
```

**This is Neo4j-only.** `RepositoryBackendNeo4j` maps a `KnowledgeGraph` field to two field-name-scoped
relationship types — `<FIELD>_MEMBER` (owner → node, so an isolated node with no edges still round-trips)
and `<FIELD>_EDGE` (node → node, the graph's own edges, MERGed on their full property set so `Set<E>`'s
"possibly several distinct edges between the same pair" semantics survive persistence). `save()`/hydration
never construct a live/reactive graph proxy — every call rebuilds a fresh, plain `JavAIKnowledgeGraph` and
sets it onto the field, identical in spirit to how every other JavAI collection round-trips. Both
`RepositoryBackendHibernatePostgres` and `RepositoryBackendSpringDataMongo` reject a `KnowledgeGraph`-typed
field with a clear `IllegalArgumentException` at registration time (naming the offending field and pointing
at Neo4j), rather than failing confusingly later — Postgres's own field classifiers would otherwise let it
fall through to being treated as a plain, unmappable scalar column; Mongo's would misidentify it as a
referenceable entity (since `KnowledgeGraph extends JavAIVectorizable`), which has no `@Id` and would fail
deep inside id-reading code the first time a document tried to reference it.

**Why not Postgres or MongoDB too, eventually?** Every other JavAI collection (`List`/`Set`/`Map`) persists
uniformly across all three backends because "a collection of related entities" has some natural
representation in each of them — a membership table, a reference-pointer array. `KnowledgeGraph`'s actual
value proposition is different: native multi-hop traversal combined with similarity search in one query
(`nearestSubgraph`). Neo4j has this built in (Cypher traversal plus a native vector index). Postgres and
MongoDB don't — building an equivalent would mean hand-rolling a real graph-traversal engine on top of a
relational/document store, a substantial undertaking deliberately out of scope for this project's Phase 0
(proving the design space), not an oversight or a temporary gap.

## Entity registration: a property of the configuration, not of call order

JavAI learns about an entity when a repository is realized for it, or for anything that references it --
related types reachable through an already-registered type's own fields are discovered recursively. On
**Postgres** the backend then builds one Hibernate `SessionFactory`, lazily, at the first actual repository
call (`JavAIPI.sessionFactory(config)` builds it too). Hibernate's metadata is immutable once built.

Left there, that makes correctness a property of *global startup ordering*: whether an application boots
depends on the order its repositories happen to be created in, which nothing local can check and no compiler
can verify. It failed at boot, non-deterministically, blaming the repository that arrived late rather than
whatever built the factory early — and because there is one shared factory, it took the whole application's
persistence down rather than one repository. Downstream it cost a consumer a hand-maintained 18-name
`@DependsOn` list that had already drifted out of sync with its own bean declarations (OMI-214).

**The resolution is to make the entity set a property of the configuration**, complete before anything can be
built:

```java
JavAIPersistenceConfig.builder()
    .backend(Backend.POSTGRES)./* ... */
    .entityPackages("com.example.domain")
    .build();
```

Ordering then stops existing as a concept. Two further properties keep the residue small:

- A late `repository(...)` call **is a no-op when it introduces nothing new** — the type is already
  registered, directly or transitively. Only a genuinely unknown type fails, and the error names the call
  that built the factory.
- **Neo4j and MongoDB have no such constraint at all.** They hold no boot-time metadata; the ordering
  question is Postgres-specific.

Scanning validates everything it finds, deliberately: an entity Hibernate maps but JavAI cannot is worse
than one that is refused, because the failure would surface later and far from its cause. Only three
conditions are refused, all about JavAI-owned field types rather than about vectors — a JavAI collection
keyed by something other than `String`, a `KnowledgeGraph` field (Neo4j-only), and a collection field that is
unmapped or is a concrete-typed JavAI collection carrying an association annotation. **Being non-vectorized
is never one of them**: a plain `@Entity` is a first-class citizen of a `JavAIRepository`, mapped and served
exactly like a vectorized one, it simply has no vectors. Serving both from one repository type is the design
intent, not a concession.

For an `@Entity` inside a scanned package that this configuration should not own, use
`excludeEntityType(...)`/`excludeEntityPackages(...)` (per-configuration — the right tool for a
backend-specific type such as a `KnowledgeGraph` owner belonging to Neo4j) or `@PersistenceIgnore` on the
class (global). Exclusion affects scanning only: a type named outright, or reached through a registered
entity's fields, is registered regardless, since Hibernate cannot map the referencing entity without it.

## Persisting one entity type to more than one store at once

There is no dual-write `save()` -- an entity type persisted to two backends simultaneously means two
independently-created repository proxies, one per backend, each permanently bound to whichever
`JavAIPersistenceConfig` was passed to the `JavAIPI.repository(...)` call that created it:

```java
JavAIPersistenceConfig postgresConfig = JavAIPersistenceConfig.builder().backend(Backend.POSTGRES)./* ... */.build();
ArticleRepository postgresRepo = JavAIPI.repository(ArticleRepository.class, postgresConfig);

JavAIPersistenceConfig mongoConfig = JavAIPersistenceConfig.builder().backend(Backend.MONGODB)./* ... */.build();
ArticleRepository mongoRepo = JavAIPI.repository(ArticleRepository.class, mongoConfig);

postgresRepo.save(article); // writes to Postgres only
mongoRepo.save(article);    // writes to MongoDB only -- the caller owns cross-store consistency
```

Both proxies stay independently usable regardless of what other `repository(...)` calls happen afterward --
a proxy's backend binding is fixed at creation time from the config argument given to it, not re-resolved on
each call, and there is no shared ambient state either proxy could be affected by.

## Embedding-model versioning: expand/contract migration

Embedding-model versioning is a runtime configuration concern, not a language feature. Swapping the
configured model triggers a non-destructive, background re-indexing pass across the object graph and, via
the shim, across the persisted store — never a blocking schema migration.

```sql
ALTER TABLE article ADD COLUMN body_vector vector(1024);
ALTER TABLE article ADD COLUMN body_vector_model varchar(64);

CREATE TABLE embedding_version (
    entity_id uuid REFERENCES article(id),
    field_name varchar(64),
    model_id varchar(64),
    vector vector(1024),
    computed_at timestamptz,
    PRIMARY KEY (entity_id, field_name, model_id)
);
```

The fast-path columns (`body_vector`, `body_vector_model`) serve the current model; the side table holds
old/new vectors coexisting during a migration window, tied to `EmbeddingVector.modelId`.

## Accuracy must-have, independent of embedding consistency mode

Vector Core's embedding concurrency model (`doc/spec/vector-core.md`) lets a mutation's own recompute
happen synchronously (`IMMEDIATE_CONSISTENCY`) or in the background (`EVENTUAL_CONSISTENCY`/
`COALESCED_CONSISTENCY`) -- but the database must never see a vector that doesn't match the field value
being written in the same flush, regardless of which mode is active. `JavAIRuntime.runWithSubgraphLockedForPersistence(root, action)`
is the mechanism both real backends wrap every `save()` call in: it locks every reachable
`JavAIVectorizable` in the subgraph being flushed (the same per-object lock `IMMEDIATE_CONSISTENCY` itself
uses) and forces every field/`concatenatedTextVector()` read on the calling thread to block for an accurate
value for the duration of the flush, overriding whichever consistency mode is globally configured just for
that thread and that call. Because every setter, under every mode, briefly takes this same lock around its
own bookkeeping, the subgraph is genuinely frozen against mutation for the duration too -- not just
protected on the read side. See `javai-persistence/README.md`'s own "What's actually implemented" section
for the tests proving this holds under all three modes.

## `@Summary` is written after the transaction, not inside it (OMI-255)

The accuracy rule above is about one writer: the database must never see a vector inconsistent with the
field value committed alongside it. It says nothing about two writers, and for **derived** state the two
questions have different answers.

A container's `summaryVector()` is stored as one row per `(owner_type, owner_id)`, so every mutation
anywhere beneath a `@Summary` container writes that same row. Writing it inside the caller's transaction
therefore made two unrelated writers -- different children, different tables, different primary keys --
collide on it: `could not serialize access due to concurrent update` at `REPEATABLE READ`, or a silent
last-writer-wins at `READ COMMITTED` where the surviving summary reflects only one of the two mutations.

**Neither isolation level can be made to give the right answer**, and this is worth stating precisely
because it rules out the obvious fix. A lock taken inside the writer's transaction cannot help at
`REPEATABLE READ`: the snapshot is fixed by the transaction's first statement, long before the container is
known, so a writer that waits for the lock and then updates a row committed after that snapshot is still
refused. Serialising the writers does not un-take their snapshots.

So the Postgres backend does not write it there at all:

1. **Inside the caller's transaction**, the mutation appends a row to `javai_summary_pending` naming the
   container. Every enqueue is an insert with its own fresh primary key, so concurrent writers produce
   distinct rows and cannot conflict at any isolation level. It commits and rolls back with the mutation.
2. **After that transaction commits** -- immediately when JavAI owns it, from a commit callback when it
   joined a Spring or `JavAIPI.inTransaction` one -- a short `READ COMMITTED` transaction takes a Postgres
   advisory lock on `(owner_type, owner_id)`, recomputes the summary *from the committed graph*, writes the
   one row, and deletes exactly the queue rows it claimed.

Recomputing from committed state rather than folding the writer's in-memory value is what makes step 2
correct rather than merely serialised: the drain that runs last has read both writers' children, so the
surviving summary reflects both. `READ COMMITTED` is required here, not incidental -- at `REPEATABLE READ`
two drains would refuse each other exactly as the writers used to.

**Upward propagation is resolved from the database, not from the object graph.** Vector Core's in-memory
back-edge walk is correct for a single process holding the whole graph, and insufficient the moment the
deployment is multi-pod: a pod that loaded a `Shelf` through its own repository holds no `Library`, so there
is no back-edge to walk and the library's summary silently keeps whatever some other pod last left. The
drain instead asks which containers *currently* hold the entity, from the declared `@Summary` fields of
registered types plus the stored relationships -- covering both natively-mapped associations (HQL) and this
backend's own `javai_collection_members` (SQL) -- and walks up transitively.

Two costs are accepted deliberately. A summary row is **stale within the caller's own transaction**, since
the write happens after commit. And a drain that fails leaves the recomputation queued rather than
performed -- durable, retried by the next save of that container or by `JavAIPI.drainPendingSummaries`, and
logged, but not instantaneous. `SummaryPolicy.QUEUE_ONLY` makes the second case a deliberate choice for
write-heavy paths.

Entities outside `@Summary` containment entirely -- which is most of an application's entities -- keep the
inline write and never touch the queue.

## Ordinary relational derived finders

A `JavAIRepository` interface may declare ordinary Spring-Data-style derived finders alongside the
`findNearestBy…Vector` convention, so a single repository serves both an entity's relational access paths
*and* its vector search — no parallel Spring Data JPA repository over the same table, and no `findAll()` +
in-memory filtering. This matters most for an entity that needs both: e.g. an `Identity` that is
`@JavAIVectorizable` (so it joins the semantic graph) yet is also looked up by `findByUserId`/`findByHandle`.
A **non-`@JavAIVectorizable`** `@Entity` is a first-class citizen of the same mechanism too — it just writes
no vectors on save; its finders resolve exactly like any other entity's.

```java
public interface IdentityRepository extends JavAIRepository<Identity> {
    List<Identity> findByUserId(UUID userId);          // ordinary relational finder
    Optional<Identity> findByHandle(String handle);
    boolean existsByHandle(String handle);
    List<Identity> findNearestBySummaryVector(EmbeddingVector reference, int limit); // vector convention
}
```

The method-name grammar is delegated to Spring Data's own `PartTree` (a self-contained name parser; no
Spring context or Spring Data JPA runtime is involved), so the full derived-query vocabulary is available:
`findBy`/`readBy`/`getBy`/`queryBy`/`countBy`/`existsBy`/`deleteBy`, `And`/`Or`, the operator set
(`GreaterThan`/`LessThan`/`Between`/`Like`/`Containing`/`StartingWith`/`In`/`IsNull`/`True`/`IgnoreCase`/…),
static `OrderBy`, `Top`/`First` limiting, `Distinct`, `Regex`/`Matches`, the collection-emptiness operators
(`IsEmpty`/`IsNotEmpty`/`Exists`), geo `Near`/`Within`, and the return-type adapters `List`/`Optional`/single/
`Stream`/`Page`/`Slice`/`long`(count)/`boolean`(exists). Dynamic `Sort`/`Pageable`/`Limit` parameters are
honored too. `PartTree` validates every referenced property against the entity type **by field, not requiring
JavaBean accessors**, so — like an invalid `findNearestBy…` — an unknown property or a mismatched parameter
count fails fast at repository-creation time, never on first call.

**Backend support and nested traversal (OMI-141).** All three backends translate finders into their native
query language (Postgres → JPA Criteria, Neo4j → Cypher, MongoDB → a driver filter). **Nested-association
paths** are supported through *both* singular associations *and* to-many/collection relationships
(`findByReviewsReviewer` — an entity by a field of its collection members). Each backend uses the mechanism
that fits its storage: Neo4j composes a self-contained `EXISTS { MATCH (n)-[:REL]->(x) WHERE … }` subquery
(correct through any cardinality and under `And`/`Or`); Postgres and MongoDB, whose related entities live
out-of-band (Postgres `javai_collection_members`; Mongo `{type, id}` reference pointers, not embedded),
resolve the matching related ids first and then match owners referencing them, expressed as `root.id IN (…)`.
Pure single-or-nested-*singular* scalar predicates stay a native Criteria join on Postgres. **Collection
emptiness** (`findByReviewsIsEmpty`) rides the same side tables/relationships. *Sort* is still limited to a
singular scalar path (a to-many sort is ambiguous). The id-set resolution materializes intermediate id sets
and issues a query per hop — fine for the Phase-0 goal of proving the design space; a single-statement
rewrite (a mapped membership entity for Criteria subqueries; `$lookup` for Mongo) is a natural later
optimization.

**Geo (`Near`/`Within`).** A `Point` field (Spring Data's `org.springframework.data.geo.Point`) round-trips
per backend — a Neo4j native `point`, a MongoDB GeoJSON `Point`, and (Postgres) two columns in a
`javai_geo_points` side table — and `findByLocationNear(Point, Distance)`/`findByLocationWithin(Circle)`
translate to `point.distance(…)` (Neo4j), `$geoWithin`+`$centerSphere` (MongoDB), and
`earth_distance(ll_to_earth(…))` (Postgres). The Postgres path uses the `cube`+`earthdistance` contrib
extensions — bundled with the official Postgres base image the pgvector image builds on — rather than PostGIS
+ `hibernate-spatial`, so geo needs no extra dependency and no test-container image swap; both give equivalent
great-circle point-distance. `Distance` is normalized to meters (kilometres/miles recognized).

**Value-conversion parity (Neo4j / MongoDB).** Both the Neo4j and MongoDB backends store non-primitive
scalars — `UUID`, `Instant`, `enum` — in a converted form (a `UUID`/`Instant` as its string form, an enum as
its `name()`), because that's what those stores hold natively for a JavAI field. A derived finder's bound
arguments are therefore run through the **same** conversion before they reach the query, so a comparison is
made against the identical stored form. This is a correctness requirement, not a nicety: without it,
`findByUserId(someUuid)` would bind a raw `UUID` against a column/property holding that UUID's *string* and
silently match nothing. The Postgres backend needs no such step — Hibernate binds Java types natively — which
is why the note is specific to the two reflective backends. (Range comparisons on a value stored as an
ISO-8601 string, e.g. an `Instant`, remain correct because ISO-8601 sorts lexicographically; comparisons that
are meaningless on a converted value, e.g. `>` on a UUID string, are permitted but not meaningful.)

## A JPA-style query, contrasted with an object-level query

A repository query (`findNearestByBodyVector`) is scoped to the whole persisted store; an object-level
`query()` (Vector Core) is scoped to one object's reachable graph. See whitepaper §6.6–§6.7 for the full
worked contrast and the comparison table for which one to reach for.

## Concatenated text: the entity-grain table holds two vectors (OMI-191)

`concatenatedTextVector()` (see `doc/spec/vector-core.md`) is **entity-level**, not field-level, so it is
stored on `javai_summary_vectors__<model>` — keyed `(owner_type, owner_id)`, exactly the right grain. The
field table is keyed `(owner_type, owner_id, field_name)`, so a per-entity value there would be null on every
row but one, with an arbitrary convention for which row carries it.

```sql
-- javai_summary_vectors__<model>
owner_type, owner_id, model_id, dims,
vector                        vector(N) NOT NULL,   -- summary vector, unchanged
concatenated_text             text        NULL,     -- new
concatenated_text_vector      vector(N)   NULL,     -- new
concatenated_text_computed_at timestamptz NULL,     -- new
computed_at                   timestamptz NOT NULL,
PRIMARY KEY (owner_type, owner_id)
```

**The table keeps its name.** Renaming it would be a migration bought for cosmetics. Read it as *entity-level
vectors*, of which the summary vector is one and the concatenated text vector another, as against the field
table's per-field grain.

**Three things worth knowing:**

- **The text is stored, not just its vector.** Text is model-independent, so re-embedding under a different
  model becomes a pure re-embed rather than a fresh walk of the object graph. Postgres TOASTs a `text`
  column automatically — compressed and stored out-of-line when large — which is the right behaviour for
  accumulated subtree text with nothing to configure.
- **The row now serves two independent opt-ins.** Write when *either* value is present; delete only when both
  are absent; write the concatenated columns as NULL when concatenation is switched off, so a stale text
  vector cannot outlive the opt-in and keep matching searches.
- **`ensureSummaryVectorTable` is `CREATE TABLE IF NOT EXISTS`**, which does nothing to a table that already
  exists — a pre-OMI-191 deployment would keep a three-column-short table and fail on first write. The three
  `ALTER TABLE … ADD COLUMN IF NOT EXISTS` statements alongside it are the migration, and are idempotent.

`vector NOT NULL` means a row cannot hold concatenated text without a summary vector. Reasoning says the two
always co-occur (text implies content, content implies a non-absent summary contribution), but that is
inference, so the write path **refuses loudly** if it ever meets the combination rather than silently
dropping the text or tripping a bare constraint violation. If it ever fires, the fix is to make `vector`
nullable, not to skip the write.

**Neo4j and MongoDB need no special handling.** Both already store `summaryVector__<model>` as a per-entity
property/field; `concatenatedText__<model>` and `concatenatedTextVector__<model>` are the same shape, and
neither has a grain problem to solve.

**Hydration matters more here than for a field vector.** A loaded entity's `summaryVector()` is arithmetic
over field vectors hydration already restored, so recomputing costs nothing; the concatenated text vector is
a real embedding, so not restoring it would mean a live model call on every load of every participating
entity. All three backends read it back into the entity's slot under the same pristine-slot rule that governs
field hydration.
