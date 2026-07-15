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
| Hibernate-based enhancement shim | Mechanism | ByteBuddy enhancement via Hibernate's `EnhancementContext`-style SPI |
| `hibernate-vector` module | Dependency | Native pgvector column mapping (`@JdbcTypeCode(SqlTypes.VECTOR)`) |
| Neo4j-facing shim | Mechanism | Parallel graph-native persistence backend, same `JavAIPI` contract |
| MongoDB-facing shim | Mechanism | `RepositoryBackendSpringDataMongo` -- Spring Data MongoDB's `MongoTemplate` for connection/database access, raw driver reads/writes/`$vectorSearch` for the vector-specific work, same `JavAIPI` contract |
| `embedding_version` side table / expand-contract migration | Pattern | Versioned vector storage across model changes, non-destructive |

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

## A JPA-style query, contrasted with an object-level query

A repository query (`findNearestByBodyVector`) is scoped to the whole persisted store; an object-level
`query()` (Vector Core) is scoped to one object's reachable graph. See whitepaper §6.6–§6.7 for the full
worked contrast and the comparison table for which one to reach for.
