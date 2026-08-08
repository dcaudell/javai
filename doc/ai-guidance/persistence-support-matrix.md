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
| `@Column`, `@Table`, `@Basic`, `@Enumerated`, `@Temporal`, `@Lob` | ✅ | ⚠️ | ⚠️ | Postgres: honored by Hibernate as usual. Neo4j/Mongo: ignored — scalar conversion is fixed (`enum`→`name()`, `Instant`/`UUID`→string, etc.), column/table names don't apply. |
| `@Version` | ✅ | ⚠️ | ⚠️ | **Postgres:** optimistic locking works, and works through a detached-entity repository. Concurrent writers to one entity produce one winner and one `OptimisticLockException`; and **since 0.1.8 (OMI-254) the instance `save()` returns carries the version the write assigned**, so the object you get back is safe to mutate and save again — the ordinary load-mutate-save-mutate-save shape, on the root and on cascaded members alike. Up to and including 0.1.7 it did not: detection was correct but `save()` handed back the *pre-write* version, so a second save of the same instance threw with no concurrency involved at all, which made the annotation effectively unusable. On ≤0.1.7, re-read between writes. **Neo4j/Mongo:** inert, and inert in a way worth stating plainly — the field is persisted as an ordinary scalar and never checked or incremented, so it looks like protection and provides none. Use `@Transactional(isolation = …)`, not `@Version`, on those backends. |
| *Implicit* column/table naming (no `@Column`/`@Table`) | ✅ snake_case | n/a | n/a | **Postgres, since 0.1.5 (OMI-145):** `CamelCaseToUnderscoresNamingStrategy` is the default — `emailVerified` → `email_verified`, entity `TestCrew` → table `test_crew` — matching Spring Boot's default. Up to 0.1.4 the bare Hibernate default applied (`emailverified`), so **an existing pre-0.1.5 schema with multi-word names needs migrating or pinning**: `JavAIPersistenceConfig.Builder.physicalNamingStrategy(new PhysicalNamingStrategyStandardImpl())` restores the old naming, and `.hibernateProperty(k, v)`/`.hibernateProperties(map)` passes through any other Hibernate setting. Neo4j/Mongo have no JPA column naming at all, so both knobs are inert there. |
| `@Embedded` / `@Embeddable` | ✅ | ❌ | ❌ | Postgres maps an embeddable's columns (and Criteria can navigate into them). Neo4j/Mongo have no embeddable concept — such a field is skipped. |
| `@Any` (+ `@AnyDiscriminator`, `@AnyDiscriminatorValue`, `@AnyKeyJavaClass`) | ✅ | ❌ | ❌ | A **polymorphic to-one** whose target may be any of several *unrelated* entities, resolved by a discriminator column. **Postgres:** ordinary Hibernate mapping; the concrete types named in `@AnyDiscriminatorValue(entity = …)` are registered automatically, so you don't need a repository for each one. Add `@Cascade` if the owner should save its target — `@Any` doesn't cascade by default. **Two things to know before choosing it:** the target's id column points into several tables, so `@Any` **cannot carry a foreign key** — you trade referential integrity for the polymorphism, and nothing at the database level will stop a dangling reference. And the discriminator values are strings in your data, so renaming or moving a target class is a data migration, not a refactor. **Neo4j/Mongo:** rejected at registration with a clear error. Their mapping has no discriminator concept, and reference detection keys off the declared field type — which for `@Any` is deliberately a plain interface, so the field previously fell into the "silently skipped" boundary and the association came back `null` after a successful save. Refusing it loudly is better than losing it quietly. |

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
| **`saveAll(Iterable<T>)`** | ✅ | ⚠️ | ⚠️ | **Since 0.1.9 (OMI-266).** Saves a batch, embedding every vector it needs in **one** provider call rather than one per entity — measured on Postgres: twelve two-field entities cost 24 texts in 24 round trips saved individually, 24 texts in **1** round trip through `saveAll`. Each entity is then persisted by exactly the path `save` would have taken; nothing about what is written changes. **The ⚠️ is atomicity, not batching** — the batching is identical on all three, and measured on each. **Postgres:** the whole batch is one transaction, all or nothing. **Neo4j/Mongo:** each entity is written independently, so a failure part-way leaves the earlier ones saved — the same limit `inTransaction` has on those backends. They accept the call rather than refusing it, deliberately: refusing would deny them the batching, which is what the method is primarily for. Takes an optional `SummaryPolicy`, exactly like `save`. |
| `reindexAll()` | ✅ | ✅ | ✅ | Since 0.1.9 (OMI-266), batched in chunks of 100 — a whole-table re-index costs one provider call per chunk rather than one per entity. Re-indexes the **whole datastore** — every *registered* entity type, not just the repository's own — under the currently-configured model. Takes no argument, because no single type scopes it. Postgres also validates afterwards and throws if anything that had a vector under the previous model didn't get one under the new model. |
| `reindex()` | ✅ | ✅ | ✅ | Re-embeds **only this repository's own type**, leaving others on whatever model they were last written under. The narrow counterpart; performs no completeness validation, since it is deliberately partial. Prefer `reindexAll()` when swapping models. |
| `findNearestBy<Field>Vector` / `findNearestByVector` / `findNearestBySummaryVector` | ✅ | ✅ | ✅ | Vector search: pgvector HNSW / Neo4j native vector index / MongoDB `$vectorSearch`. |
| **Vector search narrowed by a predicate** — `findNearestBy<Field>VectorAnd<Predicate>(…)`, or `nearestBy(field).where(…)` | ✅ | ❌ | ✅ | **Since 0.1.8 (OMI-230).** "The nearest N that *also* satisfy X", with the **limit applied after the predicate** — so N matches means N results, not "however many of the nearest N happened to match". The predicate vocabulary is the whole derived-finder vocabulary in this table (same translator, so identical semantics), including nested paths. **Postgres:** the predicate resolves to an id set, then ranking happens within it. **Mongo:** the id set becomes `$vectorSearch`'s own `filter`, a genuine pre-filter — note an index created before 0.1.8 lacks the `_id` filter path and must be dropped so it can be recreated. **Neo4j: refused at repository-creation time**, and the refusal is deliberate: `db.index.vector.queryNodes` picks its K nearest *before* Cypher can filter, so narrowing could only ever return fewer than the requested limit and silently answer a different question. Use another backend, filter in the caller (accepting the over-fetch explicitly), or narrow with an ordinary derived finder and rank in memory via `JavAIVectorizable.query(...)`. |
| **Ranked vector results** — declare `List<Ranked<T>>`, or call `.ranked()` | ✅ | ✅ | ✅ | **Since 0.1.8 (OMI-230).** Each hit carries the `similarity` it was ranked on, so a caller that must post-filter can tell a strong 50th match from a weak 5th. **Always plain cosine similarity in `[-1, 1]`** — the same number `VectorMath.cosineSimilarity`/`similarityTo` give in process. Each backend converts its own store's convention (pgvector reports cosine *distance*; Neo4j and Mongo both report `(1 + cosine) / 2`), so the value means the same thing whichever store answered. `Ranked.distance()` gives the cosine-distance view. |
| **Paging a vector search** — trailing `Pageable`/`Limit`, or `.offset(n).limit(n)` | ✅ | ✅ | ✅ | **Since 0.1.8 (OMI-230).** The offset counts *matches*, so paging a narrowed search walks the matching entities rather than the raw ranking. Exact on every backend, narrowing aside: ranking is total, so skipping its head is well-defined — a backend fetches `limit + offset` and drops the head. A `Pageable` supplies both the window and the offset, in which case no `int limit` parameter is declared at all. |
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
| **Joining a caller's transaction** (Spring `@Transactional`, or `JavAIPI.inTransaction`) | ✅ | ❌ | ❌ | **Postgres, since 0.1.5 (OMI-146):** a repository call runs on the caller's session when one is active — a Spring `@Transactional` method or a `JavAIPI.inTransaction(config, body)` block — and opens its own session only when there is none. `@Transactional` needs Spring's transaction manager and JavAI to hold the *same* `SessionFactory` (matched by identity), which since **0.1.6 (OMI-160)** you can get either way round: let JavAI own the factory and ask for it with `JavAIPI.sessionFactory(config)` (**preferred** — keeps the mapping hooks that make an interface-typed `@OneToMany JavAIList<T>` a real JavAI collection; wire it to `JpaTransactionManager`, *not* `HibernateTransactionManager`, which can't unwrap a `DataSource` from it), or let Spring own it and hand it over with `Builder.sessionFactory(...)` (works with either manager, but loses those hooks). Vector rows commit/roll back with the caller. Neo4j/Mongo: every call is still its own unit of work, and `inTransaction` throws rather than pretending; design multi-call flows there to be idempotent. |
| `deleteBy…` / `deleteById` | ✅ | ✅ | ✅ | PG deletes per-id (cascades vectors + collection members) · Neo4j `DETACH DELETE` · Mongo `deleteMany` (does **not** cascade to referenced docs). **Postgres, since OMI-255:** an entity held in another entity's `@Summary` or ordinary to-many is **detached from those containers first**, so deleting it succeeds instead of tripping the join table's foreign key. Membership only — no other entity is deleted. A **singular** reference (`@ManyToOne`/`@OneToOne`) at the entity is still refused by the foreign key, deliberately: nulling someone else's field is a data change, not a cleanup. |
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
  A **polymorphic to-one** (the target may be any of several unrelated entities) → `@Any`, Postgres only.
- **Portability:** target the intersection (avoid `KnowledgeGraph`, `@Any`, `@Embedded`, and nested/to-many
  *sort*) if the same entity must run on more than one backend. Note that `KnowledgeGraph` and `@Any` pull in
  opposite directions — one is Neo4j-only, the other Postgres-only — so an entity declaring both cannot be
  persisted on any single backend at all.

---

## Registering entity types (and why ordering used to matter)

JavAI learns about an entity when you realize a repository for it — or for anything that *references* it,
since related types are registered recursively. On **Postgres** the backend then builds one Hibernate
`SessionFactory`, lazily, at the first actual repository call (or at `JavAIPI.sessionFactory(config)`, which
builds it too). Hibernate's metadata is immutable once built, so a type genuinely unknown at that moment can
never be mapped. Neo4j and MongoDB have no boot-time metadata and no equivalent constraint.

**Name your entity packages and the problem disappears:**

```java
JavAIPersistenceConfig.builder()
    .backend(Backend.POSTGRES)
    .postgresUrl(url).postgresUsername(user).postgresPassword(password)
    .entityPackages("com.example.domain")     // every @Entity under here, scanned up front
    .entityType(SomeTypeElsewhere.class)      // for anything outside those packages
    .build();
```

The entity set is then a property of the **configuration**, complete before anything can be built — so
repositories may be realized in any order, at any time, and no startup ordering has to be arranged or
maintained. In Spring, that means repository `@Bean`s need no `@DependsOn` on the factory bean.

Scanning reads class metadata rather than loading classes, so a broad package is cheap and initializes
nothing. It does **validate everything it finds**, and registration fails naming both the type and the
package it came from. Only three things are refused, all about JavAI-owned field types rather than about
vectors:

1. a **JavAI collection field keyed by something other than `String`** (`JavAIMap<UUID, X>`); a plain
   `Map<UUID, X>` is unaffected;
2. a **`KnowledgeGraph`-typed field**, which is Neo4j-only and already refused on Postgres/Mongo however the
   type was registered;
3. a **collection field that is unmapped** (no `@OneToMany`/`@ManyToMany`/`@ManyToAny`/`@ElementCollection`/
   `@Transient`), or a **concrete-typed** JavAI collection (`JavAIArrayList<X>`) carrying an association
   annotation Hibernate cannot honour.

An entity has to be using JavAI's own collection types, or a Neo4j-only feature, to be refused at all. This
is deliberate rather than lenient: an entity Hibernate maps but JavAI cannot is worse than one that is
refused, because the failure surfaces later and far from its cause.

**Excluding something from a scan.** Occasionally an `@Entity` sits inside a scanned package but belongs to a
*different* persistence unit or `SessionFactory`. Three ways to keep it out, all scanning-only:

```java
.excludeEntityType(ReportingRow.class)              // by class
.excludeEntityPackages("com.example.reporting.*")   // by package (and everything beneath it)
```
```java
@Entity @PersistenceIgnore                          // by declaration, at the class itself
class ReportingRow { }
```

**Being non-vectorized is never a reason to exclude anything.** A plain `@Entity` with no
`@JavAIVectorizable` is a first-class citizen of a `JavAIRepository` — registered, mapped and served exactly
like a vectorized one, it simply has no vectors. Serving both kinds through one repository type is the point
of the design; excluding an entity for being non-vectorized would drop it from persistence entirely.

Exclusion affects **scanning only**. A type named by `entityType(...)` is registered regardless (naming a
class outright is unambiguous intent), and so is one reached through a registered entity's own fields —
Hibernate cannot map the referencing entity without it.

**If you don't name packages or types**, ordering still matters on Postgres: realize every repository before
invoking a method on any of them. Note that a late `JavAIPI.repository(...)` is **harmless** when it
introduces nothing new — re-realizing one, or asking for a type another entity already pulled in, is a no-op.
It fails only when the type is genuinely unknown, and the error then names *what built the factory*, because
that call is the thing to move.

---

## Concurrency: what `@Summary` costs, and what JavAI does about it (OMI-255)

**The short version:** a `@Summary` container's vector is derived state, so JavAI does not write it inside
your transaction. Your mutation records that the container owes a recomputation; the recomputation happens
straight after your transaction commits, against committed state, under a lock. Two people writing beneath
one container no longer collide, and the container ends up reflecting **both** of them rather than whichever
committed last.

### Why this needed doing at all

Annotating a container with `@Summary` means every write anywhere beneath it changes that container's
**single** row — one per `(owner_type, owner_id)`. That is inherent to what a summary is, and it does not
depend on the children having distinct primary keys or living in different tables. Before this was fixed,
that row (and, worse, rows that had not changed at all) were written inside the caller's transaction, so at
`REPEATABLE READ` two unrelated writers refused each other with `could not serialize access`, surfacing as
`org.hibernate.exception.LockAcquisitionException` in code with no visible connection to vectors.

### The contract you can rely on

| | Guarantee |
|---|---|
| **Concurrent writes beneath one container** | Both succeed. They no longer share any row inside their own transactions. |
| **The container's summary afterwards** | Reflects every committed write, not just the last one — it is recomputed *from the committed graph*, not folded from whatever the writer held. |
| **When it is current** | By the time `save(...)` returns. Inside `JavAIPI.inTransaction` or a Spring `@Transactional` method, by the time that outer transaction commits. |
| **Inside your own open transaction** | ⚠️ **Not yet current.** A summary-vector search issued between your `save` and your `commit` sees the container's *previous* summary. |
| **Ancestors you never loaded** | Recomputed too. Containment is resolved from the database, so a pod that loaded only a `Shelf` still updates the `Library` above it. |
| **If the recomputation fails** | Your write is committed and stays committed. The recomputation stays queued (`javai_summary_pending`) and is retried by the next save of that container, `JavAIPI.drainPendingSummaries(config)`, or a reindex. It is logged at `WARNING`, never silently dropped. |
| **Entities with no `@Summary` anywhere** | Completely unaffected — nothing is queued, no queue table is created, and their entity-grain row is written inline exactly as before. |

### The one thing to decide

Nothing is required of you: `save(entity)` behaves as described above. The opt-out exists for write-heavy
paths where a container's summary being a few seconds behind is cheaper than the recomputation:

```java
shelves.save(shelf, SummaryPolicy.QUEUE_ONLY);   // record what is owed, don't recompute now
```

Then drain it somewhere — a scheduled job, a quiet-period task, or the next ordinary `save`:

```java
JavAIPI.drainPendingSummaries(config);           // safe to run concurrently from several pods
```

⚠️ **`QUEUE_ONLY` with nothing ever draining leaves summaries stale indefinitely, and nothing will tell you
so.** The work is durable, not automatic.

### Postgres only, in this phase

Neo4j and MongoDB recompute summaries inline and accept `SummaryPolicy` without acting on it — they are
already as current as `RECOMPUTE_AFTER_COMMIT` would make them. They also do not join a caller's transaction
at all (see Table 2), so the contention this addresses takes a different shape there and has not been
characterised.

### Two related things fixed alongside it

- **DDL no longer runs on the write path.** Every vector write used to issue `CREATE TABLE IF NOT EXISTS` +
  `CREATE INDEX IF NOT EXISTS` first, inside the caller's transaction. `CREATE INDEX` takes a lock that
  conflicts with a concurrent transaction's inserts on the same table, so two savers could **deadlock** on
  the DDL before reaching any of the contention above. Provisioning now happens once, on its own connection.
- **Unchanged vectors are no longer rewritten.** A save used to rewrite every vector row it touched even when
  the value was identical, and at `REPEATABLE READ` a value-identical `UPDATE` still creates a row version a
  concurrent writer can collide with. Rows are now written only when their value actually changes.

---

## Two traps that look like JavAI bugs and aren't

### A convenience getter can break a nested finder (OMI-161)

Derived finder names are parsed with Spring Data's `PartTree`, which discovers properties from **getters as
well as fields**. Adding a convenience getter whose name collides with a nested path therefore *changes how
that path is split*:

```java
@Entity @JavAIVectorizable
public class Profile {
    @OneToOne private Identity identity;

    public UUID getIdentityId() { return identity.getId(); }   // <-- innocuous-looking
}
```

`findByIdentityId` previously split into the nested path `identity.id`. With that getter present, `PartTree`
sees a real `identityId` property and binds it as a **single segment** instead — and since JavAI resolves the
path against *fields*, execution then fails with:

```
IllegalStateException: Expected field identityId on class ...Profile or one of its superclasses
```

The getter that "helps" is what breaks it. **Fix:** rename the getter (`identityIdOrNull()`,
`resolveIdentityId()`), or drop it and let the nested path resolve naturally. Nested paths need no accessor —
JavAI reads fields.

### `@Summary` on a `FetchType.LAZY` association only summarizes inside a session

`summaryVector()` has to read each `@Summary` child's own summary. If that child is a still-uninitialized
lazy association on a **detached** entity (anything returned from a repository), computing the summary throws
`LazyInitializationException`, exactly like any other lazy dereference.

This is deliberate, not an oversight: silently treating an unloadable child as contributing nothing would
make `summaryVector()` depend on session state, so the identical object graph would summarize differently
depending on how it happened to be loaded. **Fix:** fetch that association eagerly, or read the summary while
the entity is still managed. Saving is unaffected — only reading a summary off a detached instance.

### A repository returns a genuinely detached entity (changed in 0.1.10, OMI-271)

Up to and including 0.1.9, an entity from `findById`/`findAll`/a derived finder/a vector search came back
with its **whole reachable collection graph already loaded** — a read of one entity loaded everything
reachable from it, recursively, and paid a side-table SELECT per entity in it. That was a defect, not a
feature: the post-load walk that served stored vectors iterated every collection field, and iterating an
uninitialized Hibernate collection is initializing it.

Since 0.1.10 nothing is loaded that the caller did not ask for, which means **traversing an untouched lazy
association on a returned entity now throws `LazyInitializationException`** — ordinary JPA, and the same rule
the `@Summary` section above already described for singular associations. Code that relied on the graph
arriving pre-loaded will break, visibly and at the point of traversal.

**Fix:** read inside a unit of work and initialize the hops you actually want, so the cost is yours to choose:

```java
Identity identity = JavAIPI.inTransaction(config, () -> {
    Identity loaded = identities.findById(id).orElseThrow();
    Hibernate.initialize(loaded.getGallery());
    return loaded;
});
```

A Spring `@Transactional` method works the same way (see OMI-146's Spring-managed sessions). For a GraphQL or
REST surface, the durable answer is to resolve each hop from its own repository call driven by the selection
set, rather than loading a graph and projecting it.

**One mapping is still eager, deliberately:** a JavAI collection field carrying *no* association annotation
(`private final JavAIArrayList<X> …`) is stored out-of-band in `javai_collection_members` and has no
Hibernate laziness to lean on, so it is still filled on load. Declare the field by the interface with
`@OneToMany`/`@ManyToMany` (see "Which JavAI collection shape should I use on Postgres?" above) to get a
lazy one.
