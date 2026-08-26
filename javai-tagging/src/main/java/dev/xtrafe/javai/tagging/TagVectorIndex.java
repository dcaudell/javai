package dev.xtrafe.javai.tagging;

import dev.xtrafe.javai.collections.VectorIndex;
import dev.xtrafe.javai.model.JavAIArrayList;
import dev.xtrafe.javai.model.JavAIList;
import dev.xtrafe.javai.vector.EmbeddingVector;
import dev.xtrafe.javai.vector.Ranked;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * The {@code VectorIndex<TaggableRef>} both {@link JavAITagRepository#tagSimilarityIndex()} and
 * {@link JavAITagRepository#tagTextIndex()} return -- see doc/spec/tagging.md's "Tag-similarity search" and
 * "Concatenated tag text". Deliberately read-mostly: unlike {@code JavAIVectorIndex} (a plain,
 * caller-populated in-memory container), this realization is persistence-backed and maintained
 * automatically as a side effect of {@link JavAITagRepository#addTag}/{@link JavAITagRepository#removeTag}
 * and the tag-text recompute those same calls trigger, so {@link #add}/{@link #remove} -- required by the
 * {@link VectorIndex} contract, but with no legitimate caller here -- simply refuse.
 *
 * <h2>One class for two indexes (OMI-460)</h2>
 *
 * This was two near-identical classes, {@code TagSimilarityVectorIndex} and {@code TagTextVectorIndex},
 * differing only in which pair of {@link TaggingBackend} methods they called. Narrowing would have had to be
 * written into both, so the difference became a {@link Grain} instead -- the same collapse
 * {@code NearestSpec} performed on {@code javai-persistence}'s three {@code findNearestBy*} methods, and for
 * the same reason: two implementations of one contract can drift, and one cannot.
 *
 * <h2>Narrowing reaches the query (OMI-460)</h2>
 *
 * {@link #ofType(Collection)} does not filter results -- it carries a candidate-type list into the backend
 * query, which applies it <b>before</b> the top-N is chosen. That is the whole point: the index spans every
 * {@code @Taggable} type at once, so a caller wanting the nearest 20 albums used to have to draw some
 * multiple of 20 and discard, which is undetectably wrong the moment the discarded majority pushes a real
 * album below the draw.
 */
final class TagVectorIndex implements VectorIndex<TaggableRef> {

    /** Which of the two per-ref vectors this index ranks -- the only difference between what used to be two
     *  classes. */
    enum Grain {
        /** The tag-summary vector: the affinity-weighted sum of the tags an instance carries. */
        TAG_SUMMARY,
        /** The tag-text vector: one real embedding of an instance's concatenated tag text. */
        TAG_TEXT
    }

    private final TaggingBackend backend;
    private final Grain grain;

    /** Fully-qualified names this view admits, or {@code null} for the unnarrowed index. An <em>empty</em>
     *  set is a real state and not the same one: it means {@link #ofType(Collection)} was given no type
     *  still reachable, so nothing can match -- answered here rather than asked of a backend, which reads an
     *  empty list as "unnarrowed" (see {@link TaggingBackend#nearestByTagSummaryVector}). */
    private final Set<String> candidateTypeNames;

    TagVectorIndex(TaggingBackend backend, Grain grain) {
        this(backend, grain, null);
    }

    private TagVectorIndex(TaggingBackend backend, Grain grain, Set<String> candidateTypeNames) {
        this.backend = backend;
        this.grain = grain;
        this.candidateTypeNames = candidateTypeNames;
    }

    @Override
    public void add(TaggableRef item) {
        throw refuse("add");
    }

    @Override
    public boolean remove(TaggableRef item) {
        throw refuse("remove");
    }

    @Override
    public VectorIndex<TaggableRef> ofType(Collection<? extends Class<?>> narrowTo) {
        Objects.requireNonNull(narrowTo, "candidateTypes");
        Set<String> narrowed = new LinkedHashSet<>();
        for (Class<?> type : narrowTo) {
            Objects.requireNonNull(type, "candidateTypes must not contain null");
            // The stored discriminator is the fully-qualified name (TaggableRef.taggableType()), so this is
            // exact-class matching -- the same terms taggedWith/rankedByTags have always taken types on.
            // Intersects rather than replaces, per the VectorIndex contract.
            if (candidateTypeNames == null || candidateTypeNames.contains(type.getName())) {
                narrowed.add(type.getName());
            }
        }
        return new TagVectorIndex(backend, grain, narrowed);
    }

    @Override
    public int size() {
        if (matchesNothing()) {
            return 0;
        }
        List<String> types = queryTypes();
        return grain == Grain.TAG_SUMMARY
                ? backend.tagSummaryVectorCount(types)
                : backend.tagTextVectorCount(types);
    }

    @Override
    public JavAIList<TaggableRef> nearestN(EmbeddingVector reference, int n) {
        JavAIArrayList<TaggableRef> results = new JavAIArrayList<>();
        for (RankedTaggableRef ranked : nearest(reference, n)) {
            results.add(ranked.ref());
        }
        return results;
    }

    @Override
    public List<Ranked<TaggableRef>> nearestNRanked(EmbeddingVector reference, int n) {
        List<RankedTaggableRef> hits = nearest(reference, n);
        List<Ranked<TaggableRef>> results = new ArrayList<>(hits.size());
        for (RankedTaggableRef ranked : hits) {
            // Every backend already normalizes to cosine as it reads its own store's score, so this is a
            // change of record type and nothing else -- Ranked and RankedTaggableRef carry the same number.
            results.add(new Ranked<>(ranked.ref(), ranked.similarity()));
        }
        return results;
    }

    @Override
    public JavAIList<TaggableRef> filterByMinSimilarity(EmbeddingVector reference, double threshold) {
        JavAIArrayList<TaggableRef> results = new JavAIArrayList<>();
        if (matchesNothing()) {
            return results;
        }
        // No threshold query shape exists on any of the three stores, so the whole (narrowed) index is
        // fetched and thresholded here. Narrowed, the bound has to be the narrowed count -- otherwise a
        // threshold over one type would fetch a window sized for every type and lose the tail of its own.
        for (RankedTaggableRef ranked : nearest(reference, Math.max(size(), 1))) {
            if (ranked.similarity() >= threshold) {
                results.add(ranked.ref());
            }
        }
        return results;
    }

    @Override
    public JavAIList<TaggableRef> sortByCosineDistance(EmbeddingVector reference) {
        return nearestN(reference, Math.max(size(), 1));
    }

    private List<RankedTaggableRef> nearest(EmbeddingVector reference, int n) {
        if (matchesNothing()) {
            return List.of();
        }
        List<String> types = queryTypes();
        return grain == Grain.TAG_SUMMARY
                ? backend.nearestByTagSummaryVector(reference, n, types)
                : backend.nearestByTagTextVector(reference, n, types);
    }

    /** Narrowed to no reachable type at all -- answered here, since an empty list means "unnarrowed" to a
     *  backend and asking one would return the whole index. */
    private boolean matchesNothing() {
        return candidateTypeNames != null && candidateTypeNames.isEmpty();
    }

    private List<String> queryTypes() {
        return candidateTypeNames == null ? List.of() : List.copyOf(candidateTypeNames);
    }

    private UnsupportedOperationException refuse(String operation) {
        return new UnsupportedOperationException(operation + "(...) is not available on " + describe()
                + ": it is maintained automatically by JavAITagRepository's own mutation points "
                + "(addTag/removeTag/classify) -- see doc/spec/tagging.md's "
                + (grain == Grain.TAG_SUMMARY ? "Tag-summary vector index" : "Concatenated tag text"));
    }

    private String describe() {
        return grain == Grain.TAG_SUMMARY ? "tagSimilarityIndex()" : "tagTextIndex()";
    }
}
