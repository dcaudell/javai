package dev.xtrafe.javai.collections;

import dev.xtrafe.javai.vector.EmbeddingVector;
import dev.xtrafe.javai.vector.Ranked;
import dev.xtrafe.javai.model.JavAIList;
import dev.xtrafe.javai.model.JavAISortable;

import java.util.Collection;
import java.util.List;

/**
 * Bare similarity-search container "for cases that don't need full graph semantics" (doc/spec/
 * vector-collections.md). Deliberately simpler than {@code JavAIList}/{@code JavAISet}: no
 * {@code JavAIVectorizable} of its own (no {@code vector()}/{@code summaryVector()} -- it isn't meant to
 * be a node in the object graph itself, just a standalone lookup structure) and no dependents/dirty
 * tracking. If you need a collection that participates in {@code summaryVector()} propagation, reach for
 * {@code JavAIArrayList}/{@code JavAILinkedHashSet} in {@code javai-model} instead.
 *
 * <h2>Narrowing chains; searching ends the chain (OMI-460)</h2>
 *
 * {@link #ofType(Collection)} returns another {@code VectorIndex} rather than a result, so narrowing composes and the
 * search that ends it is an ordinary search over a smaller index:
 *
 * <pre>{@code
 * JavAIList<TaggableRef> albums = tagging.tagSimilarityIndex()
 *         .ofType(Album.class)
 *         .nearestN(reference, 20);
 * }</pre>
 *
 * This is the shape {@code SubgraphResult extends KnowledgeGraph} already uses one file over: a narrowed
 * result stays the thing it narrowed, so it can be narrowed again and queried by every method the original
 * offered. {@link #nearestN(EmbeddingVector, int, Collection)} is the same query written as one call, for callers
 * who would rather not hold the intermediate.
 *
 * <p><b>Narrowing is not filtering the result.</b> Every realization applies the candidate types
 * <em>before</em> the top-N is chosen -- "the nearest N of these types", never "those of the nearest N that
 * happen to be of these types". The second is what a caller is forced to write when the index cannot be
 * narrowed, and it is silently wrong past the edge of whatever over-fetch multiplier they guessed: an item
 * ranked below the draw is simply absent, undetectably. Removing that is the whole point of this method.
 * The same ordering rule {@code NearestSpec} states for repository vector search, in the collection that
 * has no repository.
 *
 * <h2>What "of this type" means</h2>
 *
 * The <b>exact runtime class</b> of the element -- or, where an index holds references to instances rather
 * than the instances themselves ({@code VectorIndex<TaggableRef>}), of the referenced instance. Not
 * assignability: {@code ofType(Animal.class)} does not match a {@code Dog}. This matches
 * {@code JavAITagRepository.taggedWith}/{@code rankedByTags}, which have taken candidate types on exactly
 * these terms since before this method existed, and it is the only rule a persistence-backed realization can
 * answer without enumerating every loaded subtype of the class it was handed.
 */
public interface VectorIndex<T> extends JavAISortable<T> {

    void add(T item);

    boolean remove(T item);

    int size();

    JavAIList<T> nearestN(EmbeddingVector reference, int n);

    /**
     * {@link #nearestN(EmbeddingVector, int)} keeping the similarity each hit was ranked on (OMI-460).
     *
     * <p>{@code nearestN} returns order and nothing else, which is enough to display a list and not enough
     * to show a match strength, to threshold, or to decide whether to fetch more -- a strong 50th hit and a
     * weak 5th are indistinguishable. The ranked shape is the one {@code NearestQuery.ranked()} and
     * {@code JavAITagRepository.rankedByTags} already return; this was the one search surface without it.
     *
     * @return nearest first, at most {@code n} hits, each with its cosine similarity in {@code [-1, 1]}
     */
    List<Ranked<T>> nearestNRanked(EmbeddingVector reference, int n);

    JavAIList<T> filterByMinSimilarity(EmbeddingVector reference, double threshold);

    /**
     * This index narrowed to items whose own type is one of {@code candidateTypes} -- a view, not a copy,
     * and itself a {@code VectorIndex}, so narrowing composes and every query method above still applies.
     *
     * <p>Narrowing an already-narrowed index <b>intersects</b>: {@code ofType(A, B).ofType(A)} is
     * {@code ofType(A)}, and naming a type the view already excluded leaves an index that matches nothing.
     *
     * <p>Naming <b>no</b> types also matches nothing, rather than meaning "don't narrow" -- the same rule
     * {@code JavAITagRepository.taggedWith(tag, List.of())} already follows. Call the unnarrowed query
     * instead of passing an empty list to say "everything".
     *
     * <p>The returned view is read-only: {@link #add}/{@link #remove} through a filter have no honest
     * meaning (an added item might not satisfy it), so they refuse.
     *
     * @throws NullPointerException if {@code candidateTypes} or any element is null
     */
    VectorIndex<T> ofType(Collection<? extends Class<?>> candidateTypes);

    /** {@link #ofType(Collection)} for the common single-type case -- {@code ofType(Album.class)}. */
    default VectorIndex<T> ofType(Class<?>... candidateTypes) {
        return ofType(List.of(candidateTypes));
    }

    /**
     * The nearest {@code n} items <b>of one of {@code candidateTypes}</b> -- exactly
     * {@code ofType(candidateTypes).nearestN(reference, n)}, for callers who don't want the intermediate.
     *
     * <p>A {@code default} rather than a second implementation, deliberately: the two spellings are the
     * same query, and each realization implements it once, in {@link #ofType(Collection)}, so they cannot
     * drift into answering differently.
     */
    default JavAIList<T> nearestN(EmbeddingVector reference, int n,
            Collection<? extends Class<?>> candidateTypes) {
        return ofType(candidateTypes).nearestN(reference, n);
    }

    /** {@link #nearestNRanked(EmbeddingVector, int)} narrowed to {@code candidateTypes} -- the one signature
     *  that covers both of OMI-460's first two items at once. */
    default List<Ranked<T>> nearestNRanked(EmbeddingVector reference, int n,
            Collection<? extends Class<?>> candidateTypes) {
        return ofType(candidateTypes).nearestNRanked(reference, n);
    }
}
