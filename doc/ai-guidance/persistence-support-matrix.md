# JavAI Persistence — Backend Support Matrix

Quick-reference for **what a `JavAIRepository` can do on each of the three persistence backends**, so you can
tell — before you write a repository method or annotate an entity — whether it will work on the backend you've
configured. Companion to `JavAI_Usage_Guide.md`; the authoritative, always-current source is the JavAI repo's
`doc/spec/persistence-bridge.md`.

## Orientation: the three backends are not the same kind of thing

`JavAIPI.repository(YourRepository.class, config)` returns a proxy bound to whichever
`JavAIPersistenceConfig.Backend` you passed. The backends differ in a way that explains almost every cell
below:

- **`POSTGRES` (`RepositoryBackendHibernatePostgres`)** — a *real Hibernate/JPA* backend. Your entity is an
  ordinary `@Entity`, mapped by Hibernate, so **standard JPA mapping annotations apply**. Vectors and JavAI
  collections live in side tables this backend owns; derived finders become JPA Criteria queries.
- **`NEO4J` (`RepositoryBackendNeo4j`)** and **`MONGODB` (`RepositoryBackendSpringDataMongo`)** — *reflective*
  backends. There is no ORM. The backend reflects over your object's **declared field types** to decide what
  is a scalar property, a relationship/reference, or a vector. They consult **only `@Id`** from JPA; every
  other JPA mapping annotation is **ignored** (harmless, just not load-bearing).

**Invariants across all three:** identity is a single `@Id`-annotated `UUID` field, **application-assigned**
(no `@GeneratedValue`); a `Map`-typed relationship field must be **`String`-keyed**; and a repository method
that a backend can't serve is rejected **at repository-creation time**, never on first call.

Legend: ✅ supported · ⚠️ accepted but inert (no effect) · ❌ unsupported / rejected · **N/A** not applicable.

---

## Table 1 — JPA (`jakarta.persistence`) annotations

| Annotation | Postgres | Neo4j | MongoDB | Notes |
|---|:--:|:--:|:--:|---|
| `@Id` | ✅ | ✅ | ✅ | **Required.** Must be `UUID`. Application-assigned (the backend sets a random `UUID` if null on save). |
| `@Entity` | ✅ (required) | ⚠️ | ⚠️ | Postgres needs it (it *is* a Hibernate entity; also drives recursive related-type registration). Neo4j/Mongo key off the declared class/field type instead — the annotation is conventional, not consulted. |
| `@GeneratedValue` | ❌ | ❌ | ❌ | Identity is always an app-assigned `UUID`. Don't use it. |
| `@MappedSuperclass` | ✅ | ⚠️ | ⚠️ | Postgres needs it for an inherited field to be mapped. Neo4j/Mongo walk the full class hierarchy regardless, so inherited fields round-trip either way. |
| `@OneToOne` / `@ManyToOne` | ✅ | ⚠️ | ⚠️ | Postgres maps a **singular** association through Hibernate (use `cascade = CascadeType.ALL` so it saves with the owner). Neo4j/Mongo infer a singular relationship/reference from the field's declared type (a `JavAIVectorizable`-typed field) — the annotation is inert. |
| `@OneToMany` / `@ManyToMany` | ✅ on a plain JDK collection **and** on an interface-typed JavAI collection<br>❌ on a *concrete*-typed JavAI collection | ⚠️ | ⚠️ | **Postgres:** a normal Hibernate association — FK/join table, cascade, `orphanRemoval`, `mappedBy`, lazy loading — whether the field is a plain JDK collection **or** declared by a JavAI *interface* (`JavAIList`/`JavAISet`/`JavAIMap`). In both cases `@JavAIVectorizable` members get their vectors persisted automatically; for the JavAI-interface case the instance Hibernate substitutes is still a real JavAI collection (vectors + dirty-tracking intact), with **no `@CollectionType` or other JavAI-specific annotation required**. A *concrete*-typed field (`JavAIArrayList<X>`) is rejected at registration, because Hibernate must be able to substitute its own instance — declare it by the interface and make it non-final. Neo4j/Mongo: inert, to-many works by declared type. |
| `@Transient` | ✅ | ⚠️ | ⚠️ | Postgres honors it (and auto-adds it for JavAI-collection and `Point` fields). Neo4j/Mongo don't skip `@Transient` fields — a simple-typed one would still be persisted. |
| `@Column`, `@Table`, `@Basic`, `@Enumerated`, `@Temporal`, `@Lob`, `@Version` | ✅ | ⚠️ | ⚠️ | Postgres: honored by Hibernate as usual. Neo4j/Mongo: ignored — scalar conversion is fixed (`enum`→`name()`, `Instant`/`UUID`→string, etc.), column/table names don't apply. |
| `@Embedded` / `@Embeddable` | ✅ | ❌ | ❌ | Postgres maps an embeddable's columns (and Criteria can navigate into them). Neo4j/Mongo have no embeddable concept — such a field is skipped. |

> The JavAI vector/graph annotations (`@Vectorize`, `@Summary`, `@SearchVisibility`, `@JavAIVectorizable`,
> `@JavAIGraphNode`/`@JavAIEdge`, `@Taggable`) are orthogonal to this table and behave the same on all three
> backends — see `JavAI_Usage_Guide.md`.

---

## Table 2 — Query & derived-finder capabilities

Everything below is declared on a `JavAIRepository<T>` subinterface and realized by `JavAIPI`, never
hand-implemented.

| Capability | Postgres | Neo4j | MongoDB | Notes |
|---|:--:|:--:|:--:|---|
| `save` / `findById` / `findAll` / `deleteById` | ✅ | ✅ | ✅ | Base CRUD, `UUID` identity. |
| `reindexAll()` | ✅ | ✅ | ✅ | Re-indexes the **whole datastore** — every *registered* entity type, not just the repository's own — under the currently-configured model. Takes no argument, because no single type scopes it. Postgres also validates afterwards and throws if anything that had a vector under the previous model didn't get one under the new model. |
| `reindex()` | ✅ | ✅ | ✅ | Re-embeds **only this repository's own type**, leaving others on whatever model they were last written under. The narrow counterpart; performs no completeness validation, since it is deliberately partial. Prefer `reindexAll()` when swapping models. |
| `findNearestBy<Field>Vector` / `findNearestByVector` / `findNearestBySummaryVector` | ✅ | ✅ | ✅ | Vector search: pgvector HNSW / Neo4j native vector index / MongoDB `$vectorSearch`. |
| Equality, `Not`, `In`/`NotIn`, `IsNull`/`IsNotNull`, `True`/`False` | ✅ | ✅ | ✅ | |
| Comparison `GreaterThan(Equal)`/`LessThan(Equal)`/`Before`/`After`, `Between` | ✅ | ✅ | ✅ | On a value stored as an ISO-8601 string (`Instant`), ordering is lexicographic — correct for ISO-8601. |
| `Like`/`NotLike`, `StartingWith`, `EndingWith`, `Containing`/`NotContaining` | ✅ | ✅ | ✅ | |
| `IgnoreCase` | ✅ | ✅ | ✅ | `lower(...)` (PG) / `toLower(...)` (Neo4j) / case-insensitive regex or collation (Mongo). |
| `Regex` / `Matches` (argument is a raw regex) | ✅ | ✅ | ✅ | PG `regexp_like` (Postgres 15+) · Neo4j `=~` · Mongo `$regex`. |
| `IsEmpty` / `IsNotEmpty` (on a collection field) | ✅ | ✅ | ✅ | See Table 3 for which field types count as collections. |
| `Exists` (property present) | ✅ | ✅ | ✅ | Scalar → not-null; collection → non-empty; Mongo → `$exists`. |
| **Geo** `Near(Point, Distance)` / `Within(Circle)` (on a `Point` field) | ✅ | ✅ | ✅ | Requires an `org.springframework.data.geo.Point` field. PG `earth_distance`/`earthdistance` · Neo4j `point.distance` · Mongo `$geoWithin`+`$centerSphere`. Great-circle point-distance (not full PostGIS/GeoJSON polygon geometry). |
| **Nested path — singular** (`findByProfileHandle`) | ✅ | ✅ | ✅ | PG: native Criteria join. Neo4j: `EXISTS {}` traversal. Mongo: resolve referenced ids, match `field.id IN (…)`. |
| **Nested path — to-many** (`findByReviewsReviewer`) | ✅ | ✅ | ✅ | Same mechanisms; the to-many hop uses the collection's storage (Table 3). **Postgres:** a natively-mapped collection (plain JDK or interface-typed JavAI) resolves as a single **Criteria JOIN**; only a *concrete*-typed JavAI collection still uses id-set materialization (a query per hop). Mongo still uses id-set (references are `{type, id}` pointers); Neo4j composes `EXISTS {}` subqueries. |
| `countBy…` / `existsBy…` | ✅ | ✅ | ✅ | Return `long`/`int` and `boolean` respectively. |
| `deleteBy…` | ✅ | ✅ | ✅ | PG deletes per-id (cascades vectors + collection members) · Neo4j `DETACH DELETE` · Mongo `deleteMany` (does **not** cascade to referenced docs). |
| `OrderBy…` / dynamic `Sort` — **root scalar** | ✅ | ✅ | ✅ | |
| `OrderBy…` / `Sort` — **nested singular path** | ✅ | ❌ | ❌ | Neo4j/Mongo sort only by a root scalar; a nested/to-many sort is rejected at creation. |
| `Top`/`First` limiting | ✅ | ✅ | ✅ | |
| `Distinct` | ✅ | ✅ | ⚠️ | PG `DISTINCT`. Neo4j always `RETURN DISTINCT n`. Mongo: results are unique documents by `_id`, so the keyword is a no-op. |
| Return: `List` / `Optional` / single `T` / `Stream` / `Page` / `Slice` | ✅ | ✅ | ✅ | Dynamic `Pageable`/`Limit` parameters honored. A single-result finder that matches >1 row throws (add `Top1`/`First` or a narrower predicate). |
| Arbitrary geo polygons / `$near`-index / spatial indexes | ❌ | ❌ | ❌ | Only point-distance `Near`/`Within` is modeled. |

> **Not repository queries** (so not in this table): the object-graph `query()` (Vector Core, in-memory) and
> a `KnowledgeGraph`'s `nearestSubgraph(...)` — both operate on an in-memory object, not the store.

---

## Table 3 — JavAI collection field types

How each **JavAI collection type**, declared as an `@Entity` field, persists and what derived-finder queries
can reach *through* or *about* it. A `Map` field must be **`String`-keyed** on every backend (validated at
registration).

| Collection type | Persist: Postgres | Persist: Neo4j | Persist: MongoDB | Nested-traversal finder | `IsEmpty`/`IsNotEmpty` finder |
|---|:--:|:--:|:--:|:--:|:--:|
| `JavAIArrayList<E>` | ✅ `javai_collection_members` | ✅ relationship (ordered) | ✅ `{type,id}` reference array | ✅ P·N·M | ✅ P·N·M |
| `JavAILinkedHashSet<E>` | ✅ `javai_collection_members` | ✅ relationship | ✅ reference array | ✅ P·N·M | ✅ P·N·M |
| `JavAILinkedHashMap<String,V>` | ✅ (String key only) | ✅ key on the relationship | ✅ reference array + key | ✅ P·N·M (by the value's field; key-agnostic) | ✅ P·N·M |
| `KnowledgeGraph<N,E>` | ❌ rejected at registration | ✅ **Neo4j-only** (two rel. types) | ❌ rejected at registration | ❌ (not a derived-finder path) | ❌ |
| *plain* `List`/`Set`/`Map` (not a JavAI type) | ✅ native Hibernate association — **requires** `@OneToMany`/`@ManyToMany`/`@ElementCollection` | ✅ relationship (annotation inert) | ✅ reference array (annotation inert) | ✅ P·N·M | ✅ P·N·M |
| **interface-typed** `JavAIList`/`JavAISet`/`JavAIMap` + `@OneToMany`/`@ManyToMany` | ✅ **native Hibernate association** (own join table/FK), JavAI collection instance preserved | ✅ relationship | ✅ reference array | ✅ P·N·M | ✅ P·N·M |

**Which JavAI collection shape should I use on Postgres?** Declare the field by the **interface**, non-final, with the ordinary JPA annotation — `@OneToMany(cascade = ALL) private JavAIList<Comment> comments = new JavAIArrayList<>();`. You get real JPA semantics *and* a real JavAI collection. Declaring it by the **concrete** class (`private final JavAIArrayList<Comment> …`, no annotation) keeps JavAI's own side-table storage instead — still supported, but without FK/join-table/lazy-loading semantics. Hibernate substitutes its own instance into a mapped collection field, which is why the native path needs an interface type and a non-final field.

Notes:
- **Element/value type** of the collection must itself be a persistable entity (its own `@Id`), and it keeps
  its own top-level table/collection/label — it is *referenced*, never embedded, so it stays independently
  queryable through its own repository.
- **`KnowledgeGraph`** is a graph-native structure (multi-hop traversal + similarity in one query via
  `nearestSubgraph`). Persistence is **Neo4j-only** by design; Postgres and MongoDB reject a
  `KnowledgeGraph`-typed field with a clear message at registration. It is not reachable by a derived finder —
  query it in memory after `findById` hydrates it.
- **`P·N·M`** = Postgres · Neo4j · MongoDB.

---

## Rules of thumb

- **Writing a finder?** If it's `findBy`/`countBy`/`existsBy`/`deleteBy` over your entity's own scalar fields,
  it works everywhere. Reaching *through* a relationship, or using geo/emptiness, also works everywhere now —
  just mind the sort restriction on Neo4j/Mongo (root scalar only).
- **Adding a field?** Scalar → fine on all three. A *single* related entity → `@OneToOne`/`@ManyToOne`
  (Postgres) or just the declared type (Neo4j/Mongo). *Many* related entities → a **JavAI collection**, never
  `@OneToMany`. A **geo point** → `org.springframework.data.geo.Point`. A **`KnowledgeGraph`** → Neo4j only.
- **Portability:** target the intersection (avoid `KnowledgeGraph`, `@Embedded`, and nested/ to-many *sort*)
  if the same entity must run on more than one backend.
