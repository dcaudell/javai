package dev.xtrafe.javai.tagging;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import dev.xtrafe.javai.collections.VectorIndex;
import dev.xtrafe.javai.completion.CompletionRequest;
import dev.xtrafe.javai.completion.CompletionResult;
import dev.xtrafe.javai.completion.Cortex;
import dev.xtrafe.javai.model.JavAIArrayList;
import dev.xtrafe.javai.model.JavAIList;
import dev.xtrafe.javai.model.JavAIRuntime;
import dev.xtrafe.javai.model.JavAIVectorizable;
import dev.xtrafe.javai.model.VectorizableString;
import dev.xtrafe.javai.persistence.JavAIPI;
import dev.xtrafe.javai.persistence.JavAIPersistenceConfig;
import dev.xtrafe.javai.persistence.JavAIRepository;
import dev.xtrafe.javai.persistence.PersistentEntities;
import dev.xtrafe.javai.persistence.TaggregateContainment;
import dev.xtrafe.javai.vector.EmbeddingVector;
import dev.xtrafe.javai.vector.VectorMath;

import java.lang.reflect.Type;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Wraps an already-realized {@code JavAIRepository<Tag>} with the tagging-association surface (structural
 * queries/mutations plus classification) that has no equivalent in {@code JavAIRepository}'s own CRUD
 * contract -- the {@code JavAIPI} of this area, but an ordinary instance, not a static facade. Every field
 * is set once, at construction, from arguments the caller supplies explicitly: no ambient "current backend"
 * pointer to switch, race, or accidentally leave misconfigured for a later, unrelated caller.
 *
 * <p><b>Multiple instances, one per backend/data store, coexist freely</b> -- construct one
 * {@code JavAITagRepository} per {@link JavAIPersistenceConfig} you want tagging maintained against, same
 * "two independent proxies, no dual-write" posture {@code javai-persistence} itself documents for ordinary
 * multi-backend persistence (see that module's README). This class makes **no attempt to keep tag sets in
 * different stores synchronized with each other** -- if you maintain the same taxonomy in two backends, you
 * own reconciling them; nothing here tries.
 *
 * <p>Deliberately does **not** implement {@code JavAIRepository<Tag>}: that interface's own contract is
 * "realized via {@code JavAIPI.repository(Class, JavAIPersistenceConfig)}, never implemented by hand" (see
 * its own javadoc), and folding CRUD (save/findById/findAll/deleteById/reindexAll) onto the same type as the
 * association/classification surface would bloat one object with two genuinely separate responsibilities.
 * A caller keeps its own {@link TagRepository} reference (the thing this class wraps as {@link #delegate})
 * for plain {@link Tag} CRUD, and a {@code JavAITagRepository} alongside it for tagging operations --
 * exactly mirroring how {@link TagSetRepository} stays a plain, unwrapped {@code JavAIRepository<TagSet>}
 * with no wrapper of its own, since {@link TagSet} never participates in the association machinery either.
 */
public final class JavAITagRepository {

    // Pure memoization keyed by an explicit argument (identical shape to JavAIPI's own BACKENDS) -- not a
    // reintroduction of ambient state. Without this, two instances built from the *same* config (e.g.
    // create(config) called twice) would each open an independent physical connection/pool (a raw JDBC
    // Connection, a Neo4j Driver, or a MongoClient -- see each TaggingBackend implementation's own
    // constructor), a real resource cost with no correctness benefit.
    private static final Map<JavAIPersistenceConfig, TaggingBackend> BACKENDS = new ConcurrentHashMap<>();
    private static final Gson GSON = new Gson();
    private static final Type PARSED_TAGS_TYPE = new TypeToken<List<ParsedTag>>() { }.getType();
    private static final System.Logger LOG = System.getLogger(JavAITagRepository.class.getName());

    /** The tag-text cap: only the {@code TAG_TEXT_TOP_K} strongest taggings (affinity-descending, then
     *  slug) are rendered and embedded. Deliberate, because provider-side truncation past the model's
     *  input limit is silent (OMI-216) -- better to define exactly which tags make the cut than to let the
     *  provider drop an arbitrary tail. */
    private static final int TAG_TEXT_TOP_K = 50;

    /** How many pending aggregates one drain pass claims at a time. */
    private static final int DRAIN_BATCH = 500;

    private final JavAIRepository<Tag> delegate;
    private final TaggingBackend backend;
    private final Cortex cortex; // nullable -- only classify()/classifyAll() need one
    private final JavAIPersistenceConfig config; // nullable only for the test constructor below
    private volatile TaggregateContainment containment;

    public JavAITagRepository(JavAIRepository<Tag> tagRepository, JavAIPersistenceConfig config) {
        this(tagRepository, config, null);
    }

    /** {@code cortex} is constructor-only, not a settable mutator -- a caller using only the structural
     *  surface (no classification) never needs one, and a {@code final} field set once at construction
     *  removes any cross-thread-visibility question a later {@code configureClassification(...)}-style
     *  mutator would otherwise raise. */
    public JavAITagRepository(JavAIRepository<Tag> tagRepository, JavAIPersistenceConfig config, Cortex cortex) {
        this.delegate = tagRepository;
        this.backend = BACKENDS.computeIfAbsent(config, JavAITagRepository::backendFor);
        this.cortex = cortex;
        this.config = config;
    }

    /** Test-support only: injects the backend directly, so a test can wrap a real one in a counting
     *  decorator and assert the recompute cost discipline (one batched member read) structurally. */
    JavAITagRepository(JavAIRepository<Tag> tagRepository, TaggingBackend backend,
            JavAIPersistenceConfig config, Cortex cortex) {
        this.delegate = tagRepository;
        this.backend = backend;
        this.cortex = cortex;
        this.config = config;
    }

    /** Convenience factory: realizes its own {@link TagRepository} proxy via {@code JavAIPI.repository(...)}
     *  internally. Builds a *new* proxy every call -- distinct from whatever {@code TagRepository} proxy a
     *  caller may already hold for this same config -- consistent with {@code javai-persistence}'s own
     *  "two independent proxies" posture; reuse the {@link #JavAITagRepository(JavAIRepository,
     *  JavAIPersistenceConfig)} constructor directly if you already have a {@link TagRepository} to wrap. */
    public static JavAITagRepository create(JavAIPersistenceConfig config) {
        return new JavAITagRepository(JavAIPI.repository(TagRepository.class, config), config);
    }

    public static JavAITagRepository create(JavAIPersistenceConfig config, Cortex cortex) {
        return new JavAITagRepository(JavAIPI.repository(TagRepository.class, config), config, cortex);
    }

    /** Every {@link Tag} currently applied to {@code instance}, across every {@link TagSet}. */
    public JavAIList<Tag> tagsOf(Object instance) {
        JavAIArrayList<Tag> tags = new JavAIArrayList<>();
        for (UUID tagId : backend.tagIdsOf(refOf(instance))) {
            delegate.findById(tagId).ifPresent(tags::add);
        }
        return tags;
    }

    /**
     * Every tagging on {@code instance}, carrying the metadata a bare {@link Tag} cannot: how strongly it
     * applies, and who applied it.
     *
     * <p>⚠️ {@link #tagsOf} answers "what is this tagged as"; this answers "how, and by whom". The
     * difference matters wherever a classifier is involved: affinity is the match strength a model
     * reported, and {@code source} separates what a person chose ({@link Tagging#SOURCE_MANUAL}) from what
     * a classifier decided ({@link Tagging#SOURCE_AUTO}). Neither is reachable from a {@code Tag}, which
     * is catalogue reference data shared by every object carrying it.
     *
     * <p>A tagging whose {@code Tag} has since been deleted from the catalogue is skipped rather than
     * returned with a null tag — the association is stale, and a caller asking what something is tagged as
     * is not served by an entry that cannot name itself.
     */
    public List<Tagging> taggingsOf(Object instance) {
        TaggableRef ref = refOf(instance);
        List<Tagging> taggings = new ArrayList<>();
        for (TagAssociation association : backend.associationsOf(ref)) {
            delegate.findById(association.tagId()).ifPresent(tag -> taggings.add(new Tagging(
                association.tagId(), tag, ref.taggableType(), ref.taggableId(),
                association.affinity(), association.source(), null)));
        }
        return taggings;
    }

    /** Every instance -- of any of {@code candidateTypes} -- currently tagged with {@code tag}. */
    public JavAIList<TaggableRef> taggedWith(Tag tag, List<Class<? extends Taggable>> candidateTypes) {
        List<String> typeNames = candidateTypes.stream().map(Class::getName).toList();
        JavAIArrayList<TaggableRef> refs = new JavAIArrayList<>();
        refs.addAll(backend.taggedWith(tag.getId(), typeNames));
        return refs;
    }

    /** Binary "has the tag" -- affinity left {@code null}. */
    public void addTag(Object instance, Tag tag) {
        requireSlug(tag);
        Object resolved = PersistentEntities.resolve(instance);
        TaggableRef ref = refOf(resolved);
        backend.addTag(ref, tag.getId(), null, Tagging.SOURCE_MANUAL);
        recomputeDerivedVectors(ref, resolved.getClass());
        markAndDrain(ref);
    }

    public void addTag(Object instance, Tag tag, double affinity) {
        requireSlug(tag);
        Object resolved = PersistentEntities.resolve(instance);
        TaggableRef ref = refOf(resolved);
        backend.addTag(ref, tag.getId(), affinity, Tagging.SOURCE_MANUAL);
        recomputeDerivedVectors(ref, resolved.getClass());
        markAndDrain(ref);
    }

    /**
     * Refuses to index a tag with no slug (OMI-201).
     *
     * <p>A backstop, not the main defence: {@link Tag}'s constructors already refuse to produce a slugless
     * tag, so the only way to hold one is to hydrate a row written before that rule existed. Applying it
     * would put a tag with an absent vector into the tag-summary index, where it would contribute nothing
     * and be findable by nothing -- so this fails at the point of use rather than storing something inert.
     */
    private static void requireSlug(Tag tag) {
        if (tag.getSlug() == null || tag.getSlug().isBlank()) {
            throw new IllegalStateException("Tag " + tag.getId() + " has no slug, so it has no vector and"
                    + " cannot be indexed or searched. Tags created through this library's constructors"
                    + " always have one; this tag was most likely hydrated from a row written before that"
                    + " was enforced. Re-create it with a localized name that yields a slug.");
        }
    }

    public void removeTag(Object instance, Tag tag) {
        Object resolved = PersistentEntities.resolve(instance);
        TaggableRef ref = refOf(resolved);
        backend.removeTag(ref, tag.getId());
        recomputeDerivedVectors(ref, resolved.getClass());
        markAndDrain(ref);
    }

    public boolean hasTag(Object instance, Tag tag) {
        return backend.hasTag(refOf(instance), tag.getId());
    }

    /**
     * The persistence-backed {@code VectorIndex<TaggableRef>} over every tagged instance's tag-summary
     * vector -- see doc/spec/tagging.md's "Tag-similarity search". Maintained automatically as a side
     * effect of {@link #addTag}/{@link #removeTag}/{@link #classify}; the returned index's own {@code add}/
     * {@code remove} refuse (see {@link TagSimilarityVectorIndex}'s own javadoc).
     */
    public VectorIndex<TaggableRef> tagSimilarityIndex() {
        return new TagSimilarityVectorIndex(backend);
    }

    /**
     * Classifies {@code instance} against {@code tagSet}'s current candidate tags via {@link Cortex} --
     * client-invoked only, never triggered automatically by a {@link TagSet} edit (see doc/spec/tagging.md's
     * "Classification"). Marshals {@code instance}'s {@code @PromptContext} fields (minus any
     * {@code @TagIgnore}'d ones -- see {@link ClassifierContext}), builds a prompt showing the model only
     * {@code tagSet}'s candidate slugs (never localized names or descriptions), and parses the response as
     * a JSON array of {@code {slug, affinity?, reasoning?}}.
     *
     * <p>Diffs the result against {@code instance}'s existing {@code source = "auto"} associations *for
     * this TagSet specifically*: a returned slug is added or has its affinity updated; an existing auto
     * association for a tag in this set that the model didn't return this time is removed. A slug the model
     * returns that isn't one of {@code tagSet}'s own candidates is silently ignored (the model hallucinated
     * a tag outside what it was shown -- not an error, just discarded). {@code source = "manual"} taggings
     * on the same instance, including ones for tags in this very set, are never read or touched.
     *
     * @throws IllegalStateException if this instance was constructed without a {@link Cortex}
     */
    public ClassificationResult classify(Object instance, TagSet tagSet) {
        List<Tag> candidates = tagSet.getTags();
        String prompt = buildClassificationPrompt(ClassifierContext.marshal(instance), candidates);
        CompletionResult result = cortex().complete(CompletionRequest.builder().prompt(prompt).build());
        List<ParsedTag> parsed = parseClassificationResponse(result.text());

        Map<String, Tag> candidatesBySlug = new HashMap<>();
        for (Tag candidate : candidates) {
            candidatesBySlug.put(candidate.getSlug(), candidate);
        }

        List<ClassificationResult.AppliedTag> results = new ArrayList<>();
        for (ParsedTag parsedTag : parsed) {
            Tag matched = candidatesBySlug.get(parsedTag.slug());
            if (matched == null) {
                // The model returned a slug outside tagSet's own candidates -- discarded here rather than
                // passed on, because a hallucination is expected noise from a model and applyClassification
                // treats an off-set tag as a caller bug. See that method's own javadoc.
                continue;
            }
            results.add(new ClassificationResult.AppliedTag(matched, parsedTag.affinity(), parsedTag.reasoning()));
        }
        return applyClassification(instance, tagSet, results);
    }

    /**
     * Applies an <b>already-computed</b> classification -- the reconciliation half of {@link #classify},
     * reachable without a {@link Cortex}.
     *
     * <p>Exists because a classifier need not be an LLM. An image tagger (RAM++, OMI-289) returns
     * {@code (slug, confidence)} from its own model, out of process, and everything worth having about
     * {@code classify} is on this side of the model call: the diff against this instance's existing
     * {@code source = "auto"} associations <em>for this TagSet specifically</em> -- a returned tag is added
     * or has its affinity updated, one no longer returned is removed, and {@code source = "manual"} taggings
     * are never read or touched, including for tags in this very set. Duplicating that reconciliation for a
     * second kind of classifier would be a second implementation free to disagree with the first.
     *
     * <p><b>One recomputation of the tag-summary vector per call, not one per tag.</b> That is the whole
     * cost difference against looping {@link #addTag}: {@link #recomputeTagSummaryVector} resolves every
     * association through {@code findById}, so N sequential {@code addTag} calls cost ~N²/2 id lookups.
     * A classifier returning twenty tags does one recomputation here and twenty there.
     *
     * <p><b>⚠️ Every tag must belong to {@code tagSet}</b>, and an off-set tag throws rather than being
     * discarded -- deliberately the opposite of how {@link #classify} treats a hallucinated slug. The
     * asymmetry is about who is wrong: a model inventing a slug is expected noise, whereas a caller passing
     * a tag from another set is a bug with a silent, durable consequence. The diff's removal scan is scoped
     * to this set's own tags, so such a tag would be applied as {@code auto} and then never be retractable
     * by any later classification run -- a permanent automatic tag no classifier can take back.
     *
     * @param instance the object being classified
     * @param tagSet   the taxonomy this run is scoped to; the diff never reaches outside it
     * @param results  what the classifier decided -- affinity and reasoning both optional
     * @throws IllegalArgumentException if any result's tag belongs to a different {@link TagSet}
     */
    public ClassificationResult applyClassification(Object instance, TagSet tagSet,
            List<ClassificationResult.AppliedTag> results) {
        Set<UUID> candidateIds = new HashSet<>();
        for (Tag candidate : tagSet.getTags()) {
            candidateIds.add(candidate.getId());
        }
        for (ClassificationResult.AppliedTag result : results) {
            requireSlug(result.tag());
            if (!candidateIds.contains(result.tag().getId())) {
                throw new IllegalArgumentException("Tag '" + result.tag().getSlug() + "' does not belong to TagSet '"
                        + tagSet.getSlug() + "', so applying it would create an automatic tagging that no later"
                        + " classification of this set could ever remove. Classify against the TagSet the tag"
                        + " actually belongs to, or add the tag to this set first.");
            }
        }

        Object resolved = PersistentEntities.resolve(instance);
        TaggableRef ref = refOf(resolved);
        Set<UUID> previousAutoIdsInThisSet = new HashSet<>();
        for (TagAssociation association : backend.associationsOf(ref)) {
            if (Tagging.SOURCE_AUTO.equals(association.source()) && candidateIds.contains(association.tagId())) {
                previousAutoIdsInThisSet.add(association.tagId());
            }
        }

        List<ClassificationResult.AppliedTag> applied = new ArrayList<>();
        Set<UUID> returnedIds = new HashSet<>();
        for (ClassificationResult.AppliedTag result : results) {
            if (!returnedIds.add(result.tag().getId())) {
                // The same tag twice in one classification: addTag is idempotent per (ref, tagId) so this
                // would merely overwrite, but reporting it twice would misrepresent one association as two.
                continue;
            }
            backend.addTag(ref, result.tag().getId(), result.affinity(), Tagging.SOURCE_AUTO);
            applied.add(result);
        }
        for (UUID previousId : previousAutoIdsInThisSet) {
            if (!returnedIds.contains(previousId)) {
                backend.removeTag(ref, previousId);
            }
        }
        recomputeDerivedVectors(ref, resolved.getClass());
        markAndDrain(ref);
        return new ClassificationResult(ref, tagSet, applied);
    }

    /** Convenience batch form -- still one {@link Cortex} call per instance internally, not a fan-out the
     *  caller has to hand-loop themselves. */
    public List<ClassificationResult> classifyAll(Collection<?> instances, TagSet tagSet) {
        List<ClassificationResult> results = new ArrayList<>(instances.size());
        for (Object instance : instances) {
            results.add(classify(instance, tagSet));
        }
        return results;
    }

    // ---- Taggregate (OMI-304) -- see doc/spec/tagging.md's "Taggregate: derived taggings for containers" --

    /**
     * Rebuilds every container's aggregate from its members, unconditionally -- <b>the only public
     * Taggregate entry point</b>, and deliberately not something a normal application calls.
     *
     * <p>⚠️ <b>This is the after-a-restore repair.</b> Ordinary operation needs nothing: tagging a member
     * maintains every container holding it, automatically, through the mutation choke points. What this
     * exists for is the writes those choke points cannot observe -- a backup restored underneath the
     * application, an entity promoted from another environment, rows inserted by direct SQL. After any of
     * those the aggregates are wrong with nothing to notice, and this is what makes them right again.
     *
     * <p>Cost is proportional to the world, so it belongs on a maintenance path, never a request one.
     *
     * @return how many containers were visited
     */
    public int rebuildTaggregates() {
        List<TaggableRef> containers = new ArrayList<>();
        containment().allContainers((type, id) -> containers.add(new TaggableRef(type, id)));
        for (TaggableRef container : containers) {
            recomputeAggregate(container);
        }
        // Recomputing a nested container marks whatever holds it, so draining here is what makes one pass
        // converge regardless of the order the containers came back in -- no "assets before albums" rule
        // for a caller to know, which is exactly the kind of ordering an adopter should never have to own.
        drainPendingTaggregates();
        return containers.size();
    }

    /**
     * The {@code @Taggregate} containment of the registered model -- which containers hold a member, and
     * which members a container holds -- resolved lazily on first use.
     *
     * <p>⚠️ <b>This replaced a membership snapshot table</b> (OMI-304). That table was written <em>by
     * reconciliation</em>, so a container nothing had reconciled yet appeared in no snapshot and tagging its
     * members marked nothing: the incremental path could not start without a full bootstrap pass someone
     * had to remember to run. Containment is read from the join tables that already hold the membership, so
     * it is true the first time it is asked, and cold start stops being a concept.
     */
    private TaggregateContainment containment() {
        TaggregateContainment resolved = containment;
        if (resolved == null) {
            synchronized (this) {
                resolved = containment;
                if (resolved == null) {
                    resolved = config == null
                            ? TaggregateContainment.NONE   // test-constructed against a bare backend
                            : JavAIPI.taggregateContainment(config);
                    containment = resolved;
                }
            }
        }
        return resolved;
    }

    /**
     * What every mutation choke point does once its own write is done: mark the containers holding
     * {@code mutated} as owing a recompute, then drain -- <b>after the caller's transaction commits</b>,
     * following {@code @Summary}'s own precedent exactly.
     *
     * <p>The distinction is not cosmetic. Inside a caller's transaction the tag row is not visible to any
     * other connection yet, so an aggregate computed now would be computed from a state nothing else can
     * see -- and if that transaction rolls back, from one that never existed. When there is no ambient
     * transaction the write is already committed and the drain runs inline.
     */
    private void markAndDrain(TaggableRef mutated) {
        if (containment().isEmpty()) {
            return;   // nothing in this model declares @Taggregate; pay nothing for it
        }
        containment().containersOf(mutated.taggableType(), mutated.taggableId(),
                (type, id) -> backend.enqueueTaggregatePending(new TaggableRef(type, id)));
        if (!containment().afterCommit(this::drainPendingTaggregates)) {
            drainPendingTaggregates();
        }
    }

    /**
     * Recomputes every aggregate the pending queue names, and everything containing them.
     *
     * <p>Upward one hop at a time, from what containment says holds each recomputed container -- never from
     * an object graph, which the draining thread may not hold at all. The visited set bounds the walk and
     * is also the cycle guard: mutual containment terminates rather than recursing, per
     * doc/spec/tagging.md's "cycles are tolerated, not resolved".
     *
     * <p>Rows are deleted by id once their work is done, never by aggregate: a mutation committed while
     * this drain was running enqueued a new row for the same container, and that row describes a change
     * this pass may not have included -- the {@code PendingSummaries} rule, for the same reason.
     */
    private void drainPendingTaggregates() {
        while (true) {
            TaggregatePendingClaim claim = backend.claimTaggregatePending(DRAIN_BATCH);
            if (claim.isEmpty()) {
                return;
            }
            Set<TaggableRef> visited = new HashSet<>();
            Deque<TaggableRef> queue = new ArrayDeque<>(claim.aggregates());
            while (!queue.isEmpty()) {
                TaggableRef container = queue.poll();
                if (!visited.add(container)) {
                    continue;
                }
                if (recomputeAggregate(container)) {
                    containment().containersOf(container.taggableType(), container.taggableId(),
                            (type, id) -> queue.add(new TaggableRef(type, id)));
                }
            }
            backend.deleteTaggregatePending(claim.rowIds());
            if (claim.rowIds().size() < DRAIN_BATCH) {
                return;
            }
        }
    }

    /**
     * The Taggregate recompute itself -- a set-based store operation over {@code container}'s members,
     * needing no container instance and no session held by the caller.
     *
     * <p>⚠️ <b>That is a defect class, not a preference.</b> This used to walk the container object's
     * {@code @Taggregate} fields, which are ordinarily lazy {@code @ManyToMany} collections -- so an entity
     * read outside a session threw {@code LazyInitializationException} the moment the recompute touched
     * one, and an adopter's first boot pass failed for every album while still reporting success, because
     * each failure was caught per object. A query cannot have that bug.
     *
     * <p>Inputs are the union of the members' taggings of <b>every</b> source -- including a member's own
     * aggregate rows, which is what makes nesting compose one level at a time, recursion-free -- read in
     * <b>one batched query</b>. Self-exclusion: where a container is its own member (a containment cycle),
     * its own {@code aggregate} rows are not inputs to its own recompute. Per-tag affinity is the mean
     * contribution over members ({@code Σ contribution / |members|}, a member's null affinity counting 1.0,
     * an absent tag counting 0) -- coverage × strength, bounded [0,1], diluted by untagged members.
     *
     * <p>The diff writes only changed rows and only its own {@code source = "aggregate"} provenance, never
     * a {@code manual} or {@code auto} row on the container.
     *
     * @return whether any row changed -- what tells the drain to carry on upward
     */
    private boolean recomputeAggregate(TaggableRef container) {
        List<TaggableRef> members = new ArrayList<>();
        containment().membersOf(container.taggableType(), container.taggableId(),
                (type, id) -> members.add(new TaggableRef(type, id)));

        Map<TaggableRef, List<TagAssociation>> byMember = backend.associationsOfAll(members);
        Map<UUID, Double> sums = new LinkedHashMap<>();
        for (TaggableRef member : members) {
            for (TagAssociation association : byMember.getOrDefault(member, List.of())) {
                if (member.equals(container) && Tagging.SOURCE_AGGREGATE.equals(association.source())) {
                    continue;
                }
                sums.merge(association.tagId(),
                        association.affinity() != null ? association.affinity() : 1.0, Double::sum);
            }
        }
        Map<UUID, Double> target = new LinkedHashMap<>();
        for (Map.Entry<UUID, Double> entry : sums.entrySet()) {
            target.put(entry.getKey(), entry.getValue() / members.size());
        }

        Map<UUID, Double> existing = new HashMap<>();
        for (TagAssociation association : backend.associationsOf(container)) {
            if (Tagging.SOURCE_AGGREGATE.equals(association.source())) {
                existing.put(association.tagId(), association.affinity());
            }
        }
        boolean changed = false;
        for (Map.Entry<UUID, Double> entry : target.entrySet()) {
            Double current = existing.get(entry.getKey());
            if (current == null || current.doubleValue() != entry.getValue()) {
                backend.addTag(container, entry.getKey(), entry.getValue(), Tagging.SOURCE_AGGREGATE);
                changed = true;
            }
        }
        for (UUID previousId : existing.keySet()) {
            if (!target.containsKey(previousId)) {
                backend.removeTag(container, previousId);
                changed = true;
            }
        }
        if (changed) {
            recomputeDerivedVectors(container, typeOf(container));
        }
        return changed;
    }

    /** The container's own class, for the tag-text opt-in check. A type this JVM cannot load (a row left by
     *  a renamed or since-removed class) simply does not concatenate, rather than failing a repair pass. */
    private static Class<?> typeOf(TaggableRef ref) {
        try {
            return Class.forName(ref.taggableType());
        } catch (ClassNotFoundException e) {
            LOG.log(System.Logger.Level.WARNING, () -> "Taggregate container type " + ref.taggableType()
                    + " is not on this classpath; its aggregate rows are still maintained, but it cannot be"
                    + " checked for the @Taggregate(concatenate = true) tag-text opt-in.");
            return Object.class;
        }
    }


    // ---- Concatenated tag text and ranked tag queries (OMI-302) ----------------------------------------

    /**
     * The stored tag text for {@code instance} under the currently configured embedding model, or
     * {@code null} if none is stored (the type never opted in via {@code @Taggregate(concatenate = true)},
     * or it has no taggings). Reconciles first when {@code instance} is a stale aggregate -- same rule as
     * {@link #taggingsOf}: an operation holding the object trues it up.
     */
    public String tagText(Object instance) {
        TaggableRef ref = refOf(instance);
        return backend.tagText(ref, requireModelId());
    }

    /** The stored tag-text vector for {@code instance} under the currently configured embedding model, or
     *  {@code EmbeddingVector.absent()} if none is stored. Reconciles first, exactly as {@link #tagText}. */
    public EmbeddingVector tagTextVector(Object instance) {
        TaggableRef ref = refOf(instance);
        return backend.tagTextVector(ref, requireModelId());
    }

    /**
     * The persistence-backed {@code VectorIndex<TaggableRef>} over every opted-in instance's tag-text
     * vector -- the tag-text sibling of {@link #tagSimilarityIndex()}, and the fuzzy language-side query
     * surface: embed a statement, {@code nearestN} it, get back the refs whose <em>tags read most like
     * it</em>. Maintained automatically at the same trigger points as the tag-summary vector; the returned
     * index's own {@code add}/{@code remove} refuse.
     */
    public VectorIndex<TaggableRef> tagTextIndex() {
        return new TagTextVectorIndex(backend);
    }

    /**
     * Exact multi-tag ranking: every instance (of one of {@code candidateTypes}) carrying at least one of
     * {@code tags}, scored {@code Σ} of its affinity for each query tag it carries ({@code null} counting
     * 1.0), descending -- one indexed query over the taggings, exact and explainable, freely spanning
     * {@link TagSet}s. The structural counterpart to running {@link #tagQueryVector} through
     * {@link #tagSimilarityIndex()}.
     */
    public List<RankedTaggableRef> rankedByTags(List<Tag> tags, List<Class<? extends Taggable>> candidateTypes,
            int limit) {
        List<UUID> tagIds = tags.stream().map(Tag::getId).toList();
        List<String> typeNames = candidateTypes.stream().map(Class::getName).toList();
        return backend.rankedByTags(tagIds, typeNames, limit);
    }

    /**
     * An ad hoc query vector from a collection of tags -- the same weighted-sum construction the
     * tag-summary vector uses (every weight 1.0, since a query tag carries no affinity), so querying
     * {@link #tagSimilarityIndex()} with it is the fuzzy variant of {@link #rankedByTags}. Absent if every
     * tag's summary vector is absent.
     */
    public EmbeddingVector tagQueryVector(List<Tag> tags) {
        List<VectorMath.WeightedVector> terms = new ArrayList<>(tags.size());
        for (Tag tag : tags) {
            terms.add(new VectorMath.WeightedVector(((JavAIVectorizable) tag).summaryVector(), 1.0));
        }
        return VectorMath.normalize(VectorMath.weightedSum(terms));
    }

    private static String requireModelId() {
        String modelId = JavAIRuntime.currentModelId();
        if (modelId == null) {
            throw new IllegalStateException("No embedding model is configured (JavAIRuntime.currentModelId()"
                    + " is null), so there is no model realization of the tag-text store to read from."
                    + " Configure an embedding provider first.");
        }
        return modelId;
    }

    private static String buildClassificationPrompt(String context, List<Tag> candidates) {
        String slugs = candidates.stream().map(Tag::getSlug).collect(Collectors.joining(", "));
        return "You are a tagging classifier. Given the object described below, decide which of the "
                + "candidate tags apply to it. Respond with ONLY a JSON array, no prose, no markdown "
                + "fences, in exactly this shape: "
                + "[{\"slug\": \"...\", \"affinity\": 0.0, \"reasoning\": \"...\"}, ...]. "
                + "\"affinity\" is an optional 0-1 match-strength score -- omit it (or use null) if you "
                + "have no opinion on strength. \"reasoning\" is an optional short justification. Only use "
                + "slugs from the candidate list below; never invent a new one. If none apply, respond "
                + "with an empty array: [].\n\n"
                + "Candidate tags (slugs only): " + slugs + "\n\n"
                + "Object:\n" + context;
    }

    private static List<ParsedTag> parseClassificationResponse(String text) {
        int start = text.indexOf('[');
        int end = text.lastIndexOf(']');
        if (start < 0 || end < start) {
            throw new IllegalStateException("Classifier response did not contain a JSON array: " + text);
        }
        List<ParsedTag> parsed = GSON.fromJson(text.substring(start, end + 1), PARSED_TAGS_TYPE);
        return parsed == null ? List.of() : parsed;
    }

    private record ParsedTag(String slug, Double affinity, String reasoning) {
    }

    /**
     * Recomputes and persists {@code ref}'s derived vectors: always the tag-summary vector --
     * {@code normalize(sum over each current Tagging t: (t.affinity() ?? 1.0) * t.tag().summaryVector()))},
     * per doc/spec/tagging.md's "Tag-summary vector index" -- and, when {@code type} declares
     * {@code @Taggregate(concatenate = true)}, the tag-text vector too, from the same one read of the
     * associations and one load of their Tags. Called after every {@link #addTag}/{@link #removeTag}/
     * {@link #classify} mutation and every Taggregate reconcile (eagerly, not lazily -- combining
     * already-cached tag vectors is pure arithmetic; the tag-text embed is one provider call and happens
     * only for opted-in types). Deletes the index entries entirely once {@code ref} has zero Taggings left.
     */
    private void recomputeDerivedVectors(TaggableRef ref, Class<?> type) {
        boolean concatenates = TaggregateReflection.concatenates(type);
        List<TagAssociation> associations = backend.associationsOf(ref);
        if (associations.isEmpty()) {
            backend.deleteTagSummaryVector(ref);
            if (concatenates) {
                backend.deleteTagTextVector(ref);
            }
            return;
        }
        Map<UUID, Tag> tags = new LinkedHashMap<>();
        for (TagAssociation association : associations) {
            tags.put(association.tagId(), delegate.findById(association.tagId())
                    .orElseThrow(() -> new IllegalStateException(
                            "Tag " + association.tagId() + " referenced by a Tagging on " + ref + " no longer exists")));
        }

        // Each tag's summary vector, weighted by its association's affinity (OMI-218). Hand-rolled before,
        // which is how it came to crash on an absent tag summary: a bare float[] cannot say "there is no
        // vector here", so an absent tag either sized the accumulator to zero dimensions (yielding a
        // zero-dim vector that then got stored as a real tag-summary) or, if it arrived after a present one,
        // blew up with ArrayIndexOutOfBoundsException from inside the add loop. VectorMath skips absent
        // terms, so a tag with no embeddable content simply contributes nothing.
        List<VectorMath.WeightedVector> terms = new ArrayList<>(associations.size());
        for (TagAssociation association : associations) {
            // Tag doesn't declare `implements JavAIVectorizable` in source -- the weaver adds it at build
            // time (see Tag's own javadoc) -- so summaryVector() is only reachable through the interface.
            double weight = association.affinity() != null ? association.affinity() : 1.0;
            terms.add(new VectorMath.WeightedVector(
                    ((JavAIVectorizable) tags.get(association.tagId())).summaryVector(), weight));
        }

        EmbeddingVector combined;
        try {
            combined = VectorMath.normalize(VectorMath.weightedSum(terms));
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(
                    "Cannot combine tag summary vectors into one tag-summary vector for " + ref
                            + ": " + e.getMessage(), e);
        }
        if (combined.isAbsent()) {
            // Every tag on this ref had an absent summary, so there is nothing to index. Delete rather than
            // store a content-free vector that would sit in an ANN index matching arbitrary queries -- the
            // same rule the zero-associations case above already follows.
            backend.deleteTagSummaryVector(ref);
        } else {
            backend.upsertTagSummaryVector(ref, combined);
        }

        if (concatenates) {
            recomputeTagTextVector(ref, associations, tags);
        }
    }

    /**
     * Renders and embeds {@code ref}'s tag text -- deterministic by construction (doc/spec/tagging.md's
     * "Concatenated tag text"): display names ({@code en}, slug fallback), ordered affinity-descending then
     * slug, joined {@code ", "}, capped at {@link #TAG_TEXT_TOP_K}. The embed goes through
     * {@link VectorizableString} -- the same globally configured provider every other JavAI embed uses, so
     * the tag-text vector lands in the same model space as the caption/bio text it is meant to be queried
     * against. A provider failure under {@code RETURN_NULL} leaves the previously stored text/vector in
     * place rather than deleting it -- the tags still exist; only this recompute failed.
     */
    private void recomputeTagTextVector(TaggableRef ref, List<TagAssociation> associations, Map<UUID, Tag> tags) {
        record TextEntry(String display, String slug, double weight) {
        }
        List<TextEntry> entries = new ArrayList<>(associations.size());
        for (TagAssociation association : associations) {
            Tag tag = tags.get(association.tagId());
            String display = tag.getLocalizedNames().get("en");
            entries.add(new TextEntry(
                    display == null || display.isBlank() ? tag.getSlug() : display,
                    tag.getSlug(),
                    association.affinity() != null ? association.affinity() : 1.0));
        }
        entries.sort(Comparator.comparingDouble(TextEntry::weight).reversed().thenComparing(TextEntry::slug));
        String text = entries.stream().limit(TAG_TEXT_TOP_K).map(TextEntry::display)
                .collect(Collectors.joining(", "));

        EmbeddingVector vector = new VectorizableString(text).vector();
        if (vector == null || vector.isAbsent()) {
            return;
        }
        backend.upsertTagTextVector(ref, text, vector);
    }

    private Cortex cortex() {
        if (cortex == null) {
            throw new IllegalStateException(
                    "This JavAITagRepository was constructed without a Cortex -- classify(...)/classifyAll(...) "
                            + "need the 3-argument constructor (or create(config, cortex)) to supply one");
        }
        return cortex;
    }

    /**
     * The {@link TaggableRef} for {@code instance}, resolving a persistence proxy to the real entity first.
     *
     * <p>⚠️ <b>Both halves of a ref are wrong for an unresolved proxy</b>, and both fail silently. A
     * Hibernate proxy is a generated subclass, so {@code getClass().getName()} answers
     * {@code Target$HibernateProxy$xyz} and the association is filed under an owner type nothing can ever
     * look up again; and the proxy holds no state of its own, so field reflection reads a {@code null}
     * {@code @Id} from it whether or not it has been initialized. Neither shows up at the call site -- the
     * write succeeds and every later {@code hasTag}/{@code tagsOf}/{@code taggedWith} simply answers "no".
     *
     * <p>This is not hypothetical for a domain that tags entities reached through associations: an
     * {@code @Any(fetch = LAZY)} or lazy {@code @ManyToOne} hands back exactly such a proxy, so
     * {@code addTag(parent.getChild(), tag)} was the shape that hit it. {@link PersistentEntities#resolve}
     * unwraps it; {@link TaggingReflection#idOf} catches anything that still arrives without an id.
     */
    private static TaggableRef refOf(Object instance) {
        Object resolved = PersistentEntities.resolve(instance);
        return new TaggableRef(resolved.getClass().getName(), TaggingReflection.idOf(resolved));
    }

    private static TaggingBackend backendFor(JavAIPersistenceConfig config) {
        return switch (config.backend()) {
            case POSTGRES -> new TaggingBackendHibernatePostgres(config);
            case NEO4J -> new TaggingBackendNeo4j(config);
            case MONGODB -> new TaggingBackendSpringDataMongo(config);
        };
    }
}
