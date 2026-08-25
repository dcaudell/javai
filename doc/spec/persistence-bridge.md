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
| `@Query` / `@Modifying` (+ `@Param`) | Declared query on a method | JPQL or native SQL a method carries itself, for what a derived name cannot ask — grouped aggregates, projections, and targeted single-column writes. Postgres only; see "Declared queries" below |
| `findBy<AnyField>OfType(Class)` | `@Any` discriminator predicate | Filters on a polymorphic to-one's target *type* without mapping its discriminator a second time as a shadow column. Postgres only; see "`@Any` predicates" below |
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
registered types plus the stored relationships -- resolved by HQL over the mapped association, singular or
to-many -- and walks up transitively. (It used to cover a second storage shape by SQL as well; that shape and
its table went in OMI-277, and this lookup lost the half that served it.)

Two costs are accepted deliberately. A summary row is **stale within the caller's own transaction**, since
the write happens after commit. And a drain that fails leaves the recomputation queued rather than
performed -- durable, retried by the next save of that container or by `JavAIPI.drainPendingSummaries`, and
logged, but not instantaneous. `SummaryPolicy.QUEUE_ONLY` makes the second case a deliberate choice for
write-heavy paths.

Entities outside `@Summary` containment entirely -- which is most of an application's entities -- keep the
inline write and never touch the queue.

## Batching: how many provider calls a write costs (OMI-266)

The accuracy rule above says a flush must never write a vector that disagrees with the field committed
alongside it. It says nothing about how many times the flush talks to the embedding provider to get them, and
the answer used to be *once per text*: a flush reads one field at a time, so lazy computation discovered one
text at a time and each was its own round trip.

Measured on the Postgres backend, before → after:

| Flow | Texts | Round trips before | after |
|---|---|---|---|
| `save()` — one entity, two `@Vectorize` fields | 2 | 2 | **1** |
| `save()` — a container and twelve members | 13 | 13 | **1** |
| `saveAll()` — twelve entities | 24 | *(no such method)* | **1** |
| `reindex()` — whole table under a new model | 98 | 98 | **1** |
| `inTransaction` — twelve `save` calls in a loop | 24 | 24 | 12 |

The texts are unchanged throughout — this is how many calls they arrive in, not how much work is done, and
OMI-187's "each distinct value embedded exactly once" invariant is untouched.

**Where the per-save half lives, and why nothing backend-specific changed.** All three backends already wrap
every `save()` in `JavAIRuntime.runWithSubgraphLockedForPersistence`, which had already computed the entire
reachable subgraph in order to lock it. It now warms that set — one batched pass — before running the flush.
So one change in Vector Core covers Postgres, Neo4j and MongoDB, and no backend's own write path was touched.

**This shortens more than latency.** Those round trips happen while every object in the subgraph is locked
against mutation, and on Postgres and Neo4j *inside the open database transaction*. Thirteen sequential calls
to an embedding provider hold the object graph frozen, and hold a transaction open, for thirteen times as long
as one batched call. That is contention and serialization-failure surface, which is why this is worth doing
inside `save()` rather than only offering a bulk API next to it.

### `saveAll` — batching across entities

A `save()` sees only its own subgraph, so a loop of saves stays one round trip per entity no matter how well
each one batches internally. `JavAIRepository.saveAll(Iterable<T>)` is the seam that can see the whole batch:

```java
articles.saveAll(fleet);                             // one batched round trip, then the writes
articles.saveAll(fleet, SummaryPolicy.QUEUE_ONLY);   // same, deferring the @Summary recomputation
```

**Atomicity is Postgres-only, exactly as with `JavAIPI.inTransaction`.** There the whole batch is one
transaction. Neo4j and MongoDB write each entity independently, so a failure part-way leaves the earlier ones
saved. They are deliberately *not* made to refuse the call the way `inTransaction` does: refusing would deny
them the batching, which is the thing this method primarily exists to provide, for the sake of a guarantee it
secondarily provides.

On Postgres the embeddings are computed **before** the transaction opens. Nothing is locked at that moment, so
a field mutated between the warm and its `save()` simply leaves its slot dirty and that save's own locked,
accuracy-forced pass recomputes it — the guarantee at the top of this section is preserved, and a bulk write
no longer holds a transaction open across a sequence of network calls.

`reindexAll()`/`reindex()` run the same warm chunk by chunk (100 entities at a time) rather than in one pass:
a re-index is the one operation guaranteed to touch every row, so gathering the whole table's texts before
issuing any request would trade a latency problem for a memory one. It deliberately does not route through
`saveAll`, because a maintenance pass over an entire table has no business widening the transaction boundary
its per-entity saves already have.

**One flow is deliberately left unbatched:** a caller looping `save()` inside `JavAIPI.inTransaction` still
pays one round trip per entity. Batching across a transaction whose body is an opaque lambda would mean
deferring every vector write to commit time — across three backends, and through the `javai_summary_pending`
queue above. `saveAll` is the answer for that shape. The cost of not doing it is pinned by a test rather than
left as a footnote.

## Attachment: one graph, one state (OMI-275)

A repository call returns a graph that is uniformly attached or uniformly detached, never mixed. Outside a
unit of work everything is detached; inside one, the root and every node reached through it are managed --
through a lazy hop, an eager one, `@OneToOne`, `@ManyToOne`, `@OneToMany`, `@ManyToMany`, `@Any`, and a
self-reference alike.

**A mixed graph is the outcome worth designing against**, which is why it is stated as a property rather than
left to fall out. Nothing about an object tells a caller which kind of node they hold, so in a mixed graph the
same traversal works or throws depending on where they landed, and mutations are silently persisted in one
place and silently discarded in another. No rule a caller could learn covers it.

There was exactly one route to one: `save()` used to return the caller's own instance, which is never
managed, while Hibernate tracked a merged copy. Give that unmanaged root a child the caller had loaded in the
same unit of work and the result was an unmanaged root holding a managed child.

**So `save()` returns the managed instance now, as Spring Data JPA's does.** It could not before: `merge()`
leaves `<transient>` fields empty on the managed copy, and JavAI collections used to be transient. OMI-277
made them native associations that `merge()` carries across, leaving only `Point` fields, which `save` copies
across explicitly.

Two consequences to know:

- **Inside a transaction, mutating what `save` returned is dirty-checked.** Before, it was not.
- **The returned graph is a different object graph from the one passed in** -- ordinary `merge` semantics.
  After a save, use what `save` returned *or* your own instance, but do not mix them and expect the same
  objects.

**An uninitialized proxy answers its `@Id` without a round trip only if the identifier getter is `public`.**
Hibernate serves it by overriding that getter and cannot override a package-private one, so the call falls
through to the uninitialized instance and triggers a load -- which on a detached entity turns a free read into
`LazyInitializationException`. Nothing warns about it.

## A read loads what was read, not what is reachable from it (OMI-271)

The section above is about not re-embedding what a load already knows. It says nothing about how much the
load itself costs, and the answer used to be *everything reachable*: both post-load steps walked the loaded
root's graph reflectively, and that walk called `addAll` on every collection-valued field. **Iterating an
uninitialized `PersistentCollection` is initializing it**, so reading one scalar off one entity loaded its
whole reachable collection graph, recursively, plus a side-table SELECT per entity in it.

| Read | Entities loaded, before | After |
|---|---|---|
| a root with five children, one string asked for | 6 | **1** |
| a three-level graph of sixteen, one string asked for | 16 | **1** |

Laziness *was* enforced for **singular** associations, and this is worth stating because it is why the gap
survived: an uninitialized proxy is a generated subclass, `@Entity` is not `@Inherited`, so the walk's
`isAnnotationPresent` test rejected it — by accident rather than by design. A lazy `@OneToMany`/`@ManyToMany`
had no such accident protecting it.

**A repository therefore returns a genuinely detached entity now**, and traversing an association nobody
touched raises `LazyInitializationException` like any other lazy dereference — the behaviour
`doc/spec/vector-core.md`'s persistence rules and this project's own e2e tests already describe for singular
associations. Read inside a unit of work when the graph is genuinely wanted, and pay for the hops taken:

```java
Library library = JavAIPI.inTransaction(config, () -> {
    Library loaded = libraries.findById(id).orElseThrow();
    Hibernate.initialize(loaded.getShelves());
    return loaded;
});
```

**Every piece of out-of-band state is served from Hibernate's `POST_LOAD` event, in one read (OMI-276).**
Three things live outside an entity's own table -- each `@Vectorize` field's vector, the entity-grain
concatenated text vector, and any `Point` field -- and they were originally fetched by different mechanisms
at different times. Geo kept its own recursive walk, which is what made it the one that broke: the walk ran
before a caller could initialize anything, so a `Point` on an entity reached through an association was
silently never read. All three now come from one `UNION` per entity, over the tables that apply to it and
exist, with existence memoised. One statement per entity, and the count does not scale with how many
`@Vectorize` or `Point` fields that entity has.

**Stored vectors are served from Hibernate's `POST_LOAD` event instead of from a walk.** That is what makes
the removal free rather than a trade: the cost becomes one SELECT per entity *actually loaded*, and it covers
a case no walk at load time could — a member the caller initializes afterwards, which arrives long after any
walk has finished. `save()` suspends it for its own unit of work, since `merge()` loads the row *before*
copying the caller's values onto it, and hydrating there would pair the old vector with the new value.

Vector Core has the same hazard one layer up — its own walks must not touch a value whose resolution would
perform I/O — and cannot recognise one without depending on an ORM. `JavAIRuntime.configureInitializationCheck`
is the seam: the Hibernate backend installs `Hibernate::isInitialized`, and the default answers `true` for
everything, which is right for a plain object graph with no persistence layer under it.

**There is now exactly one JavAI collection mapping (OMI-277).** A JavAI collection field must be declared by
the *interface* (`JavAIList`/`JavAISet`/`JavAIMap`), non-final, with the ordinary JPA annotation; Hibernate
then substitutes `PersistentJavAIList`/`Set`/`Map`, and the field is an ordinary lazy association with vectors
and dirty-tracking intact (see OMI-142 for how that substitution works).

A *concrete*-typed field (`private final JavAIArrayList<X>`) is refused at registration. It used to be a
second, out-of-band mapping through `javai_collection_members`, and it was withdrawn rather than repaired
because that storage was only ever read and written for the entity a repository call **returned**: reached
through an association the collection came back silently empty, and saved through one its members were
silently never written. Neither failure announced itself.

It could not be made lazy where it stood, and the reason is worth recording because it is the whole argument.
The field holds a `final` instance of a `final` class that the entity's own constructor created; Hibernate
manages a collection by substituting its own instance, which a final class forbids. Laziness would therefore
have had to live *inside* `JavAIArrayList`/`JavAILinkedHashSet`/`JavAILinkedHashMap`, as a pending load
triggered from every read — and `ArrayList`'s read surface has no single funnel, so a missed override returns
an empty collection, which is precisely the defect being fixed. Doing it properly would have required an
interface-typed, non-final field: exactly what the native mapping already requires, at which point the second
mapping has no reason to exist.

The membership table and every path that touched it have been **removed**: an unclaimed table created in
every database on every boot, plus code nothing could reach, is vestigial rather than reversible. Nothing
drops an existing one — a database that has it keeps it, empty, until somebody drops it by hand.

One live path was rebuilt rather than deleted. A geo predicate nested through a to-many hop used the
membership table to map member ids back to owner ids; it resolves through an HQL join over the association
now, which is what that hop always was once the collection became native.

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
(correct through any cardinality and under `And`/`Or`); MongoDB, whose related entities live out-of-band as
`{type, id}` reference pointers rather than embedded, resolves the matching related ids first and then matches
owners referencing them, expressed as `root.id IN (…)`. **Postgres is a native Criteria join throughout** —
singular or to-many, since OMI-277 left it one collection shape and that shape is a mapped association. The
id-set-per-hop path it used to need for the other shape is gone with it; the one Postgres predicate still
resolved that way is **geo**, whose `javai_geo_points` is not a table Hibernate can join. **Collection
emptiness** (`findByReviewsIsEmpty`) rides the same mechanisms. *Sort* is still limited to a singular scalar
path (a to-many sort is ambiguous). Where id-set resolution survives it materializes an id set and issues a
query per hop — fine for the Phase-0 goal of proving the design space; `$lookup` is the natural later
optimization for Mongo.

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

## Declared queries: `@Query` and `@Modifying` (OMI-398)

A derived name can express a predicate over an entity's own properties, and nothing else. A **grouped
aggregate** is the plainest thing it cannot: "how many rows does each of these thirty ids have" is one
`GROUP BY`, while `countBy…In` returns a single total. That left N+1 counts, counting in memory, or reaching
past the repository to `JavAIPI.sessionFactory(config)` — and it is the last one this exists to stop being
the answer.

```java
public interface LikeRepository extends JavAIRepository<Like> {

    @Query("select new com.example.LikeCount(l.targetId, count(l)) from Like l "
            + "where l.targetId in :ids group by l.targetId")
    List<LikeCount> countsByTarget(@Param("ids") Collection<UUID> ids);

    @Modifying
    @Query("update MediaSocialDetails d set d.likeCount = d.likeCount + 1 where d.id = :id")
    int incrementLikeCount(@Param("id") UUID id);
}
```

`@Query`/`@Modifying` are JavAI's own (`javai-annotations`); **`@Param` is reused** from
`spring-data-commons`, already a dependency, the same reuse-not-reinvent choice `@Id` makes. `spring-data-jpa`
is not a dependency, and taking on a whole repository framework to borrow one annotation is not worth it.
`DeclaredQuery` sits beside `DerivedFinderQuery` and reuses its shape — parse, signature analysis,
return-type adaptation, binding here; three primitives in each backend — including its `Constraints` record,
so ordering and windowing mean the same thing whichever path produced them.

**Postgres only.** Neo4j and MongoDB refuse at repository-creation time, and the SPI defaults *throw* rather
than accept, following `inTransaction`'s precedent: a JPQL string means nothing to either store, and
translating one would be a query engine rather than a shim.

**Returns**: entity, `Optional`, single, `Stream`, scalar, `Object[]`, and a record. The last two needed no
projection machinery — Hibernate 7 instantiates a record from an ordinary `select new …` constructor
expression and hands back a tuple as `Object[]`. `Page` requires `countQuery = "…"`, which is **not** derived
by rewriting the query: counting an arbitrary select means understanding its projection, joins and grouping,
and a rewriter that gets that wrong returns a plausible wrong number instead of failing. `Slice` needs none,
since it fetches one extra row.

**A dynamic `Sort` is applied through Hibernate's `SelectionSpecification`, never by editing the query text.**
It therefore works only on an entity-returning JPQL query — there is no attribute to order a projection by —
and a native query refuses one outright, since applying it there could only mean rewriting SQL. A `Pageable`'s
*window* needs no rewriting and is honoured on both.

**Vector hydration is free.** Hibernate's `POST_LOAD` fires for HQL and native entity queries alike, so an
entity a declared query returns arrives with its stored vectors already in its slots, exactly as `findById`
does. Measured at the embedding provider, not inferred.

**A declared query cannot share an interface with a backend that refuses one.** The refusal happens when the
repository is *realized*, not when the method is called, so a `@Query` on an interface that is also handed to
`JavAIPI.repository(..., neo4jConfig)` fails that whole repository -- including the methods that would have
worked. If an entity is served from more than one backend, keep its declared queries in their own
Postgres-only interface; both proxies are independent, and the same entity is reachable through either. This
is what `ArticleQueryRepository` beside `ArticleRepository` looks like in `e2e-client-test`.

### When a declared query is validated

Every other creation-time check in this module is pure reflection. Parsing a query is the first that is not:
it needs Hibernate's query engine, and building that engine freezes the entity set — precisely what OMI-214
arranged should *not* happen when a repository is realized. So validation splits, and this is a property to
know rather than an accident:

- **Signature, at repository-creation time** — `@Param` names against the query's own, parameter counts,
  return-type feasibility, trailing-parameter placement, `Page` without a `countQuery`.
- **Query text, as early as the ORM allows** — immediately when the `SessionFactory` already exists,
  otherwise queued and translated the moment it is built. Either way it fails before any repository method
  runs, never on the first call of the annotated one.

Native SQL is the exception, stated plainly: there is no SQL grammar in this module, so a native statement is
parsed by the database on first execution. Its *parameter binding* is still checked at creation time.

**A query is not a registration.** Naming an entity in query text does not map it; a type reachable no other
way must be named on the configuration (`entityType(...)`/`entityPackages(...)`). The error says so.

### `@Modifying`, and what it is not allowed to touch

`save(entity)` persists the whole row, which makes a single-column write inexpressible and any column
maintained *outside* the entity's own editing path clobberable — load a row before some counter moved, edit
something unrelated, save, and the stale counter goes back with it. A repository handing out detached entities
is exactly the shape that goes stale. It is also the only way to get an atomic `SET c = c + 1`.

**Point 5 of the ticket needed no new annotation.** Measured: JPA's own `@Column(updatable = false)` already
protects a column from `save()` (7 stays 7 through a save of a mutated detached entity) while a `@Modifying`
query writes straight past it (7 → 8). Declare the column with the JPA annotation and write it through a
targeted method; JavAI adds no marker of its own.

**A bulk write that would break Vector Core's mutation rule is refused, not documented.** It fires no woven
accessor, so nothing recomputes what it invalidated — and since OMI-187 a stored vector is hydrated straight
back into a loaded object's slots, so the inconsistency is served on every later load rather than lasting one
process. `SqmUpdateStatement`'s set clause is readable without a session, so the check is static and happens
when the repository is realized. Refused assignments, resolved against **the statement's own target entity**
rather than the repository's type parameter — so a repository over a plain entity is not a way around it:

| Assigned to | Why it cannot be allowed |
|---|---|
| `@Vectorize` field | its embedding keeps the value the field had before the write |
| `@ExternalVector`'s `keyField` | the vector supplied for the old content goes on being served for the new |
| `@Summary` field | reassigning it moves containment; both containers' summary vectors are wrong, nothing enqueued |
| `@Taggregate` field | the same drift one layer up, invisible to tagging's own reconciliation |

An **ordinary column on a vectorized entity stays writable**, and that half matters as much: a summary is
arithmetic over vectors, so a column no vector reads cannot move one. A guard that refused these too would
have blocked the feature on any entity carrying an embedding.

**`nativeQuery = true` is refused outright when JavAI owns storage for the type** (it is `@JavAIVectorizable`,
or declares a `Point`). The check above reads a parsed statement's assignments and SQL has none to read, so
the repository is the only signal there is — coarse on purpose, and better than a rule that lives in a
document and is therefore never enforced. Native writes on a type JavAI keeps nothing for are ordinary.

**A `@Modifying` delete resolves the matching ids and deletes each through `deleteById`'s path**, for the same
three reasons `deleteBy…` already does: a bulk `delete` cascades to nothing, detaches from no container (so a
join table's foreign key refuses it), and leaves `javai_vectors__*`/`javai_geo_points` rows orphaned — and a
stale vector row is worse than an orphan, since it keeps matching similarity searches for an entity that no
longer exists. One extra statement buys a deletion path that is already correct and already tested.

**A bulk update does not increment `@Version`** unless the query says `update versioned`. Both spellings are
pinned by tests.

## `@Any` predicates, without shadow columns (OMI-407)

An `@Any` cannot be *joined* through, and that had been taken to mean it could not be *filtered* on either —
so the discriminator and the foreign key were mapped a second time as plain read-only columns purely to give a
derived finder something to see, duplication every queryable `@Any` would repeat and two mappings free to
drift. Measured on Hibernate 7, the premise is false for predicates: `Path.type()` resolves straight to the
discriminator column, and ordinary equality straight to the pair.

```java
List<Like> findByTarget(Likeable target);            // by instance -- no new grammar; @Any is a real field
List<Like> findByTargetIn(Collection<Likeable> targets);
List<Like> findByTargetIsNull();

List<Like> findByTargetOfType(Class<?> targetType);  // by discriminator alone -- one new keyword
List<Like> findByTargetOfTypeIn(Collection<Class<?>> targetTypes);

likes.nearestBy("caption").to(reference).where("target").ofType(MediaAsset.class).limit(20).ranked();
```

`OfType` is the one keyword added to `PartTree`'s closed vocabulary. It is stripped before the tree is built
and re-attached to the matching part afterwards, and the match is made **by counting parts**, not by
re-tokenizing the name — so a property whose name contains another's cannot be miscounted. Each occurrence is
attributed to the **longest** `@Any` field name ending where it begins, which is load-bearing rather than
defensive: one `@Any` field's name is frequently a suffix of another's (`lazyAny` inside `summaryLazyAny`),
and the character before the token is lowercase in both `findBy|LazyAny` and `Summary|LazyAny`, so nothing
short of longest-match separates them. The single
ambiguous shape (one `@Any` property named twice with the keyword on only some) is refused rather than
guessed. The same stripping runs on the vector convention's narrowing tail, so the two grammars cannot mean
different things by it.

Refusals stay refusals, with messages that now name `@Any` rather than "not a singular @Entity": traversing
*into* one (`findByTargetLabel`), sorting by one, and any operator beyond equality/`In`/`IsNull` — there is no
text in a discriminator-and-key pair to match. **Postgres-only for free**, since Neo4j and MongoDB already
refuse `@Any` fields at registration.

## Vector search combined with a relational predicate (OMI-230)

`findNearestBy…Vector(reference, limit)` used to be the whole vector surface, which made *"the nearest N that
**also** satisfy X"* inexpressible. The only recourse was to over-fetch and discard — unboundedly, since the
ratio depends entirely on the data, and blindly, since the ranking information that would have said whether
to fetch more was thrown away with the results. Three things close that, in **two idioms that compile to the
same query** (`NearestSpec`), so neither can answer differently from the other:

```java
public interface MediaNoteRepository extends JavAIRepository<MediaNote> {
    // 1. a predicate: everything after Vector is parsed by the same PartTree the findBy… finders use
    List<MediaNote> findNearestByCaptionVectorAndKindIs(EmbeddingVector reference, int limit, Kind kind);

    // 2. a ranked return: each hit keeps the similarity it was ranked on
    List<Ranked<MediaNote>> findNearestByCaptionVectorAndKindIs(
            EmbeddingVector reference, Kind kind, Limit limit);

    // 3. paging: a trailing Pageable supplies the window *and* the offset, so no int limit is declared
    List<MediaNote> findNearestByCaptionVector(EmbeddingVector reference, Pageable pageable);
}

// ...or, for a predicate composed at runtime, the builder — same mechanism, no method to declare:
List<Ranked<MediaNote>> hits = notes.nearestBy("caption")
        .to(reference)
        .where("kind").in(Kind.IMAGE, Kind.SHORT)
        .and("published").isTrue()
        .offset(20).limit(20)
        .ranked();
```

**The contract, and the whole point: the limit applies *after* the predicate.** "The nearest N that also
satisfy X" is a different question from "the ones among the nearest N that satisfy X", and only the first is
answerable without over-fetching. A backend that cannot honor that ordering **refuses the query** rather than
approximating it (`RepositoryBackend.validateNearestQuery`) — for the method-name idiom at repository-creation
time, and for the builder when it runs, since its predicate does not exist until then.

Which is why **Neo4j refuses to narrow**, and this is a property of the store rather than a gap: 
`db.index.vector.queryNodes` chooses its K nearest before Cypher can see them, so a predicate could only ever
be applied to the index's output. That returns fewer than the requested limit whenever the predicate excludes
anything — exactly the over-fetch this feature removes, relocated inside the library where the caller can no
longer see it happening. Postgres resolves the predicate to an id set and ranks within it; MongoDB hands that
id set to `$vectorSearch`'s own `filter`, which is a genuine pre-filter. Ranked results and paging work on all
three: neither needs anything a top-K index lacks.

`Ranked.similarity()` is **plain cosine in `[-1, 1]` on every backend** — the same number
`VectorMath.cosineSimilarity` and `similarityTo` return in process. That is a deliberate normalization, not a
passthrough: pgvector reports cosine *distance*, while Neo4j and MongoDB both report `(1 + cosine) / 2`. Each
backend converts as it reads its own result, so a threshold written once means the same thing whichever store
answers it.

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
vector                        vector(N)   NULL,     -- summary vector; NULL-able since OMI-435
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

### ⚠️ `vector` is NULL-able, and the reasoning that said it need not be was wrong (OMI-435)

This column was `NOT NULL`, on the inference that the two values always co-occur: text implies content,
content implies a non-absent summary contribution. The write path **refused loudly** when it met the
combination, and the note here said that if it ever fired, the fix was to make the column nullable rather
than to skip the write.

It fired, and that was the fix. **A concatenated text vector with no summary vector is a real state**: an
entity that loses its last `@Vectorize` content has an absent summary immediately, while a concatenated
vector computed earlier is still on file and is served back by hydration on the drain path. The pair reaches
the writer through no fault of the entity.

Refusing it cost far more than it caught. On the inline path the whole save rolled back — an owner's edit
lost to a vector-bookkeeping constraint — and on the drain path the recomputation requeued forever, leaving
the container's summaries stale. The row now records what is true: this text, and no summary. `rankIds`
already skips NULL vectors, so a half-populated row is invisible to a summary search and findable by a
concatenated-text one.

⚠️ **A database created before this keeps its `NOT NULL`, and that is the adopter's to fix.** JavAI has no
concept of a migration — it provisions a table it finds missing rather than altering one it finds present,
and an `ALTER` issued from the write path would need an `ACCESS EXCLUSIVE` lock on a table the calling
transaction already holds a lock on, which deadlocks the request that triggered it. One statement per
existing summary table:

```sql
ALTER TABLE javai_summary_vectors__<model> ALTER COLUMN vector DROP NOT NULL;
```

### ⚠️ A reference from another model is refused, not answered empty (OMI-458)

The concatenated text vector is **one embedding of one assembled string, produced by the configured
provider** — so it exists in that provider's model and in no other, for every participating entity, by
construction. A search whose reference comes from anywhere else is asking for something nothing can hold.

That case used to return an empty list. Empty is a lie here, and a consistent one: it is exactly what a
corpus with no near matches returns, so the query looks answered, and looks answered the same way every time
it is run. It is the one place a wrong model produces a plausible non-answer rather than a missing table,
which is why it is the one place that has to say so out loud. All three backends refuse it, through both the
builder and the `findNearestByConcatenatedTextVector` idiom, and the message points at `nearestBySummary()` —
which *does* serve that model, and is the likely intent when someone reaches here with an image vector.

**There is no fold to fall back to**, unlike a summary search: folding needs a value that exists to be
folded, and this one does not exist in that model for anything. Silent when the provider cannot name its own
model — there is nothing to compare against, and a refusal derived from an unknown is worse than the search
it would block.

**Neo4j and MongoDB need no special handling.** Both already store `summaryVector__<model>` as a per-entity
property/field; `concatenatedText__<model>` and `concatenatedTextVector__<model>` are the same shape, and
neither has a grain problem to solve.

**Hydration matters more here than for a field vector.** A loaded entity's `summaryVector()` is arithmetic
over field vectors hydration already restored, so recomputing costs nothing; the concatenated text vector is
a real embedding, so not restoring it would mean a live model call on every load of every participating
entity. All three backends read it back into the entity's slot under the same pristine-slot rule that governs
field hydration.

## Externally-supplied vectors (OMI-290)

`@ExternalVector` (see `doc/spec/vector-core.md`) is stored at the **same per-field grain** as a
`@Vectorize` field, and partitioned by the model the type *declares* rather than the one currently
configured — the two have nothing to do with each other. That is the whole of the storage design; no new
table, no new grain, no new keying rule.

| Backend | Realization |
|---|---|
| Postgres | `javai_vectors__<model>`, with a new `computed_for text NULL` column holding the content key. `ALTER TABLE … ADD COLUMN IF NOT EXISTS` alongside `ensureFieldVectorTable` is the migration for a table created earlier — idempotent, same pattern as OMI-191's three columns |
| Neo4j | `<name>Vector__<model>` node property, plus `…ComputedAt` and `…ComputedFor` siblings — the same shape as `summaryVector__<model>` |
| MongoDB | `<name>Vector__<model>` document field, plus the same two siblings |

**`computed_for` earns its place on the load path, not the query path.** After hydration the vector sits in
its cache slot, and every read compares the key it was written for against the entity's current content
before serving it. Without the stored key, a hydrated vector would be held and never served — which from
outside is indistinguishable from a pipeline that never ran. The backlog query is a bonus, not the reason.

**Reads hydrate it before the `currentModelId()` guard**, deliberately: an external vector's model is
declared on the type, so it is readable whether or not a text provider is configured or can name itself.

**A save deletes the row when the vector reads absent** — nothing supplied yet, or superseded by a content
change — for the same reason `AbsentVectorRemovalTest` gives for a `@Vectorize` field that loses its content.
A stale row in an ANN index is worse than a missing one: the entity goes on matching searches for content it
no longer has.

**`reindex` carries them across untouched.** A re-index re-embeds under the currently configured model, and
this vector is neither in that model nor computable by this process — so it is neither recomputed nor
dropped.

### The repository surface

```java
boolean supplyVector(UUID id, String vectorName, EmbeddingVector vector, String computedFor);
List<T>  findPendingVector(String vectorName, int limit);
```

`supplyVector` reads the entity — the content-key comparison is against the entity's own field, so there is
nothing to compare without it — and then writes exactly one vector: no merge, no summary recomputation, no
walk of the reachable graph, because a consumer storing a vector has not touched the entity. It returns
`false` rather than throwing when the entity has moved on, which under at-least-once delivery is ordinary.

`findPendingVector` is a **scan**, stated plainly: it needs no store-specific code, is correct on all three
backends, and is the right shape for the two jobs it exists for — a backfill and a re-drive after a
dead-letter drain — both of which sweep the whole type anyway. A backend can override it with an anti-join
against its own vector storage if that stops being true.

### Searching one

`findNearestBy<Name>Vector(reference, limit)` and `nearestBy("<name>")` accept an `@ExternalVector`'s name
exactly as they accept a `@Vectorize` field's. Nothing in the query machinery had to learn a new concept:
every backend already resolves *which* storage answers from `reference.modelId()`, so a reference from the
image model selects the image model's table/property by the mechanism that was already there.

## ⚠️ A read never provisions storage (OMI-458)

**Reading is not a reason to create anything.** `findNearest` used to resolve its table by calling
`ensure*VectorTable(reference.modelId(), reference.dims())`, so a search whose reference named a model
nothing had ever been written in *created that model's table* — two HNSW indexes and all — and then returned
no rows, because there were none to return. A failed query left a permanent, empty, indexed table in the
adopter's schema, and every repetition of a typo'd or mismatched model id minted another.

The rule now, on all three backends:

| | Who provisions | What a read does when it is absent |
|---|---|---|
| Postgres | the write path, which knows a vector exists to store | resolves the table name, finds it absent, answers empty |
| Neo4j | ⚠️ the read — it is the only trigger, since writes set properties and never create indexes | creates the index **only if some node actually carries the property**; otherwise answers empty |
| MongoDB | ⚠️ the read, same reason | creates the search index **only if some document actually carries the field**; otherwise answers empty |

Neo4j and MongoDB could not simply stop creating — nothing else ever would, and vector search would break
outright. So they ask one bounded question first (`… IS NOT NULL … LIMIT 1`, `{$exists: true}` limit 1) and
create when there is something to create it for. That also removes a second cost those two carried: both
*block the caller* while a newly created index comes online, so a junk index was a junk wait as well.

`QueryTimeSchemaCreationTest` pins the rule rather than the bug: it inventories every table and index,
runs every shape of read each backend can serve against an unwritten model, and requires the inventory to be
unchanged. A future read path that provisions anything fails there without anyone having predicted it.

## Persisted per-model summary vectors (OMI-458)

`summaryVector(String modelId)` computes a container's summary restricted to one embedding model — what its
subtree *looks* like, as against what it *reads* like. Storing it was the missing half: the entity-grain
writer asked for `currentModelId()` and the unscoped `summaryVector()`, so a model arriving only through
`@ExternalVector` — image pixels, an audio waveform — had a computable container summary and no row anywhere.
Ranking a corpus by it therefore meant folding **every** candidate in memory, at O(containers × their
members) per query.

Opt in on the container **type**:

```java
@Entity
@JavAIVectorizable
@Summary(persistModelSummaries = true)     // ← the whole opt-in
public class Album {
    @Summary
    private JavAISet<Image> images;        // Image declares @ExternalVector(model = "siglip2…")
}
```

```java
albums.nearestBySummary().to(reference).limit(10).ranked();                        // reference names the model
albums.nearestBySummary().inModel(Image.PIXELS_MODEL).to(reference).limit(10);     // …and says so out loud
```

**Which models is derived, never configured.** Every `@ExternalVector(model = …)` reachable from the
container through `@Summary` fields, **transitively**, minus the ambient one (which the ordinary path already
writes). Read from declarations rather than from what happens to be in a table: an un-embedded corpus and a
corpus with no such model are indistinguishable in storage, so the second would silently and permanently stop
writing rows the first is merely waiting for. The declared element type is expanded to its registered
subtypes, since a `@Summary` collection typed to a `@MappedSuperclass` whose subclass carries the declaration
is the ordinary adopter shape.

**Transitive, because containment is.** `Exhibition → Album → Image` is one `@Summary` chain, and the
exhibition's pixel summary is a real vector — `summaryVector(modelId)` recurses and `VectorMath` skips the
absent terms at each tier that carries nothing of its own. A walk stopping at the first hop would store the
album's row and leave the exhibition doing the in-memory fold this exists to remove.

**Storage is the table that was already there.** `javai_summary_vectors__<model>`, keyed
`(owner_type, owner_id)`, provisioned by `ensureSummaryVectorTable` with its `hnsw (vector vector_cosine_ops)`
index — so a new model's table arrives fully indexed with no migration. The concatenated columns are written
**absent**: concatenated text is one embedding of one assembled string, so it exists in the configured
provider's model and no other, and carrying it across would file a text embedding under an image model. They
are assigned rather than skipped, per the writer's standing rule, so nothing a previous configuration left
there survives.

**Nothing here can trigger an embedding.** `summaryVector(modelId)` skips a `@Vectorize` field *without
reading it* when `modelId` is not the configured provider's, and every model this writes is by definition not
that. The added cost is arithmetic over vectors already in hand plus one upsert per model.

### ⚠️ Invalidation happens at `supplyVector`, not at `save`

This is the part that is easy to get wrong, and getting it wrong produces a row that is correct exactly when
it is empty. **An `@ExternalVector` arrives after the save, by construction** — the model runs in its own
container behind a queue and answers seconds or minutes later — so every container above the entity was
summarised at a moment when there was nothing in that model to summarise. A writer that only ran on `save`
would never see the data arrive.

So `writeExternalVector` enqueues the entity on `javai_summary_pending` and drains after commit, reusing
OMI-255's mechanism rather than adding a second. The drain's own upward walk does the rest: it skips the
entity (not a container, nothing of its own to recompute) and walks one hop at a time against *committed*
state, so a tier above the container is reached too. Duplicates are expected and collapse by owner, which is
what makes a burst of thousands of supplied vectors cost one recomputation per container.

The enqueue is **gated on some registered type actually opting in**. Without that gate every `supplyVector` in
an application that never asked for this would pay a queue row and a drain for a row nothing would read.

### The query answers whether or not the rows exist

**Nothing new is needed to search one.** Every backend already resolves *which* storage answers from
`reference.modelId()`, so `nearestBySummary().to(pixelSummary)` reaches the pixel table by the mechanism that
was already there, and so does the derived `findNearestBySummaryVector(pixelSummary, 10)` — model ids are not
Java identifiers, and the method-name idiom needs none. Ranking across two embedding spaces is therefore not
a hazard here: the index is *derived from* the reference rather than chosen beside it, so the two cannot
disagree.

`NearestQuery.inModel(String)` exists for the hazard that is real, which is the caller's:

```java
albums.nearestBySummary().to(album.summaryVector());          // the text one
albums.nearestBySummary().to(album.summaryVector(PIXELS));    // the pixel one
```

A container carrying both a `@Vectorize` field and an `@ExternalVector` has *two* coherent summaries, and
those two lines differ by one token. Both compile, both run, both return sensible hits against their own
storage — so passing the wrong one answers the **other** of the container's two questions with nothing to
notice. `inModel(...)` names which was meant and refuses a reference from anywhere else.

⚠️ **It is an assertion, not a selector**, and that is why it is optional and why it is refused on every other
grain. A field-grain search names the vector, and an `@ExternalVector`'s model is fixed by its own
declaration; the combined vector and the concatenated text vector exist in the configured provider's model
and no other. On those there is no second answer for a qualifier to give, so accepting one would invite the
belief that it does something.

When the type has **not** opted in, the same question is answered by folding the candidates in memory: the
same value, the same ranking, at a cost proportional to the corpus. That fallback exists because the
alternative is not a slower answer but a wrong one — `ensureSummaryVectorTable` would provision an empty table
and the search would return nothing, which reads as *"nothing is similar"* rather than *"nothing is stored"*,
and a caller cannot tell those apart. ⚠️ **Which path runs is decided from the declaration, never from whether
the table holds rows**, so a deployment mid-backfill does not silently change both its cost and its answer
partway through. The fold logs once per `(type, model)`: a fallback nobody can see is indistinguishable from
an index, right up to the corpus size where it is not.

| | Opted in | Not opted in |
|---|---|---|
| `save` / drain | one upsert per declared non-ambient model | nothing extra |
| `supplyVector` | queue row + drain | nothing extra |
| `nearestBySummary()` in that model | indexed lookup, HNSW | in-memory fold, O(corpus) |
| Answer | identical | identical |

**Backfill is `reindex()`**, which already runs `QUEUE_ONLY` then drains — the drain recomputes each container
from committed state and writes every model's row, so turning the flag on needs no separate migration.

⚠️ **Turning the flag back off, or removing a model from a subtree, leaves the rows behind.** JavAI provisions
what it finds missing rather than altering what it finds present, and it has no record of models a container
*used to* declare. Deleting the entity still clears every table, since `deleteById` sweeps the catalog rather
than a remembered list.

**Neo4j and MongoDB** store `summaryVector__<model>` as a per-entity property/field already, so the shape
generalizes — but their writers remain single-model in this phase. Both answer a non-ambient summary search by
folding, through the same shared SPI default, rather than returning the empty result an unwritten property
would give.
