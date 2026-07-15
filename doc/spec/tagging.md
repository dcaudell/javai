# Tagging

Module: `javai-tagging`. Whitepaper: §5.8. Depends on `javai-annotations`, `javai-vector` + `javai-model`
(Tag is itself `@JavAIVectorizable`), `javai-substrate` (this module ships its own pre-woven classes — see
"A new structural situation: this module weaves itself" below), `javai-collections` (`VectorIndex<T>`,
reused rather than reinvented — see "Tag-similarity search" below), `javai-persistence` (three-backend
persisted storage for Tag/TagSet/Tagging and the tag-summary-vector index), and `javai-completion`
(classification is an LLM call via `Cortex`).

Lets any object — `@JavAIVectorizable` or not — carry zero or more Tags, where a Tag is itself a
`@JavAIVectorizable`, recursively taggable object belonging to exactly one TagSet. Two genuinely different
query shapes fall out of this: exact structural lookup ("everything tagged X," "every tag on this
instance") and vector similarity over an object's *whole collection* of applied tags ("what else looks
like this, tag-wise"). Classification — deciding which tags apply to an object — is LLM-based, not
vector-similarity-based, for a specific reason covered below.

## Orthogonality: Taggable is not Vectorizable

`@Taggable`, `@JavAIVectorizable`, and `@JavAIGraphNode` are three independent, freely composable
capabilities — a class can carry any subset of the three, including none. This is a deliberate departure
from how Vector Core's own annotations compose (`@Vectorize` fields only make sense on a
`@JavAIVectorizable` class): tagging is a search/classification concern, not an object-graph-mutation
concern, and nothing about it requires the tagged object to have an embedding of its own.

This single fact drives two other decisions in this document. First, `@Taggable` is a lightweight,
**unwoven** marker annotation — the same shape as `@JavAIGraphNode`, not the fully-woven shape of
`@JavAIVectorizable`. Tagging state doesn't live in fields on the object (there's nothing to weave a
setter around); it lives in persisted association rows, so there's no per-instance mutation to intercept
the way `@Vectorize` field setters need interception. Second, classification must be LLM-based rather than
embedding-similarity-based as the universal mechanism: a strategy that compares the tagged object's own
`vector()` against each candidate tag's vector silently cannot work on any `@Taggable` object that isn't
also `@JavAIVectorizable`, so it can't be the default. An embedding-similarity classifier remains a
reasonable optional strategy for objects that happen to be both — not specified further here, since it
isn't the Phase 0 path.

## Primitives

| Element | Kind | Purpose |
|---|---|---|
| `@Taggable` | Annotation, class | Unwoven marker, declares tagging participation — same shape as `@JavAIGraphNode`, composable with it and with `@JavAIVectorizable` independently |
| `Taggable` | Interface, hand-implemented | Empty marker interface implemented by hand alongside `@Taggable` — exists purely so generic tagging APIs can bound `<T extends Taggable>`, mirroring `<N extends JavAIGraphNode>` on `KnowledgeGraph` |
| `@TagIgnore` | Annotation, field | Excludes an otherwise-`@PromptContext` field from the text a classification prompt sees — see "What the classifier sees" below |
| `Tag` | Class (`@JavAIVectorizable @Taggable`) | Slug (vectorized identity), localized display strings (not vectorized), optional description (not vectorized, classifier context only), owning TagSet |
| `TagSet` | Class (`@JavAIVectorizable`) | A dynamic, persisted collection of Tags — not a compile-time enum. Its own `summaryVector()` is a free, decay-weighted aggregate over its member tags |
| `Tagging` | Persisted association row | Tag + taggable type/UUID + optional affinity + source (manual/auto) — the join table |
| `JavAITagRepository` | Instance wrapper (not a static facade) | `addTag`/`removeTag`/`hasTag`/`tagsOf`/`taggedWith`, classification entry points, tag-similarity index access — wraps an already-realized `TagRepository`; one instance per backend, no ambient/global tagging state anywhere |
| `TaggableRef` | Value type | `(taggableType, taggableId)` — the heterogeneous handle used everywhere a tagged instance is referenced without loading it |

## No global tagging state

`JavAITagRepository` is a plain object, not a static facade -- every field (the wrapped `TagRepository`
delegate, the resolved per-backend `TaggingBackend`, an optional `Cortex` for classification) is set once,
at construction, from arguments the caller supplies explicitly. There is no ambient "current backend"
pointer anywhere for one caller's configuration to silently affect another's.

**Multiple `JavAITagRepository` instances, one per backend/data store, coexist freely** — construct one per
`JavAIPersistenceConfig` you want tagging maintained against, mirroring the same "two independent proxies,
no dual-write" posture `javai-persistence` documents for ordinary multi-backend persistence (see
doc/spec/persistence-bridge.md's own "Persisting one entity type to more than one store at once"). This
module makes **no attempt to keep tag sets in different backends synchronized with each other** — if an
application maintains the same taxonomy in two stores, reconciling them is entirely its own responsibility;
nothing here tries.

## `@Taggable` and `Taggable`, in full

```java
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface Taggable {
}

/** Empty on purpose — see "Orthogonality" above for why there's nothing to weave. */
public interface Taggable {
}
```

```java
@Entity
@Taggable
public class Employee implements Taggable {
    @Id private UUID id;
    @PromptContext private String jobTitle;
    @PromptContext private String department;
    @TagIgnore @PromptContext private String internalNotes;   // visible to completions generally, not to the tag classifier
    // ...
}
```

An object is `@Taggable` the same way it's `@JavAIGraphNode`: annotate it, and hand-implement the marker
interface for type-safe generic use elsewhere (`JavAITagRepository.taggedWith(Tag, List<Class<? extends
Taggable>>)`, etc.). Neither the annotation nor the interface synthesizes any behavior on the class itself
— there is no `addTag()` method on `Employee`. All tagging operations go through a `JavAITagRepository`
instance, the same way persisted state goes through a `JavAIRepository` rather than living as synthesized
instance methods.

## `Tag` and `TagSet`

```java
@Entity
@JavAIVectorizable
@Taggable
public class Tag implements Taggable {

    @Id
    private UUID id;

    @Vectorize
    private String slug;                          // identity + the only field the classifier ever sees

    private Map<String, String> localizedNames;    // locale -> display string; not vectorized, not shown to the classifier

    private String description;                    // optional; not vectorized; classifier context only, see below

    @ManyToOne
    private TagSet tagSet;                          // exactly one owning set

    // slug is fixed at creation -- see "Slug derivation and immutability" below
}

@Entity
@JavAIVectorizable
public class TagSet {

    @Id
    private UUID id;

    @Vectorize
    private String slug;

    private Map<String, String> localizedNames;

    @Summary
    private final JavAIArrayList<Tag> tags = new JavAIArrayList<>();

    // TagSet.summaryVector() is therefore a decay-weighted aggregate over every
    // member tag's own vector() for free -- useful for comparing whole taxonomies
    // to each other, not just individual tags.
}
```

`Tag` is both `@JavAIVectorizable` (it needs a vector to participate in tag-similarity search) and
`@Taggable` (tags are recursively taggable — a Tag can itself carry Tags, from the same or a different
TagSet). Recursive tag-on-tag structure needs the same cycle guard `summaryVector()` already has (a walk
that stops at an already-visited node); no new cycle-safety mechanism is needed, only the same one applied
to a tag-on-tag edge instead of an object-graph field edge.

### Slug derivation and immutability

The slug is derived by slugifying whichever localized string is entered first at Tag creation, and is
**fixed thereafter** — since it's both the vectorized identity and (see "Tag-summary vector index" below)
load-bearing for the tag-similarity index, changing it in place would leave stale vectors and Taggings
pointing at a since-changed identity. "Renaming" a tag's slug is a delete-and-recreate operation, not an
edit.

**Known limitation, accepted, not solved here:** this derivation assumes the first-entered string is
Latin-script. A slug derived from a CJK, Arabic, or other non-Latin-script first entry has no clean
lowercase-and-hyphenate equivalent, and this library deliberately does not attempt transliteration or
translation — consistent with the project's own "no translation logic in the library" constraint. Authors
are expected to enter the English (or otherwise Latin-script) form first for best results; nothing in the
library enforces this, since enforcing it would require exactly the language-detection/translation logic
being avoided. Document this prominently wherever Tag creation is exposed.

### Uniqueness

A Tag's slug is unique **within its owning TagSet**, not globally — two different TagSets may each have a
tag whose slug is `urgent`. This is the one correction worth calling out against the most obvious prior
art (Rails-style tagging libraries, including the sibling implementation this design was evaluated
against, typically enforce global slug uniqueness): global uniqueness would be actively wrong here, since
TagSets are meant to be independent, dynamically-authored taxonomies that shouldn't have to coordinate
vocabulary with each other.

A given Tag has **zero or one** association to a given tagged instance — enforced as a uniqueness
constraint on `Tagging` (tag, taggable type, taggable id), not application logic. This is the only
"duplicate" this library prevents by construction. Two different Tags in the same TagSet that mean nearly
the same thing (different slugs, near-identical semantics) are explicitly **not** proscribed at the
library level — that's a curation problem for the TagSet's author, not a constraint this library enforces.
It is, however, a natural target for an end-to-end test: since Tag is already `@JavAIVectorizable`, finding
semantic near-duplicates within a TagSet is a `similarityTo()` scan over `tags()`, not new mechanism —
worth writing as a diagnostic test even though the library never blocks on it.

## `Tagging`

```java
public class Tagging {
    private UUID id;
    private Tag tag;
    private String taggableType;     // simple class name, resolved the same way JavAIRepository resolves entity types
    private UUID taggableId;          // UUID, matching JavAIRepository's fixed identity convention -- not Jericho's
                                       // ambiguous per-table Long, which is what made its own org-scoped queries
                                       // degenerate into a per-type switch statement (see "Structural queries" below)
    private Double affinity;          // null = binary "has the tag"; present = a real match-strength signal
    private String source;            // "manual" | "auto"
    private Instant createdAt;
}
```

Affinity lives on the association (`Tagging`), not on `Tag` — a tag's strength of match is a property of
one particular application of it, not of the tag itself. Nullable and expected to be null for the common
case; only present when a classifier (or a developer) supplies one. `source` exists so an auto-classifier
run can be diffed and reapplied without disturbing manually-applied tags — see "Classification" below.

## What the classifier sees

Reuses `@PromptContext` (Completion Fabric's existing field-level allowlist for "what does this object
look like to an LLM") rather than introducing a parallel `@TagSource`-style annotation. An object is
classifiable if it has any `@PromptContext` fields; `@TagIgnore` on a field that's otherwise `@PromptContext`
excludes it from the classification prompt specifically, without affecting what that field renders as for
ordinary completions.

This does **not** require changing `javai-completion` or its existing `PromptContext`/`ContextableObject`
marshalling — `@TagIgnore` is a tagging-specific filter with no meaning to general RAG completions, so
`javai-tagging` carries its own small reflection utility ("this class's `@PromptContext` fields, minus any
`@TagIgnore`'d ones, rendered as `label: value`") rather than modifying the shared marshaller. `javai-tagging`
is a pure consumer of `Contextable`/`PromptContext`/`Cortex`, the same relationship `javai-persistence` has
to Vector Core.

The classifier prompt itself sees only each candidate Tag's **slug** — never the localized display strings,
never (by default) the description. This is a deliberate simplification, not an oversight: since slugs are
meant to be stable, English-preferred, machine-facing identifiers, keeping the classifier's candidate list
to slugs alone keeps the prompt language-independent of whichever locale a given deployment's UI happens to
render. `Tag.description`, where present, is available to a classifier-prompt implementation that wants to
give the model more to reason about than a bare slug (worthwhile for terse or ambiguous slugs), but is not
part of the Phase 0 default prompt shape.

## Classification

Client-developer-invoked, never automatically triggered by TagSet mutation. Adding or removing a Tag from
a TagSet does not, by itself, re-run classification against every instance previously classified against
that set — the cost of that (an LLM call per already-tagged instance, potentially a very large fan-out from
a single edit) belongs to whoever owns that tradeoff, not to a side effect the library imposes silently.
Instead, a `JavAITagRepository` instance exposes classification as an easy, explicit call the client makes
when it decides the moment is right:

```java
public final class JavAITagRepository {
    // Single instance, single TagSet -- the unit classification runs at.
    public ClassificationResult classify(Object instance, TagSet tagSet);

    // Convenience batch form -- still one LLM call per instance internally,
    // not a fan-out the caller has to hand-loop themselves.
    public List<ClassificationResult> classifyAll(Collection<?> instances, TagSet tagSet);
}
```

Instance methods, not static ones -- classification needs a `Cortex`, supplied at construction
(`new JavAITagRepository(tagRepository, config, cortex)`), never through an ambient "current Cortex" pointer.

One TagSet per classification call — an object carrying tags from several TagSets is classified against
each set independently, mirroring the sibling implementation's own one-call-per-category shape rather than
trying to fold multiple taxonomies into a single prompt.

Flow: marshal the instance's `@PromptContext` (minus `@TagIgnore`) fields → build a prompt from that text
plus the TagSet's current candidate slugs → call `Cortex` → parse a JSON response of `{slug, affinity?,
reasoning?}` → diff against the instance's existing `source = "auto"` Taggings for *this TagSet specifically*
(add new, update affinity on matches, remove ones no longer returned) → leave `source = "manual"` Taggings
on the same instance completely untouched, even for tags in the same set. Confidence/affinity coming back
from the model is optional in the response schema, consistent with tags being binary by default — a
classifier is free to say "this applies" without a strength score.

## Structural queries

`JavAIRepository` deliberately rejects any derived query beyond `findNearestBy<Field>Vector` — it is not a
general query framework, on purpose. Tag lookups ("every tag on this instance," "every instance across
these types carrying this tag") are exactly the shape of query that contract was built to exclude, so they
live on a `JavAITagRepository` instance instead, as their own, genuinely heterogeneous surface:

```java
JavAIList<Tag> tagsOf(Object instance);
JavAIList<TaggableRef> taggedWith(Tag tag, List<Class<? extends Taggable>> candidateTypes);
void addTag(Object instance, Tag tag);
void addTag(Object instance, Tag tag, double affinity);
void removeTag(Object instance, Tag tag);
boolean hasTag(Object instance, Tag tag);
```

Because JavAI fixes every persistable entity's identity to `UUID` (`JavAIRepository`'s own convention),
`taggedWith` can be one genuinely generic implementation per backend — a single query parameterized by tag
id and a set of type names, not a per-type hand-written join. This is a direct improvement on the sibling
implementation surveyed while designing this area, whose own organization-scoped lookup degenerated into a
`switch` statement over entity type names precisely because its identity column wasn't uniform across
tables.

## Tag-similarity search

The harder requirement — "given a tag, or an ad hoc collection of tags, find other objects (of any type)
whose *own* applied tags look similar" — has no equivalent in `JavAIRepository`'s per-type
`findNearestBy<Field>Vector` convention, and no equivalent in the sibling implementation surveyed for this
design either (its tagging and its embedding capability never spoke to each other). It resolves into an
existing primitive rather than a new one: `VectorIndex<T>` already is, verbatim, "a bare similarity-search
container for cases that don't need full graph semantics" (`javai-collections`'s own doc). This area needs
exactly that, over `TaggableRef` instead of a single entity type:

```java
public record TaggableRef(String taggableType, UUID taggableId) {
}

VectorIndex<TaggableRef> index = tagging.tagSimilarityIndex();  // tagging: a JavAITagRepository instance

JavAIList<TaggableRef> similar = index.nearestN(referenceVector, 20);
```

No new collection interface — `nearestN`/`filterByMinSimilarity` (from `JavAISortable`, which `VectorIndex`
already extends) are exactly the query shape needed. What's new is *how this particular `VectorIndex` is
populated*: not by the caller's own `add()`/`remove()` calls the way an ordinary in-memory `VectorIndex` is
built, but automatically, as a side effect of `JavAITagRepository.addTag()`/`removeTag()` — this realization
is persistence-backed, maintained by the library itself, read-mostly from the caller's perspective. See
"Tag-summary vector index" below for what backs it.

For an ad hoc collection of tags rather than a single reference vector (the original form of this
requirement — "given a collection of tags, possibly from different TagSets, find objects with a
semantically similar collection"), compute a centroid first and query with that:

```java
EmbeddingVector adHoc = VectorMath.centroid(tags.stream().map(Tag::vector).toList());
JavAIList<TaggableRef> similar = index.nearestN(adHoc, 20);
```

### Tag-summary vector index

One vector per tagged instance, aggregating everything it's tagged with — not a per-object-graph-field
`summaryVector()` (an instance need not be `@JavAIVectorizable` at all to have one of these), a wholly
separate, persistence-only concept:

```
tagSummaryVector(instance) = normalize(
    Sum over each Tagging t on instance:
        (t.affinity() != null ? t.affinity() : 1.0) * t.tag().summaryVector()
)
```

Reuses `Tag.summaryVector()` rather than `Tag.vector()` specifically so a tag's own recursive tags
contribute automatically, at the same decay weighting Vector Core already defines — no new formula, the
existing one rooted differently (at a set of Tagging edges instead of object-graph fields). Each term is
further weighted by that Tagging's own affinity, defaulting to 1.0 for the binary case.

**Recomputed eagerly, not lazily, on every `addTag()`/`removeTag()`.** This is a deliberate departure from
Vector Core's usual lazy-recompute-on-read philosophy, which exists specifically to avoid paying for
unnecessary embedding-provider calls. Combining already-cached tag vectors into a weighted sum is pure
arithmetic — no `embed()` call, no provider round trip — so there's no expensive work to defer, and no
third dirty-flag family is needed alongside `FieldDirty`/`SummaryDirty`.

Per-backend realization, following the exact per-model-table precedent `doc/spec/persistence-bridge.md`
already establishes for ordinary vectors:

| Backend | Realization |
|---|---|
| Postgres | `javai_tag_summary_vectors__<model>` — owner type, owner id, vector, model id. Same `ModelIds.sanitize` keying as `javai_vectors__<model>`. |
| Neo4j | A computed node property (`tagSummaryVector__<model>`) on whatever node represents the instance, with its own native vector index — matches the existing `<field>Vector__<model>` node-property convention. |
| MongoDB | A computed document field, indexed via Atlas `$vectorSearch` — matches the existing per-model document-field convention. |

## Persistence, across all three backends

Tags, TagSets, and Taggings are ordinary persisted data, realized per backend the way every other JavAI
entity is — no fourth backend-agnostic abstraction invented here beyond what Persistence Bridge already
provides:

| Backend | Realization |
|---|---|
| Postgres | `tags`, `tag_sets` tables (ordinary Hibernate `@Entity` mapping); `taggings` join table with a unique index on `(tag_id, taggable_type, taggable_id)` and a unique index on `(tag_set_id, slug)` for the per-set slug uniqueness rule above |
| Neo4j | Tag/TagSet as native nodes; a Tagging as a native `TAGGED_WITH` relationship (properties: `affinity`, `source`, `createdAt`) from the tagged node to the Tag node — a more natural fit here than a join-table row, since Neo4j already models associations as first-class relationships |
| MongoDB | Tags stored as an array of `{tagId, affinity, source}` reference pointers on the tagged document, matching the existing `{type, id}` reference-pointer convention `RepositoryBackendSpringDataMongo` already uses for collection-typed fields |

## Optional composition with KnowledgeGraph

Taggable is a universal concern, not a `KnowledgeGraph` specialization — an object doesn't need to be
`@JavAIGraphNode` to be `@Taggable`, and `javai-tagging` doesn't depend on `javai-collections`'s graph types
for its own correctness. Where an instance *does* happen to be both `@Taggable` and `@JavAIGraphNode` (and a
`Tag`, being recursively taggable, can itself be `@JavAIGraphNode`), Tagging associations can optionally be
surfaced into that graph as edges, so `KnowledgeGraph.match()`/`nearestSubgraph()` traversal sees tag
relationships too. This is opt-in composition where it naturally dovetails, per the same orthogonality
principle as the rest of this document — not a required dependency in either direction.

## A new structural situation: this module weaves itself

Every other module that touches `@JavAIVectorizable` either defines the *contract* (`javai-model`) or
provides machinery for a *consumer's* classes to be woven (`javai-persistence`, `javai-collections`, and
`javai-substrate` itself) — none of them ship their own pre-built `@JavAIVectorizable` domain classes as
part of the library. `javai-tagging` is the first one that does: `Tag` and `TagSet` are real,
`@JavAIVectorizable` classes shipped *inside* `javai-tagging.jar`, not defined by whoever depends on it.

That means `javai-tagging`'s own build needs `javai-substrate`'s build-time (Maven-plugin) weaving mode
applied to itself, so `Tag`/`TagSet` arrive in the published jar already woven — a consuming application
gets a working `Tag.vector()` without needing to run a `-javaagent` (or configure its own build-time
weaving) purely to make the library's own shipped types work. Load-time weaving remains how the *consuming
application's own* `@Taggable`/`@JavAIVectorizable` classes get woven, same as today; only this module's own
build has the extra self-weaving step. Flag this explicitly during implementation — it's a new build
concern `javai-substrate`'s existing README doesn't anticipate, since every module before this one only
ever wove other people's code.

## Known limitations (Phase 0)

- Non-Latin-script-first slug derivation is unaddressed by design (see "Slug derivation and immutability").
- Semantic near-duplicate tags within a TagSet (different slugs, similar meaning) are never blocked, only
  discoverable via an ad hoc similarity scan — see "Uniqueness."
- Embedding-similarity-based classification (as opposed to LLM-based) is not specified here; a reasonable
  future addition for objects that are also `@JavAIVectorizable`, deliberately left out of Phase 0 scope.
- The tag-summary vector index has no in-memory analog — it's meaningful only for persisted instances, since
  it's computed from `Tagging` rows, not from anything reachable via the live object graph.
