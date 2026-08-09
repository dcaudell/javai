# Changelog

Notable changes to JavAI Extensions, newest first. Follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/)
loosely and [Semantic Versioning](https://semver.org/spec/v2.0.0.html) exactly.

This file starts at 0.1.5. Releases before it (0.1.0 through 0.1.4) are described by their git history and
their GitHub releases; nothing is reconstructed here after the fact, since a changelog written from memory is
worth less than the commit log it would be guessing from.

Each entry names the module it affects, because this repository releases all nine modules together at one
version -- a given release usually changes only one or two of them.

## [Unreleased]

### Added

- **Conformance coverage for the fetch/attachment cells that were named but never measured (OMI-279).** The
  bidirectional `@OneToOne`/`@OneToMany` pairs, `@OneToOne(optional = false)`, `@Any` under `LAZY`, and the
  `EAGER` variants of both collection mappings. No production code changed — every cell is either conformant
  or a documented Hibernate behaviour JavAI inherits — but three of them are now written down rather than
  assumed:

  - ⚠️ **A lazy to-one whose target class is `final` is silently eager.** Hibernate proxies by subclassing,
    and a `final` class cannot be subclassed. Nothing reports it, `Hibernate.isInitialized` returns `true` on
    a field declared `LAZY`, and the value is correct — only early. `final` is a keyword people put on
    entities by habit, so this is the one most likely to be met in the wild.
  - ⚠️ **The inverse side of a `@OneToOne` (`mappedBy`) ignores `LAZY`.** No foreign key on that side, so
    Hibernate must look to know whether the other row exists.
  - **`optional = false` on the *owning* side does *not* force an eager fetch** — the opposite of the widely
    repeated rule, which holds only for the inverse side. The FK column is itself proof the row exists.

  The middle finding is why the first was found at all: every fixture in the new test was `final`, so *both*
  to-ones came back eager and finality was very nearly documented as optionality. `TestSeal` and
  `TestWaxSeal` are now identical targets of identical mappings differing only in the keyword.

### Changed

- **`javai-persistence`: an `@ElementCollection` of basic values no longer breaks `save()` (OMI-275).** The
  graph walks reflected into every collection element, so a `List<String>` put them on `String.value` and the
  module system refused to open `java.lang` — `InaccessibleObjectException` on an ordinary JPA mapping the
  registration validator explicitly accepts. JDK values are leaves now, guarded in the shared walk rather
  than at each call site.

- **⚠️ `javai-persistence`: `save()` returns the managed instance, as Spring Data JPA's does (OMI-275).**
  It used to return the caller's own instance, which was never managed. That was deliberate — `merge()` left
  `@Transient` JavAI collection fields empty on the managed copy — and OMI-277 removed the reason by making
  JavAI collections native associations `merge()` carries across. Only `Point` fields are still transient,
  and `save` now copies those onto the managed copy explicitly.

  **What it fixes is not only a difference from Spring Data.** Returning an unmanaged root while the session
  was open was the *one* way a caller could be handed a graph attached in one place and detached in another:
  mutate the root and the change was silently discarded, mutate a child reached through it and the change was
  silently persisted, with nothing about either object saying which was which. Measured, then fixed —
  `AttachmentConformanceTest`.

  ⚠️ **The returned graph is a different object graph from the one passed in.** That is ordinary `merge`
  semantics, and it is the part most likely to surprise: after a save, keep using what `save` returned *or*
  keep using your own instance, but do not mix them and expect the same objects. Mutating an entity you still
  hold no longer affects what `save` handed back.


- **⚠️ `javai-persistence`/`javai-tagging`: a JavAI collection field must be declared by its interface
  (OMI-277).** `private final JavAIArrayList<X> xs = new JavAIArrayList<>();` is **refused at registration**
  now, with a message naming the interface to use instead. The supported shape is the one already
  recommended:

  ```java
  @OneToMany(cascade = CascadeType.ALL)
  private JavAIList<Photo> photos = new JavAIArrayList<>();   // interface-typed, non-final, annotated
  ```

  The concrete form was a second storage mechanism (`javai_collection_members`) and it was **silently
  root-only in both directions**: reached through an association the collection came back empty, and saved
  through one its members were never written. It was withdrawn rather than repaired because it cannot be made
  lazy where it stands — the field holds a `final` instance of a `final` class, and Hibernate manages a
  collection by substituting its own. See OMI-277 for the options weighed, including the one that was chosen
  and then withdrawn on contact with the types.

  **This is a breaking API change to `javai-tagging`'s shipped `TagSet`**: `getTags()` returns
  `JavAIList<Tag>` rather than `JavAIArrayList<Tag>`. A caller that declared the receiver as the concrete
  type needs a one-word change; every other use is unaffected.

  ⚠️ **Two to-many fields of the same element type now need explicit `@JoinTable(name = …)`.** Hibernate
  derives the default join-table name from owner + element type, so a list and a map of the same type on one
  entity silently claim the same table. Ordinary JPA, newly reachable because the map used to avoid the
  native path entirely.

### Removed

- **`javai-persistence`: the `javai_collection_members` side table and all its machinery (OMI-277).** The
  mapping that used it was refused in the same release; the table was left in place, unreachable, so the
  decision could be walked back. Kept that way it would have been an empty table created in every database on
  every boot, plus read/write/delete/derived-finder/containment code nothing could reach — vestigial by any
  reading. Gone: the `CREATE TABLE`, the membership read and write paths, the cascade-delete and
  detach-from-container halves, the map-key validator that only ever fired for the refused shape, and
  `Containment`'s `JAVAI_COLLECTION` edge kind.

  **Nothing drops an existing table.** A database that already has one keeps it, empty and unread, until
  somebody drops it by hand; a database built from scratch never gets one.

  One live path had to be rebuilt rather than deleted: a **geo predicate nested through a to-many hop** used
  the membership table to map member ids back to owner ids. It resolves through an HQL join over the
  association now, which is what the hop always was once the collection was native.

### Fixed

- **Docs: `doc/ai-guidance/persistence-support-matrix.md` described the storage this release deleted.** The
  consumer-facing support matrix still had JavAI collections living in a side table, `@Transient` being
  auto-added for them, a to-many finder hop costing a query per hop, `String`-keyed maps as a rule on all
  three backends, and — worst of the set, because someone would have followed it — a *Rules of thumb* line
  reading "*Many* related entities → a **JavAI collection**, never `@OneToMany`", which 0.1.10 inverts. Also
  corrected: the `save()`-returns-managed note was dated to 0.1.11, a version that does not exist.

  The same OMI-277 vestiges are gone from `RepositoryBackendHibernatePostgres`'s own javadoc, which claimed
  "both shapes are fully supported and can coexist" two paragraphs after explaining that one of them was
  withdrawn, and from the `IllegalArgumentException` thrown at an unmapped collection field, which advised
  reaching for a concrete JavAI collection — the shape that is now refused, so following the message led
  straight into a second failure.

- **`javai-persistence`: a `Point` reached through an association is no longer silently `null` (OMI-276).**
  A 0.1.10 regression, and a silent one: nothing threw and nothing logged, so an entity simply appeared to
  have no location. `Point` fields live out-of-band in `javai_geo_points` and were read by a recursive walk
  of the loaded graph. OMI-271 correctly stopped that walk at uninitialized associations, and the walk runs
  before the caller can initialize anything -- so a `Point` on any entity the caller initialized afterwards
  was never read. On 0.1.9 the walk force-initialized the whole graph and always got there; the over-fetch
  was carrying it.

  `Point` fields now come from the same `POST_LOAD` event that already serves vectors -- once per entity
  Hibernate actually loads, whenever it loads it -- which is the one place that can also cover an entity
  initialized later. The recursive geo walk is gone, and with it the last graph walk on the load path.

- **`javai-persistence`: an entity's out-of-band state is read in one statement (OMI-276).** Restoring the
  `Point` could have meant a third query per entity on top of the two the post-load listener already issued
  for vectors, plus a JDBC metadata call per table per entity, plus **one query per `Point` field**. Instead
  the three reads are one: a single `UNION` over the tables that apply to that entity and actually exist,
  with existence memoised so the metadata round trip is paid once per table rather than once per entity.
  Measured, per OMI-275's standing criterion: an entity with two `Point` fields costs **one** geo read, and
  a load costs **one** field-vector read per entity.


### Fixed

- **`javai-persistence`: a read no longer loads the whole reachable object graph (OMI-271).**
  `reachableRelated` -- the walk both post-load steps traversed -- called `addAll` on every collection-valued
  field, and iterating an uninitialized Hibernate `PersistentCollection` *is* initializing it. So every
  `findById`/`findAll`/derived finder/vector search loaded the root's entire reachable collection graph,
  recursively, and paid a side-table SELECT per entity in it, whatever the caller had asked for.

  Measured with Hibernate's own load counters against a real pgvector container: reading one string off a
  root with five children loaded **6** entities, now **1**; off a three-level graph of sixteen, loaded
  **16**, now **1**.

  Laziness was already enforced on *singular* associations -- but by accident, not design: an uninitialized
  proxy is a generated subclass, `@Entity` is not `@Inherited`, so the walk's `isAnnotationPresent` test
  happened to reject it. A lazy `@OneToMany`/`@ManyToMany` had no such accident protecting it.

  ⚠️ **This changes what a consumer gets back.** A repository now returns a genuinely detached entity, so
  traversing an association the caller never touched raises `LazyInitializationException` -- ordinary JPA,
  and what `AssociationGraphE2ETest` already asserted for singular associations. Read inside
  `JavAIPI.inTransaction` (or a Spring `@Transactional` unit of work) when the graph is genuinely wanted:

  ```java
  Library library = JavAIPI.inTransaction(config, () -> {
      Library loaded = libraries.findById(id).orElseThrow();
      Hibernate.initialize(loaded.getShelves());   // pay for the hop you actually want
      return loaded;
  });
  ```

  Fixing the walk exposed the same defect in four more graph walks that had only ever worked *because* the
  read path pre-initialized everything for them -- `JavAIRuntime.collectReachableVectorizables`,
  `ensureIdsAssigned`, `writeVectorsForRelatedEntities`, and the `@Summary` child handling -- so `save()` of
  a detached entity threw from inside JavAI once the crutch was gone. All four now skip what they cannot see
  without loading it.

  This left one deliberate exception — a JavAI collection field carrying **no** association annotation stayed
  eagerly hydrated, since it was mapped out-of-band and had no Hibernate laziness to lean on, and declining to
  fill it would have handed back a silently-empty collection rather than a lazy one. **OMI-277, later in this
  same release, removed that mapping and with it the exception.** As shipped, no collection shape ignores its
  declared `FetchType`.

### Added

- **`javai-persistence`: stored vectors are served from Hibernate's load event (OMI-271).** The walk removed
  above existed to serve loaded entities their stored vectors, so that re-saving unchanged content does not
  re-embed it (OMI-256). `JavAIPostLoadVectorListener` does that from `POST_LOAD` instead -- one SELECT per
  entity *actually loaded*, and strictly more complete than any walk could be: a member the caller
  initializes later, after any load-time walk has finished, is served as it arrives. `save()` suspends it for
  its own unit of work, because `merge()` loads the row before copying the caller's values onto it and
  hydrating there would pair the old vector with the new value.

- **`javai-model`: `JavAIRuntime.configureInitializationCheck(Predicate<Object>)` (OMI-271).** Vector Core's
  graph walks must not touch a value whose resolution would perform I/O, and cannot recognise one without
  depending on an ORM -- which it deliberately does not. The Hibernate backend installs
  `Hibernate::isInitialized`; the default answers `true` for everything, which is exactly right for a plain
  object graph with no persistence layer under it.
--
## [0.1.9] - 2026-08-07

### Added

- **`javai-persistence`: `JavAIRepository.saveAll(Iterable<T>)` (OMI-266).** A repository could only be
  handed one entity at a time, and a `save` can batch only within its own reachable subgraph — so saving a
  hundred entities cost a hundred sequential round trips to the embedding provider however well each
  individual save batched. `saveAll` embeds the whole batch first, then persists each entity by exactly the
  same path it would have taken alone.

  ```java
  articles.saveAll(fleet);                              // one batched round trip, then the writes
  articles.saveAll(fleet, SummaryPolicy.QUEUE_ONLY);    // same, deferring the @Summary recomputation
  ```

  **Atomicity is Postgres-only**, matching `JavAIPI.inTransaction`: there the batch is one transaction, all
  or nothing. Neo4j and MongoDB write each entity independently and say so. They were deliberately not made
  to refuse the call — refusing would deny them the batching, which is what the method primarily exists for,
  for the sake of a guarantee it secondarily provides.

  The embeddings are computed *before* the transaction opens, so on Postgres a bulk write no longer holds a
  database transaction open across a sequence of network calls to an embedding provider.

- **`javai-vector`: `JavAIEmbeddingProvider.maxBatchSize()`/`maxBatchTokens()`, and a batch that respects
  them (OMI-266).** Nothing bounded how large a batched request could get. `EmbeddingInputLimits` bounds each
  text against the model's context window and is deliberately explicit that it does so *per member, never per
  batch* — which is right, and which left the sum completely unbounded. A hundred individually-legal texts
  against `qwen3-embedding:0.6b`'s 32,768-token context is roughly **9.4 MiB in one HTTP body**.

  That was theoretical while nothing in the library called `embedAll`. The batching work above made every
  `save()`, `saveAll()` and re-index chunk go through it, so it stopped being theoretical.

  Two ceilings, because they fail in opposite directions — eight enormous documents break a token ceiling
  while satisfying any count ceiling, and two thousand one-word strings do the reverse:

  | Provider | Count | Size | How |
  |---|---|---|---|
  | TEI | `max_client_batch_size` (**default 32**) | `max_batch_tokens` (default 16,384) | discovered from `/info`, now cached together with `max_input_length` in one fetch rather than one per limit |
  | OpenAI | 2048 | 300,000 tokens | vendor-published; no endpoint reports them |
  | Ollama, vLLM | library default | library default | neither publishes a batch limit |
  | Replicate | — | — | doesn't batch at all |

  **TEI's default of 32 is below the 100 this library chunked at**, so a default TEI deployment refused a full
  batch outright. Nothing consulted the provider before this.

  Both are `default` methods, so no third-party implementation breaks, and `EmbeddingBatchLimits.DEFAULT_MAX_BATCH_SIZE`
  is the chunk size already in use — a provider that declares nothing behaves exactly as it did.
  `EmbeddingBatchLimits.split` is the one place the rule lives. `embedAll` itself deliberately does *not*
  split: it sends one request for exactly what it is given, which is what keeps one call equal to one round
  trip and one permit. A single text larger than the whole batch budget is sent alone rather than dropped —
  it is already bounded to the model's own context, so it is a request the provider has every reason to
  accept, and skipping it forward would loop forever.

### Changed

- **`javai-model`, `javai-persistence`: persistence flows batch their embedding calls (OMI-266).** Nothing
  in this library called `JavAIRuntime.precomputeVectors` or `JavAIEmbeddingProvider.embedAll`. Both had
  existed since OMI-187/OMI-213, both were tested and documented, and both were reachable only by a caller
  who read the spec and wired them up by hand. Every persistence flow discovered its texts one at a time,
  deep inside a flush, and paid a round trip for each.

  Measured on the Postgres backend with a round-trip-aware ledger, before → after:

  | Flow | Texts | Round trips before | after |
  |---|---|---|---|
  | `save()` — one entity, two `@Vectorize` fields | 2 | 2 | **1** |
  | `save()` — a container and twelve members | 13 | 13 | **1** |
  | `saveAll()` — twelve entities | 24 | *(no such method)* | **1** |
  | `reindex()` — whole table, new model | 98 | 98 | **1** |
  | `inTransaction` — twelve `save` calls in a loop | 24 | 24 | 12 |

  The number of texts embedded is unchanged in every row — this changes how many calls they arrive in, not
  how much work is done, and OMI-187's exactly-once invariant still holds unmodified.

  `JavAIRuntime.runWithSubgraphLockedForPersistence` is where the per-save half lives: it already computed
  the whole reachable subgraph in order to lock it, so it now warms that same set before running the flush.
  All three backends inherit it without changing a line, since all three already wrap `save()` in it.

  **This is not only a latency change.** Those calls happen while every object in the subgraph is locked
  against mutation, and on Postgres and Neo4j inside an open database transaction. Thirteen sequential round
  trips held both for thirteen times as long as one batched call does.

  The last row is deliberate and is documented rather than fixed: batching *across* a transaction whose body
  is an opaque lambda would mean deferring every vector write to commit time, across three backends and
  through OMI-255's summary queue. `saveAll` is the answer for that shape, and the row is pinned by a test so
  the cost of deferring it stays visible.

  **Covered end to end against a real model** by `EmbeddingBatchingE2ETest` (`e2e-client-test`), not only
  against a fake provider: a fake can show that JavAI groups the texts, but not that the grouped request is
  one a real embedding server accepts — which is a separate claim, and the one the batch ceilings exist for.
  It covers `saveAll` on all three backends, every consistency mode, a bulk write provably larger than one
  batch, a small declared ceiling honoured against the live server (the TEI-shaped case, unreachable on a
  host where `LocalEmbeddingDefaults` picks Ollama), and that the returned vectors are real, correctly
  dimensioned and distinct — a batch whose rows were misaligned would satisfy every count-based assertion and
  be silently wrong forever.

- **`javai-model`: `query()` no longer embeds from inside a sort comparator.** Its ranking step reads each
  candidate's `vector()` as the comparator's key extractor, so the first pass over a cold graph discovered
  and embedded each candidate's fields one at a time, in the one place it is least visible. The candidates
  are warmed in one batched pass before the sort; the ranking is identical.

### Fixed

- **`javai-model`: `precomputeVectors` silently discarded the vectors it paid for, for any field that had
  been embedded before (OMI-266).** It committed its batch results through `hydrateFieldVector`, which is
  built for a vector read back from the *database* and therefore refuses any slot that has ever computed or
  has been mutated since construction — the pristine-slot rule that stops a stored vector overwriting a real
  change. Applied to a freshly-computed batch, that rule threw the result away and left the slot dirty, so
  the read that followed embedded the identical text a second time.

  Invisible while the only caller seeded fresh objects, which is the shape OMI-187 built it for. It became
  load-bearing the moment a `save()` warmed an entity that had been loaded and mutated — the ordinary update
  path — where it would have made batching *worse* than not batching: two embeddings of the same text rather
  than one.

  The field pass now captures the slot's generation before reading its text and commits with
  `commitSuccess`, exactly as the concatenated-text pass beside it already did; the captured generation is
  what makes the plain commit safe against a racing mutation, which is the job the pristine-slot check was
  wrongly borrowed to do.

  The existing test asserted the right text was embedded, which a discarded result passes perfectly. Reading
  the slot afterwards and checking that costs nothing is the only thing that catches it, and that assertion
  now exists alongside the old one.

- **`javai-vector` test-jar: `RecordingEmbeddingProvider` un-batched every provider it wrapped.** It did not
  override `embedAll`, so it inherited the looping `default` — wrapping Ollama, TEI, OpenAI or vLLM turned
  their one request back into N sequential ones, and the ledger reported N texts in N calls whether or not
  batching was happening. Every before/after measurement of batching taken through this instrument would have
  been meaningless. It now records a batch as one round trip carrying several texts and passes it to the
  delegate as a batch; `EmbeddingLedger` gained `roundTrips()`/`batchSizes()` alongside the existing
  `totalCalls()`, whose meaning is unchanged so every OMI-187 assertion still reads as it did.

## [0.1.8] - 2026-08-05

### Added

- **`javai-persistence`: a vector search can be narrowed by a relational predicate, return each hit's
  similarity, and be paged (OMI-230).** `findNearestBy<Field>Vector(reference, limit)` took a reference and a
  limit and nothing else, so *"the nearest N that **also** satisfy X"* was not expressible. The workaround was
  over-fetching — unbounded, because the ratio depends entirely on the data, and blind, because the ranking
  information that would have said whether to fetch more was discarded along with the results.

  Two idioms, which compile to the same query (`NearestSpec`) so they cannot answer differently:

  ```java
  // the method-name convention -- validated at repository-creation time, query visible in the interface
  List<MediaNote> findNearestByCaptionVectorAndKindIs(EmbeddingVector reference, int limit, Kind kind);
  List<Ranked<MediaNote>> findNearestByCaptionVectorAndKindIs(EmbeddingVector r, Kind kind, Limit limit);
  List<MediaNote> findNearestByCaptionVector(EmbeddingVector reference, Pageable pageable);

  // ...and the builder, for a predicate composed at runtime
  notes.nearestBy("caption").to(reference)
       .where("kind").in(Kind.IMAGE, Kind.SHORT).and("published").isTrue()
       .offset(20).limit(20).ranked();
  ```

  Everything after `Vector` is parsed by the same Spring Data `PartTree` the ordinary `findBy…` finders use,
  and translated by the same backend code — so the full relational vocabulary (operators, `And`/`Or`, nested
  paths, `IgnoreCase`) is available with no second grammar and identical semantics by construction. The one
  real ambiguity is that `Vector` can occur inside a field name *or* a predicate property; the parser scans
  right to left and requires the tail to begin with `And`, which resolves both directions and is tested from
  both (`findNearestBySubVectorVector`, `findNearestByCaptionVectorAndVectorNameContaining`).

  **The limit applies after the predicate**, which is the entire contract: N matches means N results, not
  "however many of the nearest N happened to match". Postgres resolves the predicate to an id set and ranks
  within it; MongoDB hands that id set to `$vectorSearch`'s own `filter`, a genuine pre-filter (its index
  definition now declares `_id` as a filter field — **an index created by an earlier version lacks that path
  and must be dropped so it can be recreated**). **Neo4j refuses to narrow**, at repository-creation time for
  the method-name idiom and at execution for the builder: `db.index.vector.queryNodes` picks its K nearest
  before Cypher can filter, so narrowing there could only return fewer than the requested limit and silently
  answer a different question — the same over-fetch, hidden inside the library. Ranked results and paging
  work on all three backends.

  `Ranked.similarity()` is plain cosine in `[-1, 1]` everywhere — the same number `similarityTo` returns in
  process, so one threshold means one thing whichever store answered. That is a conversion, not a passthrough:
  pgvector reports cosine *distance*, while Neo4j and MongoDB both report `(1 + cosine) / 2`, and each backend
  undoes its own convention where it is known. Asserted numerically per backend rather than by ordering, since
  an unconverted score still produces a plausible-looking ranking and only the value catches it.

  Internally the three `findNearestBy*` SPI methods collapsed into one `findNearest(entityType, spec)`: they
  differed only in which stored vector to rank against, and adding a predicate, an offset and a distance to
  each of three signatures across three backends would have multiplied a difference that was never real.

### Fixed

- **`javai-persistence`: `@Version` is usable, not merely honored (OMI-254).** Optimistic locking always
  *detected* correctly -- two concurrent writers to one entity produced one winner and one
  `OptimisticLockException` -- and was unusable anyway, because `save()` did not refresh the version on the
  instance it returned. `merge()` performs the write on Hibernate's managed copy and increments *that*
  copy's version, while `save()` deliberately hands back the caller's own instance (returning the managed
  one would hand back an entity whose `@Transient` JavAI collection fields were empty). So the returned
  object still carried the pre-write version, and saving it a second time collided with the row the first
  save had just written -- **no concurrency involved: no second thread, no second transaction**. Adding the
  annotation to an entity broke ordinary provisioning code that saves, mutates and saves again, and the
  failure surfaced as what looked like a concurrency bug in unrelated code.

  `save()` now carries the post-write version back onto the caller's instance, across the whole saved graph
  rather than the root alone -- a cascaded child is written in the same flush and has its own version
  bumped, so refreshing only the root would have left the identical defect one hop down. The invariant: *the
  object `save()` hands back is safe to mutate and save again.* Detection is unchanged, since the version a
  concurrent writer collides on is the one `merge()` read off the detached instance beforehand;
  `OptimisticLockingTest` asserts both halves together, including a non-vectorized `@Version` entity, which
  the vector-state machinery could never have carried by accident. Entities with no `@Version` anywhere are
  unaffected and pay nothing: the graph walk is skipped entirely unless something registered with the
  backend declares one. Postgres only -- Neo4j and MongoDB have no optimistic locking, and the support
  matrix now says so instead of grouping `@Version` with the inert-but-harmless JPA annotations.

- **`javai-persistence`: loading an entity no longer leaves its association members cold, so re-saving an
  unchanged container embeds nothing (OMI-256).** Loading an entity and saving it back unchanged should
  embed nothing -- every value involved is already stored. It embedded **one call per member of a natively
  mapped association, every time**, because hydration served stored vectors into the *root* entity's cache
  slots only. The members Hibernate materializes behind a `@OneToMany`/`@ManyToMany` arrived with empty
  slots and the ensuing save recomputed each one for real. The cost scaled with how much the container held:
  a gallery service loading an album of 50 assets and saving it back -- to reorder it, retitle it, change any
  single field -- paid 50 embedding calls for content that had not changed and whose vectors were sitting in
  the database. Embeddings are the expensive part of a write.

  All four load paths (`findById`, `findAll`, derived finders, vector search) now hydrate the whole loaded
  graph through one shared step, so a path cannot silently forget one -- which mattered, because forgetting
  does not fail, it just costs a model call. The traversal is the one the geo-point hydration already made
  on the same paths, so it initializes nothing that was not already being initialized: an untouched lazy
  association is still skipped rather than loaded on JavAI's initiative, and the `AssociationGraphE2ETest`
  invariants that pin that behavior are unchanged. This was the part OMI-187 left behind, not an OMI-255
  regression -- verified against a worktree at the pre-OMI-255 commit rather than assumed. The test that
  pinned the defect at "exactly one wasted call" now asserts zero.

- **`javai-persistence`: concurrent writes beneath one `@Summary` container no longer refuse each other, and
  the container now reflects all of them (OMI-255).** Two users adding assets to one album failed for each
  other with `org.hibernate.exception.LockAcquisitionException`, from application code with no visible
  connection to vectors. Three separate defects, found in this order:

  1. **DDL ran on every vector write, inside the caller's transaction.** Each write issued
     `CREATE TABLE IF NOT EXISTS` + `ALTER TABLE … ADD COLUMN IF NOT EXISTS` + `CREATE INDEX IF NOT EXISTS`
     before its `INSERT`. `CREATE INDEX` takes a `ShareLock` even when there is nothing to create, and that
     conflicts with the `RowExclusiveLock` a concurrent transaction holds from its own insert into the same
     table — so two savers **deadlocked on the DDL**, before reaching any of the contention the ticket was
     filed about. Provisioning now happens once per table, on its own connection, committed independently.
     Committing it separately is also what makes memoizing it safe: the previous code re-ran it every time
     precisely because DDL rolls back with the caller's transaction, which had made an in-memory "already
     created" flag unable to tell the truth.
  2. **Unchanged vectors were rewritten anyway.** A save rewrote every vector row it touched even when the
     value was byte-identical — and the object's own combined `$vector` is recombined arithmetically on each
     save, so it always arrived with a fresh `computed_at`. At `REPEATABLE READ` a value-identical `UPDATE`
     still creates a row version, and that version is what a concurrent writer collides with. This was the
     row the reported error actually named: `javai_vectors__<model>` with `field_name = '$vector'`, which is
     the container's *own* vector and does not depend on its `@Summary` children at all. Rows are now read
     once per save and written only when their value genuinely changes.
  3. **The summary row genuinely is shared, and is now written outside the caller's transaction.** A
     container's summary is one row per owner and changes whenever any descendant does, so that contention is
     real. It cannot be fixed with a lock inside the writer's transaction: at `REPEATABLE READ` the snapshot
     is fixed by the first statement, long before the container is known, so a writer that waits for a lock
     and then updates a row committed since is still refused. The mutation now appends to a durable
     `javai_summary_pending` queue (inserts with distinct keys, which cannot collide), and the summary is
     recomputed straight after the caller's transaction commits — in a short `READ COMMITTED` transaction,
     under a Postgres advisory lock on the owner, **from the committed graph**. Recomputing from committed
     state rather than folding the writer's own value is what makes the last drain *correct* rather than
     merely last: it has seen both writers' children.

  **The recomputation costs no embedding calls.** Getting there took two fixes, both found by counting
  `embed()` calls rather than by reading code, and both worth naming because either alone leaves waste:

  1. **Only `@Summary` containers are recomputed.** The queue also names leaves, since they are the starting
     points for the walk upward — but a leaf's entity-grain row depends on nothing except itself and was
     already written inline during the save. Recomputing it reloaded the entity and re-embedded any vector
     that did not survive the trip. This was the part that **scaled with container size**.
  2. **A lazy singular `@Summary` child is unproxied before its stored vectors are read.** A
     `FetchType.LAZY` to-one reads back as an uninitialized proxy whose `@Id` is null, so hydration silently
     did nothing while the object still answered vector calls — by recomputing them.

  Guarded two ways: `AssociationGraphEmbeddingCostE2ETest` against a real model, and a new
  `SummaryDrainEmbeddingCostTest` that runs the same accounting in seconds against a fake provider, split by
  phase (`QUEUE_ONLY` = write only, `RECOMPUTE_AFTER_COMMIT` = write plus recomputation) and across all three
  `EmbeddingConsistencyMode`s.

  **Ancestors are found from the database, not from the object graph.** A pod that loads a `Shelf` through
  its own repository holds no `Library`, so Vector Core's in-memory back-edge walk cannot reach it and the
  library's summary silently kept whatever another pod last left. Containment is now resolved from the
  declared `@Summary` fields of registered types plus the stored relationships — covering both
  natively-mapped associations and `javai_collection_members` — and walked transitively.

  Removing a child from a container recomputes it too, and `deleteById` resolves the containers that held an
  entity *before* removing it, since afterwards there is nothing left to ask.

- **`javai-persistence` (Neo4j): a vector search no longer runs against a half-built index.**
  `CREATE VECTOR INDEX` returns as soon as the index *exists* — in state `POPULATING`. Neo4j populates it
  asynchronously, and a query issued before it reaches `ONLINE` is answered from the partial index
  **silently**: no error, just fewer or wrongly-ordered results. `ensureVectorIndex` now waits, using Neo4j's
  own `db.awaitIndex`, which is the same readiness problem `RepositoryBackendSpringDataMongo` already solves
  by polling `listSearchIndexes()` for `queryable: true`.

  Only the creating caller pays the wait — one per index, per process — and a timeout is logged rather than
  thrown, since a slow index on a large store is not a broken one.

  ⚠️ **Defensive, and honestly labelled as such: no test demonstrates this.** It was written while chasing a
  failing Neo4j ranking assertion that turned out to have an entirely different cause (see the test fix
  below), and the wait did not stop that failure. The race it closes is real and documented — `ensureVectorIndex`
  is called on the *query* path, immediately before searching, so a first-ever query on a fresh store creates
  the index and queries it in the same breath — but it is kept on the strength of that reasoning rather than
  a reproduction, which is a weaker footing than everything else in this entry.

- **`e2e-client-test`: the Neo4j similarity-ranking test polls, like its MongoDB twin already did.** Neo4j's
  vector index is updated **asynchronously**, so a just-saved node is briefly absent from it. The Neo4j test
  queried once, immediately after saving — asking an index that did not yet contain the article whether the
  article was in it, and getting back whichever *older* article was nearest. That reads as "an article is not
  its own nearest neighbour" while being purely a timing problem.

  The same file's MongoDB variant already used an `awaitNearest(...)` helper for exactly this, with a javadoc
  explaining that `$vectorSearch` "updates near-real-time, not synchronously with the write". The Neo4j test
  now uses it too. Pre-existing and intermittent; verified to fail identically on a build predating this
  ticket.

  Two earlier diagnoses of this failure were wrong and are recorded here so the next person does not repeat
  them: it is not index *population* (the wait added for that did not stop it), and it is not duplicate
  titles across runs (the titles were made unique per run, and it still failed). Those title changes are kept
  anyway — an assertion that an article ranks nearest to itself is undecidable when an identically-titled
  article exists, since identical text embeds identically — but they were hardening, not the cure.

- **`e2e-client-test`: models are loaded and pinned before the suite runs.** Three separate reasons the
  harness's readiness signal was wrong, each masking the next:

  1. `waitForPort` proves only that Ollama's socket is bound. It binds before its HTTP server serves, so a
     request in that window is accepted and reset (`curl exit 56`) — not a slow load, and no request timeout
     fixes it. Readiness is now gated on `/api/tags` answering.
  2. An embedding model rejects `/api/generate` outright (`"qwen3-embedding:0.6b" does not support
     generate`), so the two models need different endpoints; sending both to one warms neither.
  3. Cold-loading `qwen3:8b` takes **~4m45s**, measured. No sensible client read timeout survives that, so
     whichever test hit the model first timed out while every later test passed —
     `TaggingE2ETest.classifyAllAppliesCorrectRealWorldTopicTagsUsingTheRealCortex` failing while
     `CompletionE2ETest` passed against the same endpoint in seconds.

  `keep_alive` is set for the run as well, and matters as much as the warm-up: Ollama evicts an idle model
  after five minutes, which is shorter than the gap between those two test classes, so warming alone would
  only have moved the failure later.

- **`javai-persistence`: `deleteById` no longer fails for an entity a container still holds.** Deleting an
  entity that sat in another entity's natively-mapped `@OneToMany`/`@ManyToMany` left the join row behind and
  the database refused the delete outright:

  ```
  ERROR: update or delete on table "test_book" violates foreign key constraint
         "fkovgxnu4wc1n9jct9dc7he0gll" on table "test_shelf_test_book"
  ```

  JavAI already removed the equivalent rows for its *own* collection storage
  (`javai_collection_members`), so whether `deleteById` worked depended on which of the two storage shapes
  the container happened to declare — the same entity graph deleted cleanly or threw a constraint violation
  based on a mapping choice made elsewhere. The entity is now detached from every container holding it, both
  shapes, before the row is removed.

  This is a **membership removal, never a cascade**: the container loses its reference and no other entity is
  deleted. A *singular* reference (`@ManyToOne`/`@OneToOne`) pointing at the entity is deliberately left to
  the foreign key to refuse — nulling out someone else's field is a change to their data, not a cleanup, and
  a loud refusal is the honest answer there.

### Added

- **`javai-completion`: `CortexOllama.builder().readTimeout(Duration)`.** The connector had no way to say how
  long a response is worth waiting for, and there is a real case for a long one: an 8B model on CPU working
  through a long prompt — a tag-classification request listing every candidate tag, say — can spend minutes
  generating before its first response byte, and the client's ordinary timeout severs that mid-generation
  with `ResourceAccessException: Read timed out`. Nothing is wrong at either end; the caller simply refused
  to wait for work it asked for. It presents as flakiness because it is load-dependent — the same
  classification passed in 141s on one run and timed out on the next.

  ⚠️ **Unset by default, and deliberately so.** A timeout exists to make a provider that has stopped
  answering look like a failure rather than like slow work. A connector shipping a very long default would
  decide, on every caller's behalf, that a hung endpoint should hold a thread for a quarter of an hour before
  anyone finds out — a decision that belongs to the application, not the library. Existing behaviour is
  therefore unchanged for everyone who does not ask.

  `LocalCompletionDefaults.create(URI, Duration)` is the paired convenience for the case that does want it,
  and `e2e-client-test`'s harness is the only thing in this repository that sets one.

- **`javai-persistence`: `save(entity, SummaryPolicy)` and `JavAIPI.drainPendingSummaries(config)`
  (OMI-255).** `save(entity)` is unchanged and settles its own summary work before returning, so no existing
  call site needs to do anything. `SummaryPolicy.QUEUE_ONLY` records what is owed and returns, for
  write-heavy paths where a container's summary being briefly behind is cheaper than recomputing it; the
  queue is durable, so this delays the work rather than losing it, but nothing drains it on your behalf.
  `reindex`/`reindexAll` now use it internally and drain once at the end instead of per entity.

  An entity type in no `@Summary` relationship is entirely unaffected: nothing queued, no queue table
  created, entity-grain row still written inline. `SummaryPolicy` is accepted and ignored on Neo4j/MongoDB,
  which write summaries inline and are therefore already as current as it could ask for.

  The full contract — including what is visible inside your own open transaction, and what happens when a
  recomputation fails — is in `doc/ai-guidance/persistence-support-matrix.md`'s new "Concurrency" section.

### Documentation

- **`doc/ai-guidance/JavAI_Usage_Guide.md`** now documents how to raise the transaction isolation level on a
  **JavAI-owned `SessionFactory`**, which needs two settings applied together and fails loudly if either is
  missing: `JpaTransactionManager.setJpaDialect(new HibernateJpaDialect())`, and
  `.hibernateProperty("hibernate.connection.handling_mode", "DELAYED_ACQUISITION_AND_HOLD")` on the config.
  A bare `JpaTransactionManager` uses `DefaultJpaDialect`, which cannot prepare a connection and therefore
  refuses *every* `@Transactional(isolation = …)` call rather than degrading to the default — a failure that
  looks nothing like its cause. Written up because it is the natural fallback when `@Version` is not the
  right tool: isolation is enforced by the database on every transaction, where optimistic locking depends
  on each writer going through an entity that carries the annotation. Pinned by
  `JavAIOwnedSessionFactoryTransactionTest` rather than left as prose — which is also how a third setting
  that had been believed necessary, `dialect.setPrepareConnection(true)`, was found to be Spring's own
  default and documented as such rather than repeated as a requirement.
- **`doc/ai-guidance/persistence-support-matrix.md`** splits `@Version` out of the grouped JPA-annotation row
  into its own, with the Postgres/Neo4j/MongoDB positions stated separately. The grouped row's flat ✅ was
  true of detection and misleading about usability, and a wrong ✅ costs more than an honest caveat: it is
  discovered only after the annotation is in and unrelated tests have gone red.

## [0.1.7] - 2026-07-28

### Added

- **`javai-tagging`: a whole translation bundle can be given to a `Tag` or `TagSet` at once (OMI-201).**
  Localization was one locale per call, and each call decoded the JSON blob, put one key, and re-encoded it —
  so a catalog loaded from translation files meant a loop, and the codec itself was package-private, so a
  consumer holding a bundle could not hand it over at all.

  ```java
  Tag tag = Tag.fromLocalizedNamesJson(tagSet, """
          {"en": "Zero-day", "fr": "Faille zero-day", "ja": "ゼロデイ"}""");
  tag.setLocalizedNames(Map.of("de", "Zero-Day-Lücke"));   // merges; existing locales stay
  ```

  Both `Map` and JSON forms, on both types, merging rather than replacing. Malformed JSON surfaces as this
  library's own `IllegalArgumentException` rather than leaking Gson's `JsonSyntaxException` out of an API
  that says nothing about Gson.

  **Slug derivation is now explicit**, because bulk entry made the old rule ("whichever string was entered
  first") mean *whichever key led the JSON object* — a searchable identity depending on serialization order.
  An existing slug is never re-derived; otherwise English wins (`en`, or a variety like `en-US`/`en_GB`, any
  case, preferring plain `en`); otherwise the first entry that yields a usable slug. A candidate that
  slugifies to blank is passed over rather than accepted. The new `slugLocale` field records which locale was
  actually used, so the choice is inspectable rather than inferred.

  `Tag` and `TagSet` share one implementation of all of this (`LocalizedNames`, plus `Slugs` extracted from
  `Tag`); each entity holds only the three fields. They cannot share a superclass — both are JPA `@Entity`
  types and a mapped superclass would change their schema for no benefit.

### Changed

- **`javai-tagging`: a `Tag` or `TagSet` with no derivable slug is now refused at construction (OMI-201).**
  Previously a display name with no alphanumerics (`"!!!"`, or any non-Latin script — this library does not
  transliterate) produced an entity whose only `@Vectorize` field was blank, and therefore whose vector was
  absent: unsearchable, unclassifiable, and indistinguishable from a real tag from the outside. OMI-218
  pinned that this input was reachable through the ordinary public constructor; it is now an error at the
  input that caused it. `JavAITagRepository.addTag` carries a backstop for the one case constructors cannot
  cover — a row hydrated from before the rule existed. `TagSet(String slug)` likewise rejects a null or blank
  slug.

- **`javai-tagging`: `getLocalizedNames()` returns an unmodifiable snapshot.** It returned a mutable copy, so
  `tag.getLocalizedNames().put(...)` compiled, read correctly, and silently did nothing — exactly the call
  bulk localization invites. It now throws `UnsupportedOperationException`; use `setLocalizedNames(...)`.

- **`javai-vector`: `VectorMath`'s public surface is now `EmbeddingVector`-only (OMI-218).** It exposed
  `normalize(float[])` and `addWeighted(float[], float[], float)` alongside its vector-level methods, and
  every caller of those two was doing the same thing by hand — accumulate a weighted sum into a bare array,
  then wrap it back up. Three sites, three copies of the model check, the dimension check and the
  absent-input handling, and **they did not agree with each other**. Both array methods are now private,
  replaced by `weightedSum(List<WeightedVector>)` and `normalize(EmbeddingVector)`.

  That is the actual argument for the narrower surface rather than tidiness: `EmbeddingVector` carries the
  model, the dimensionality and the absence that make those checks possible, and a `float[]` carries none of
  them. Callers migrated: `JavAIRuntime.summaryVector`, `CollectionVectorSupport.summaryVector`,
  `JavAITagRepository.recomputeTagSummaryVector`.

  Every method now accepts absent **and null** inputs without throwing, treating both as "no vector here":
  skipped from an aggregate rather than summed in, `NEGATIVE_INFINITY` from `cosineSimilarity` so a
  content-free vector never ranks as anyone's nearest neighbour, and an absent result when nothing was
  present. Dimension and model compatibility are checked wherever the operation requires it.

  **Two behaviour changes in `CollectionVectorSupport.summaryVector`**, both to stop it disagreeing with the
  object path over the same question. An absent centroid no longer short-circuits the whole summary to
  absent — a member whose own `vector()` is absent can still have a non-absent `summaryVector()`, and
  `JavAIRuntime.summaryVector` never made the equivalent assumption. And a member of a different
  dimensionality now throws instead of being silently skipped, which is the shape of wrong answer that
  cannot be noticed from outside: the summary comes back well-formed, just computed over fewer members than
  the collection holds.

  One diagnostic regression, accepted: `summaryVector()`'s dimension-mismatch message no longer singles out
  the offending `@Summary` field, only the class and its field list. That is the cost of having one
  implementation of the compatibility rule instead of three.

### Fixed

- **`javai-tagging`: recomputing a tag-summary vector crashed, or stored a content-free vector, when a tag
  had no embeddable content (OMI-218).** `Tag`'s only `@Vectorize` field is its slug, and a display name
  with no alphanumerics slugifies to the empty string — so a tag with an absent `summaryVector()` is
  reachable through the ordinary public constructor (now pinned by a test). Accumulating into a bare
  `float[]` could not express that: such a tag arriving first sized the accumulator to zero dimensions and
  stored the result as a real tag-summary vector, and arriving after a present tag threw
  `ArrayIndexOutOfBoundsException` from inside the add loop. Absent tags are now skipped, and a ref whose
  tags are *all* absent has its index entry deleted rather than being given a content-free vector that would
  match arbitrary queries — the same rule the zero-associations case already followed.

- **`javai-vector`: `VectorMath.centroid` never checked dimensionality.** It validated the model id and
  assumed dimensions followed. Same model, different dimensions either threw
  `ArrayIndexOutOfBoundsException` from inside the loop or silently truncated the longer vector, averaging a
  value nobody asked for, depending on which way the mismatch fell.

- **`javai-model`: dirty propagation stopped early and could leave an ancestor holding stale state
  (OMI-191).** `propagateDirty` pruned its walk at the first already-`SummaryDirty` node, on the reasoning
  that a dirty node implies dirty ancestors. That holds for a monotone boolean whose clearer also clears its
  descendants — true on the path `summaryVector()` takes, since it recurses into each `@Summary` child and
  clears it on the way — but it is not true for concatenated text: assembling an ancestor's text calls
  `concatenatedText()` on its descendants, which is pure string work committing nothing, so the ancestor goes
  clean while its descendants stay dirty. A later mutation then pruned at the dirty descendant and left the
  clean ancestor silently out of date. The walk now visits each reachable dependent once via an identity set,
  which also makes cycles terminate. It was the *walk* that was overfitted to a single reader, not just the
  flag — OMI-187's lesson one level up.

  Cost: no early exit. The set walked is ancestors (typically shallow) and each is visited exactly once.

- **`javai-model`: `hydrateFieldVector` shipped undocumented.** Its javadoc was stranded when
  `precomputeVectors` was inserted between the block and its method — javadoc binds only the nearest
  preceding block. Reattached. (Same defect class as `embedAll`'s, fixed in OMI-213; worth noticing that this
  has now happened twice in this file.)

- **`javai-model`: a bulk `precomputeVectors` seed no longer bypasses the embedding concurrency gate
  (OMI-213).** It called the configured provider without acquiring a permit at all, so a bulk seed ran
  entirely outside the bound `JavAIRuntime.configureMaxConcurrentEmbeddingCalls` documents as applying to
  every provider call in every consistency mode. A batched call now takes **one** permit for the whole batch
  — the gate bounds concurrent *calls*, and a batch is one call on one connection however many texts it
  carries. Charging per text would have made the gate throttle batching itself: a 100-text batch against a
  gate of 8 could never acquire enough permits, so bulk seeding would deadlock rather than be bounded.

- **`javai-vector`: `embedAll`'s contract documentation was silently discarded.** OMI-216 inserted
  `maxInputTokens`' javadoc between `embedAll`'s javadoc and `embedAll` itself, so the block bound to nothing
  and the method shipped undocumented. Reattached, and extended to state the ordering requirement that the
  batching work below depends on.

- **`javai-persistence`: realizing a repository after the `SessionFactory` was built no longer fails when it
  introduces nothing new (OMI-214).** `registerEntityType` threw whenever the factory existed, *regardless of
  whether the type was already registered* — so even re-realizing the same repository twice was an error.
  That is also what made "declare your types up front" unable to solve the ordering problem on its own: the
  late call failed however completely the types had been declared. Registration now computes what a call
  *would* add and only refuses when that set genuinely contains something unknown.

- **`javai-persistence`: the late-registration error names the call that built the factory.** It previously
  named only the type that arrived late, which is never the thing a consumer has to change — the fix is
  always to move whatever built the factory, or to stop needing the ordering. The message now reports the
  triggering call site and points at declaring types on the configuration.

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

- **Concatenated text vectoring is now a real, opt-in feature (OMI-191).** `concatenatedTextVector()` was
  woven onto every `@JavAIVectorizable` from the start, but no backend stored it, no query reached it, every
  JavAI collection threw `UnsupportedOperationException` when asked for one, and it never recursed — a
  child's text never reached a parent. It was computed on demand at the price of a real model call, and
  thrown away.

  It now assembles real text across an object graph and embeds it once, which an embedding model can read
  relationships out of in a way arithmetic over separately-embedded fields cannot. Opting in is
  `@Summary(concatenate = true)`, in three independent places: on a **type** (embed my own `@Vectorize`
  fields), on a **field** referencing a vectorizable (absorb that child's text), or on a **field** holding a
  JavAI collection (aggregate its members' text). Default `false`, so nothing changes for existing consumers,
  and a type that declines assembles nothing, embeds nothing, and stores nothing.

  **Assembly is parent-first, and each node contributes exactly once** — a deliberate divergence from
  `summaryVector()`, where a node reachable by two paths stacks additively. Adding a vector twice is a
  meaningful weighting; the same paragraph appearing twice in a string is not, it just skews the embedding.
  The colouring also supplies cycle safety, so diamonds, cycles and self-reference all fall out of one
  mechanism.

  **Assembly is pure string work**, which is what makes the batched pass possible: `precomputeVectors` builds
  every text first and only then reaches the network, so a graph of any shape costs one embedding per
  participating object, in batches. The concatenated pass runs after the field pass so the two never
  interleave.

  **Storage** is on the entity-grain table (`javai_summary_vectors__<model>`), which keeps its name — that
  table now holds two entity-level vectors, and renaming it would be a migration bought for cosmetics. The
  assembled text is stored alongside its vector, so re-embedding under a different model is a pure re-embed
  rather than a fresh walk of the object graph. Idempotent `ALTER TABLE … ADD COLUMN IF NOT EXISTS` covers
  already-deployed tables, which `CREATE TABLE IF NOT EXISTS` would silently skip. Neo4j and MongoDB store
  the same per-entity properties they already use for the summary vector, so neither has a grain problem.

  **`findNearestByConcatenatedTextVector(EmbeddingVector, int)`** searches it, and is rejected at
  repository-creation time for a type that never participates — otherwise it would return an empty list
  forever, indistinguishable from "nothing was similar".

  Every JavAI collection now aggregates its members' text instead of throwing. A collection always *can*
  aggregate; whether it does is the owning field's decision, never the collection's.

- **`javai-vector`: batched `embedAll` on OpenAI, vLLM and TEI (OMI-213).** Ollama was the only bundled
  provider genuinely batching; the other four fell back to the SPI's looping `default` and remained one HTTP
  round trip per text. Batching is a pure latency optimization — `embedAll`'s `default` loops and is already
  correct — so this changes speed, not semantics: at the ~10ms per embed measured in OMI-187, a 1,400-item
  seed is ~14s of *sequential* HTTP versus roughly a dozen batched round trips.

  **The hazard this is built around:** OpenAI's response entries carry an explicit `index` and are documented
  as *not* guaranteed to arrive in request order (vLLM, serving the same contract, schedules across continuous
  batches for its own reasons). Reading rows by array position pairs every text with the wrong vector while
  nothing fails — each vector is well-formed and correctly dimensioned, and the only symptom is a semantic
  index returning subtly wrong neighbours forever. The shared `OpenAiCompatibleEmbeddings` parser therefore
  places rows by `index` and refuses a response whose indices are not exactly one each of `0..n-1`, rather
  than treating the ordering as an assumption. TEI needs no index — its bare array's position *is* the
  correspondence — but its row count is checked, that being its only way to misalign.

  **`EmbeddingProviderReplicate` deliberately keeps the loop.** Its `input` object is shaped by each model's
  own `cog predict()` signature rather than by Replicate, so there is no vendor-wide contract to batch
  against. The bundled default model does accept several texts, but via a field named `texts` (not the `text`
  this provider defaults to) documented only as "formatted as a JSON list of strings" — ambiguous between a
  native array and a JSON-encoded string. Guessing wrong would either error loudly or silently embed the
  literal `["a","b"]` instead of `a` and `b`, and a batching implementation that works for only some models,
  silently, is worse than none.

- **`javai-vector`: embedding providers now know and respect their model's maximum input size (OMI-216).**
  `JavAIEmbeddingProvider.maxInputTokens()` is a new `default` method — no existing implementation breaks —
  resolving through an explicit override, then runtime discovery where the backend can answer (Ollama
  `/api/show`, TEI `/info`, vLLM `/v1/models`), then `EmbeddingModelLimits`, a table of published limits.
  Discovery is cached per instance and degrades to the table on any failure; not knowing the limit precisely
  is a reason to be conservative, never a reason to refuse to embed. All five providers accept an explicit
  limit (a constructor argument, or `Builder.maxInputTokens(int)` on Replicate).

  **What it fixes:** the same text previously produced a correct vector, a quietly partial one, or an
  exception, depending only on which provider was configured. Measured against a live Ollama instance, a
  324,000-character input — roughly 80,000 tokens against `qwen3-embedding:0.6b`'s 32,768-token context —
  returned HTTP 200 and an ordinary 1024-dimension vector, truncated with nothing in the response saying so.
  A truncated vector is by construction indistinguishable from a complete one: it is a plausible embedding of
  a document prefix, and it scores against queries forever without ever looking wrong.

  Text is now bounded client-side on **all five providers uniformly**, at a deliberately pessimistic 3
  characters per token (JavAI has no tokenizer), cutting at a word boundary when one is near. This is a
  trade, not a free win: TEI and Ollama truncate *exactly*, having real tokenizers, so deferring to them
  would preserve more text. Uniformity was chosen because the defect being fixed is precisely that the
  outcome depended on the provider. TEI keeps its `"truncate": true` server-side backstop regardless.

  The fallback for an unrecognized model is **512 tokens**, not the completion side's 8192: embedding models
  run far tighter than chat models, so a generous default would produce partial vectors for exactly the
  models most likely to be missing from the table.

  **Known gap:** truncation is *silent*. Signalling it needs either a breaking change to `EmbeddingVector`
  (45 construction sites) or the project's first logging mechanism — this codebase has none — and both are
  larger decisions than this fix. Not a regression, since Ollama and TEI already truncated with no limit
  knowledge at all, but it should be closed once a logging mechanism is chosen.

- **`javai-persistence`: `JavAIPersistenceConfig.Builder.entityPackages(String...)` / `(Collection)`.**
  Scans the classpath for `@Entity` types under the named packages and registers them when the backend is
  created, so the entity set becomes a property of the **configuration** rather than of the order
  repositories happen to be realized in. Repositories may then be created in any order, at any time; a Spring
  application needs no `@DependsOn` on its factory bean.

  Downstream this was costing real maintenance: `omiai-platform` carried a hand-maintained 18-name
  `@DependsOn` list that had already drifted two entries out of sync with its own bean declarations, and one
  of the missing registrations happened *inside* a library call (`JavAITagRepository.create`), where no
  amount of care reading the configuration would have revealed it.

  Scanning reads class metadata rather than loading classes, so a broad package is cheap and initializes
  nothing. It validates everything it finds, and refusal is deliberate rather than lenient: an entity
  Hibernate maps but JavAI cannot is worse than one that is refused, because the failure would surface later
  and far from its cause. Only three conditions are refused, all about JavAI-owned field types rather than
  about vectors — a JavAI collection field keyed by something other than `String`, a `KnowledgeGraph` field
  (Neo4j-only), and a collection field that is unmapped or is a concrete-typed JavAI collection carrying an
  association annotation. **Being non-vectorized is never one of them**: a plain `@Entity` is a first-class
  citizen of a `JavAIRepository`, registered and served exactly like a vectorized one, it simply has no
  vectors.

- **`javai-persistence`: three ways to exclude a type from scanning.**
  `JavAIPersistenceConfig.Builder.excludeEntityType(Class...)` / `.excludeEntityTypes(Collection)` by class,
  `.excludeEntityPackages(String...)` by package (matching on package boundaries, with a trailing `.*`
  accepted), and **`@PersistenceIgnore`** (`javai-annotations`) on the class itself for a type whose owner
  would rather declare it once than maintain a list elsewhere.

  These are for an `@Entity` that sits inside a scanned package but belongs to a *different* persistence unit
  or `SessionFactory` — never for non-vectorized entities, which are ordinary citizens of a
  `JavAIRepository`. Exclusion applies to **scanning only**: a type named by `entityType(...)` is registered
  regardless, and so is one reached through a registered entity's fields, since Hibernate cannot map the
  referencing entity without it.

  Backed by Spring's `ClassPathScanningCandidateComponentProvider`; `spring-context` is now a declared
  (non-optional) dependency of `javai-persistence` rather than an inherited one.

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

- **Docs: the registration model is now documented where consumers read.** It previously existed only in
  `JavAIPI`'s javadoc — invisible to anyone working from the AI-guidance package, which is precisely the
  audience most likely to add a repository. Now covered in `persistence-support-matrix.md` and
  `JavAI_Usage_Guide.md`, including the Postgres-only scope and the recommended `entityPackages(...)` shape.

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

[Unreleased]: https://github.com/dcaudell/javai/compare/v0.1.9...HEAD
[0.1.9]: https://github.com/dcaudell/javai/compare/v0.1.8...v0.1.9
[0.1.8]: https://github.com/dcaudell/javai/compare/v0.1.7...v0.1.8
[0.1.7]: https://github.com/dcaudell/javai/compare/v0.1.6...v0.1.7
[0.1.6]: https://github.com/dcaudell/javai/compare/v0.1.5...v0.1.6
[0.1.5]: https://github.com/dcaudell/javai/compare/v0.1.4...v0.1.5
