package dev.xtrafe.javai.tagging;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import dev.xtrafe.javai.collections.VectorIndex;
import dev.xtrafe.javai.completion.CompletionRequest;
import dev.xtrafe.javai.completion.CompletionResult;
import dev.xtrafe.javai.completion.Cortex;
import dev.xtrafe.javai.model.JavAIArrayList;
import dev.xtrafe.javai.model.JavAIList;
import dev.xtrafe.javai.model.JavAIVectorizable;
import dev.xtrafe.javai.persistence.JavAIPI;
import dev.xtrafe.javai.persistence.JavAIPersistenceConfig;
import dev.xtrafe.javai.persistence.JavAIRepository;
import dev.xtrafe.javai.vector.EmbeddingVector;
import dev.xtrafe.javai.vector.VectorMath;

import java.lang.reflect.Type;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
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

    private final JavAIRepository<Tag> delegate;
    private final TaggingBackend backend;
    private final Cortex cortex; // nullable -- only classify()/classifyAll() need one

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

    /** Every instance -- of any of {@code candidateTypes} -- currently tagged with {@code tag}. */
    public JavAIList<TaggableRef> taggedWith(Tag tag, List<Class<? extends Taggable>> candidateTypes) {
        List<String> typeNames = candidateTypes.stream().map(Class::getName).toList();
        JavAIArrayList<TaggableRef> refs = new JavAIArrayList<>();
        refs.addAll(backend.taggedWith(tag.getId(), typeNames));
        return refs;
    }

    /** Binary "has the tag" -- affinity left {@code null}. */
    public void addTag(Object instance, Tag tag) {
        TaggableRef ref = refOf(instance);
        backend.addTag(ref, tag.getId(), null, Tagging.SOURCE_MANUAL);
        recomputeTagSummaryVector(ref);
    }

    public void addTag(Object instance, Tag tag, double affinity) {
        TaggableRef ref = refOf(instance);
        backend.addTag(ref, tag.getId(), affinity, Tagging.SOURCE_MANUAL);
        recomputeTagSummaryVector(ref);
    }

    public void removeTag(Object instance, Tag tag) {
        TaggableRef ref = refOf(instance);
        backend.removeTag(ref, tag.getId());
        recomputeTagSummaryVector(ref);
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
        Set<UUID> candidateIds = new HashSet<>();
        for (Tag candidate : candidates) {
            candidateIds.add(candidate.getId());
        }

        TaggableRef ref = refOf(instance);
        Set<UUID> previousAutoIdsInThisSet = new HashSet<>();
        for (TagAssociation association : backend.associationsOf(ref)) {
            if (Tagging.SOURCE_AUTO.equals(association.source()) && candidateIds.contains(association.tagId())) {
                previousAutoIdsInThisSet.add(association.tagId());
            }
        }

        List<ClassificationResult.AppliedTag> applied = new ArrayList<>();
        Set<UUID> returnedIds = new HashSet<>();
        for (ParsedTag parsedTag : parsed) {
            Tag matched = candidatesBySlug.get(parsedTag.slug());
            if (matched == null) {
                continue; // the model returned a slug outside tagSet's own candidates -- discarded, not an error
            }
            returnedIds.add(matched.getId());
            backend.addTag(ref, matched.getId(), parsedTag.affinity(), Tagging.SOURCE_AUTO);
            applied.add(new ClassificationResult.AppliedTag(matched, parsedTag.affinity(), parsedTag.reasoning()));
        }
        for (UUID previousId : previousAutoIdsInThisSet) {
            if (!returnedIds.contains(previousId)) {
                backend.removeTag(ref, previousId);
            }
        }
        recomputeTagSummaryVector(ref);
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
     * Recomputes and persists {@code ref}'s tag-summary vector -- {@code normalize(sum over each current
     * Tagging t: (t.affinity() ?? 1.0) * t.tag().summaryVector()))}, per doc/spec/tagging.md's "Tag-summary
     * vector index". Called after every {@link #addTag}/{@link #removeTag}/{@link #classify} mutation
     * (eagerly, not lazily -- combining already-cached tag vectors is pure arithmetic, no {@code embed()}
     * call to defer). Deletes the index entry entirely once {@code ref} has zero Taggings left.
     */
    private void recomputeTagSummaryVector(TaggableRef ref) {
        List<TagAssociation> associations = backend.associationsOf(ref);
        if (associations.isEmpty()) {
            backend.deleteTagSummaryVector(ref);
            return;
        }
        float[] sum = null;
        String modelId = null;
        int dims = 0;
        for (TagAssociation association : associations) {
            Tag tag = delegate.findById(association.tagId())
                    .orElseThrow(() -> new IllegalStateException(
                            "Tag " + association.tagId() + " referenced by a Tagging on " + ref + " no longer exists"));
            // Tag doesn't declare `implements JavAIVectorizable` in source -- the weaver adds it at build
            // time (see Tag's own javadoc) -- so summaryVector() is only reachable through the interface.
            EmbeddingVector tagSummary = ((JavAIVectorizable) tag).summaryVector();
            if (sum == null) {
                modelId = tagSummary.modelId();
                dims = tagSummary.dims();
                sum = new float[dims];
            } else if (!tagSummary.modelId().equals(modelId)) {
                throw new IllegalStateException("Cannot combine tag summary vectors from different models ("
                        + modelId + " vs " + tagSummary.modelId() + ") into one tag-summary vector for " + ref);
            }
            float weight = association.affinity() != null ? association.affinity().floatValue() : 1f;
            VectorMath.addWeighted(sum, tagSummary.values(), weight);
        }
        EmbeddingVector combined = new EmbeddingVector(VectorMath.normalize(sum), modelId, dims, Instant.now());
        backend.upsertTagSummaryVector(ref, combined);
    }

    private Cortex cortex() {
        if (cortex == null) {
            throw new IllegalStateException(
                    "This JavAITagRepository was constructed without a Cortex -- classify(...)/classifyAll(...) "
                            + "need the 3-argument constructor (or create(config, cortex)) to supply one");
        }
        return cortex;
    }

    private static TaggableRef refOf(Object instance) {
        return new TaggableRef(instance.getClass().getName(), TaggingReflection.idOf(instance));
    }

    private static TaggingBackend backendFor(JavAIPersistenceConfig config) {
        return switch (config.backend()) {
            case POSTGRES -> new TaggingBackendHibernatePostgres(config);
            case NEO4J -> new TaggingBackendNeo4j(config);
            case MONGODB -> new TaggingBackendSpringDataMongo(config);
        };
    }
}
