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
JavAIPI.repository(ConceptRepository.class, config); // register the node type first, same rule as any
                                                       // relationship target Neo4j needs to resolve by label
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
static `OrderBy`, `Top`/`First` limiting, `Distinct`, and the return-type adapters `List`/`Optional`/single/
`Stream`/`Page`/`Slice`/`long`(count)/`boolean`(exists). Dynamic `Sort`/`Pageable`/`Limit` parameters are
honored too. `PartTree` validates every referenced property against the entity type **by field, not requiring
JavaBean accessors**, so — like an invalid `findNearestBy…` — an unknown property or a mismatched parameter
count fails fast at repository-creation time, never on first call.

**Backend support and nested traversal.** All three backends translate finders into their native query
language (Postgres → JPA Criteria, Neo4j → Cypher, MongoDB → a driver filter). **Nested-association paths**
(`findByProfileHandle` — filtering an entity by a field of its *singular* related entity) are supported on
**Postgres** (Criteria auto-joins the `@OneToOne`) and **Neo4j** (a relationship traversal). They are not yet
supported through a to-many/JavAI-collection field on any backend, nor through MongoDB's `{type, id}`
reference pointers (which would need a `$lookup`); those cases are rejected clearly at repository-creation
time and are the subject of a planned follow-up (collection-membership join / Criteria-join deepening).

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
