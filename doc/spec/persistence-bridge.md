# Persistence Bridge

Module: `javai-persistence`. Whitepaper: §4.4, §4.5.4, §5.2, §6.6. Depends on `javai-collections`,
`javai-vector`, and `javai-model`.

Takes what Vector Core computes in memory and makes it durable and queryable in a real store, without
asking the developer to hand-manage a parallel vector index alongside their ORM.

## Primitives

| Element | Kind | Purpose |
|---|---|---|
| `JavAIPI` | Internal contract | The save/query/re-index contract JavAI objects speak internally |
| `JavAIRepository<T>` | Interface | Spring-Data-style repository base; delegates to existing derived-query-method machinery |
| `findNearestBy<Field>(EmbeddingVector, int limit)` | Derived query method convention | E.g. `findNearestByBodyVector` — repository-level nearest-neighbor search |
| Hibernate-based enhancement shim | Mechanism | ByteBuddy enhancement via Hibernate's `EnhancementContext`-style SPI |
| `hibernate-vector` module | Dependency | Native pgvector column mapping (`@JdbcTypeCode(SqlTypes.VECTOR)`) |
| Neo4j-facing shim | Mechanism | Parallel graph-native persistence backend, same `JavAIPI` contract |
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

ArticleRepository repo = JavAIPI.repository(ArticleRepository.class);

repo.save(article);   // auto-vectorized on write -- backend picked by javai.persistence.backend

List<Article> hits = repo.findNearestByBodyVector(queryVector, 20);

// Swapping the persisted backend is configuration, not code:
//   javai.persistence.backend=postgres+pgvector   (default, via hibernate-vector)
//   javai.persistence.backend=neo4j               (native vector index + Cypher traversal)
```

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

## A JPA-style query, contrasted with an object-level query

A repository query (`findNearestByBodyVector`) is scoped to the whole persisted store; an object-level
`query()` (Vector Core) is scoped to one object's reachable graph. See whitepaper §6.6–§6.7 for the full
worked contrast and the comparison table for which one to reach for.
