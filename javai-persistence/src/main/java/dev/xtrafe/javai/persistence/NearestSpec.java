package dev.xtrafe.javai.persistence;

import dev.xtrafe.javai.vector.EmbeddingVector;

import java.util.List;

/**
 * One fully-resolved vector search, as a {@link RepositoryBackend} receives it (OMI-230).
 *
 * <p>Both idioms that can express a vector search -- the {@code findNearestBy…Vector…} method-name
 * convention ({@link DerivedQueryMethods}) and the {@link NearestQuery} builder -- reduce to this record
 * before any backend sees them, which is the point: a backend translates one shape, and the two idioms
 * cannot drift into meaning different things. It plays the same role for vector search that
 * {@link DerivedFinderQuery}'s {@code BoundPart}/{@code Constraints} pair plays for relational finders, and
 * deliberately reuses {@code BoundPart} itself for the predicate rather than inventing a parallel vocabulary.
 *
 * <h2>The ordering contract, which is the whole ticket</h2>
 *
 * {@code limit} applies <b>after</b> {@code predicate}, never before. "The nearest {@code limit} entities
 * that also satisfy the predicate" is a different question from "the entities among the nearest
 * {@code limit} that satisfy the predicate", and only the first is answerable without over-fetching. A
 * backend that cannot honour that ordering must refuse the query rather than approximate it -- see
 * {@link RepositoryBackend#validateNearestQuery}.
 *
 * @param kind       which stored vector to rank against
 * @param fieldName  the {@code @Vectorize} field for {@link DerivedQueryMethods.Kind#FIELD}, or
 *                   {@link RepositoryBackend#COMBINED_VECTOR_FIELD} for the object's own combined vector;
 *                   {@code null} for the summary/concatenated-text kinds, which are entity-grain
 * @param reference  the vector being searched for
 * @param limit      maximum hits to return, applied after the predicate
 * @param offset     how many of the nearest matches to skip first; {@code 0} for an unpaged query
 * @param predicate  OR-of-ANDs relational narrowing, in {@link DerivedFinderQuery}'s own bound form; empty
 *                   for a plain unnarrowed search, which is exactly what the pre-OMI-230 methods produce
 */
record NearestSpec(
        DerivedQueryMethods.Kind kind,
        String fieldName,
        EmbeddingVector reference,
        int limit,
        int offset,
        List<List<DerivedFinderQuery.BoundPart>> predicate) {

    /** Whether this search narrows at all. Every backend supports the {@code false} case; only the ones that
     *  can apply the predicate before the limit support the {@code true} case. */
    boolean isNarrowed() {
        return predicate.stream().anyMatch(group -> !group.isEmpty());
    }

    /** The window a backend must materialize to serve {@code offset}: everything up to the end of the
     *  requested page. Ranking is total, so skipping is exact -- unlike narrowing, paging needs no
     *  backend-specific capability beyond fetching a little more and dropping the head. */
    int limitIncludingOffset() {
        return Math.addExact(limit, offset);
    }
}
