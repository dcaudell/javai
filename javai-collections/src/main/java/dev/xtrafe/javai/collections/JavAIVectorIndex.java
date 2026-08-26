package dev.xtrafe.javai.collections;

import dev.xtrafe.javai.model.CollectionVectorSupport;
import dev.xtrafe.javai.vector.EmbeddingVector;
import dev.xtrafe.javai.vector.Ranked;
import dev.xtrafe.javai.model.JavAIArrayList;
import dev.xtrafe.javai.model.JavAIList;
import dev.xtrafe.javai.model.JavAIVectorizable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The concrete {@link VectorIndex}. Hand-written, not woven -- a plain, user-instantiated container
 * (like {@code javai-model}'s {@code JavAIArrayList}), so there's nothing here for
 * {@code javai-substrate}'s weaver to do at all. Reuses {@link CollectionVectorSupport#similarityOf} rather
 * than re-deriving the same non-vectorizable-element handling.
 *
 * <h2>Narrowed views share the index they came from (OMI-460)</h2>
 *
 * {@link #ofType(Collection)} returns another {@code JavAIVectorIndex} over the <b>same backing list</b>, carrying the
 * candidate types as a filter -- a live view, not a snapshot, so a chain of narrowings costs nothing per
 * step and an item added to the original afterwards is visible through every view that admits it. Only the
 * unnarrowed index can be mutated: {@link #add}/{@link #remove} on a view refuse, since an item added
 * through a filter it does not satisfy would have to either vanish or break the filter.
 */
public final class JavAIVectorIndex<T> implements VectorIndex<T> {

    private final List<T> items;

    /** Exact runtime classes this view admits, or {@code null} for the unnarrowed index -- deliberately not
     *  an empty set, which {@link #ofType(Collection)} treats as "no type can match" per the {@link VectorIndex}
     *  contract. */
    private final Set<Class<?>> candidateTypes;

    public JavAIVectorIndex() {
        this(new ArrayList<>(), null);
    }

    private JavAIVectorIndex(List<T> items, Set<Class<?>> candidateTypes) {
        this.items = items;
        this.candidateTypes = candidateTypes;
    }

    @Override
    public void add(T item) {
        requireUnnarrowed("add");
        items.add(item);
    }

    @Override
    public boolean remove(T item) {
        requireUnnarrowed("remove");
        return items.remove(item);
    }

    @Override
    public int size() {
        return candidateTypes == null ? items.size() : (int) admitted().count();
    }

    @Override
    public VectorIndex<T> ofType(Collection<? extends Class<?>> narrowTo) {
        Objects.requireNonNull(narrowTo, "candidateTypes");
        Set<Class<?>> narrowed = new LinkedHashSet<>();
        for (Class<?> type : narrowTo) {
            Objects.requireNonNull(type, "candidateTypes must not contain null");
            // Intersects rather than replaces: a type this view already excludes stays excluded, so
            // ofType(A, B).ofType(A) is ofType(A) and ofType(A).ofType(B) matches nothing.
            if (candidateTypes == null || candidateTypes.contains(type)) {
                narrowed.add(type);
            }
        }
        return new JavAIVectorIndex<>(items, narrowed);
    }

    @Override
    public JavAIList<T> nearestN(EmbeddingVector reference, int n) {
        return ranked(reference)
                .limit(n)
                .map(Ranked::entity)
                .collect(Collectors.toCollection(JavAIArrayList::new));
    }

    @Override
    public List<Ranked<T>> nearestNRanked(EmbeddingVector reference, int n) {
        return ranked(reference).limit(n).toList();
    }

    @Override
    public JavAIList<T> filterByMinSimilarity(EmbeddingVector reference, double threshold) {
        return admitted()
                .filter(item -> CollectionVectorSupport.similarityOf(item, reference) >= threshold)
                .collect(Collectors.toCollection(JavAIArrayList::new));
    }

    @Override
    public JavAIList<T> sortByCosineDistance(EmbeddingVector reference) {
        return nearestN(reference, size());
    }

    /** Every admitted, vectorizable item scored against {@code reference}, nearest first -- the one ranking
     *  {@link #nearestN} and {@link #nearestNRanked} both read, so the two cannot order differently. */
    private Stream<Ranked<T>> ranked(EmbeddingVector reference) {
        return admitted()
                .filter(JavAIVectorizable.class::isInstance)
                .map(item -> new Ranked<>(item, CollectionVectorSupport.similarityOf(item, reference)))
                .sorted(Comparator.comparingDouble(Ranked<T>::similarity).reversed());
    }

    /** The items this view admits -- everything, unless narrowed, in which case exact runtime class decides
     *  (see {@link VectorIndex}'s own "What 'of this type' means"). */
    private Stream<T> admitted() {
        if (candidateTypes == null) {
            return items.stream();
        }
        return items.stream().filter(item -> item != null && candidateTypes.contains(item.getClass()));
    }

    private void requireUnnarrowed(String operation) {
        if (candidateTypes != null) {
            throw new UnsupportedOperationException(operation + "(...) is not available on a narrowed view of a "
                    + "JavAIVectorIndex: an item passing through ofType(...) either does not satisfy the filter, "
                    + "and would vanish, or does, and the filter was not what the caller thought it was. Mutate "
                    + "the index this view was narrowed from.");
        }
    }
}
