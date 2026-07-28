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

[Unreleased]: https://github.com/dcaudell/javai/compare/v0.1.6...HEAD
[0.1.6]: https://github.com/dcaudell/javai/compare/v0.1.5...v0.1.6
[0.1.5]: https://github.com/dcaudell/javai/compare/v0.1.4...v0.1.5
