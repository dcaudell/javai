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
 * <h2>⚠️ {@link #modelId} is an assertion, and the reference is the answer (OMI-458)</h2>
 *
 * <b>{@link #reference} alone decides which storage answers</b>, and always has: every backend resolves its
 * table or property from {@code reference.modelId()}. So a summary search in a model that only ever arrives
 * through {@code @ExternalVector} is served with {@link #modelId} left {@code null}, and ranking across two
 * embedding spaces is not a hazard at this layer -- the index is <em>derived from</em> the reference rather
 * than chosen beside it, so the two cannot disagree.
 *
 * <p>What {@code modelId} carries is the caller's own claim about which question they are asking. A container
 * carrying both a {@code @Vectorize} field and an {@code @ExternalVector} has <b>two</b> coherent summaries,
 * and {@code to(album.summaryVector())} versus {@code to(album.summaryVector(PIXELS))} differ by one token --
 * both valid, both correctly ranked against their own storage, so passing the wrong one answers the
 * <em>other</em> of the two questions with nothing to notice. Stating the model turns that into a refusal;
 * see {@link #requireModelAgreement}.
 *
 * <p>So it buys legibility at the call site and a checked assumption, and no capability -- which is exactly
 * why it is optional, why {@code null} is the ordinary case, and why {@code NearestQuery.inModel(...)}
 * refuses every grain where the vector could only ever have come from one model.
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
 * @param modelId    which embedding model's summary to rank against, stated by the caller rather than
 *                   inferred (OMI-458); {@code null} for every search that does not name one, which is
 *                   every search that existed before it and means exactly what it always did
 */
record NearestSpec(
        DerivedQueryMethods.Kind kind,
        String fieldName,
        EmbeddingVector reference,
        int limit,
        int offset,
        List<List<DerivedFinderQuery.BoundPart>> predicate,
        String modelId) {

    /**
     * The model this search ranks in -- <b>the reference's own</b>, unconditionally.
     *
     * <p>Not "the stated one, falling back to the reference": {@link #requireModelAgreement} has already
     * refused any spec where those differ, so there is one answer and reading it off the reference is what
     * makes that visible. A search that states nothing is not a lesser case here; it is the ordinary one.
     */
    String resolvedModelId() {
        return reference.modelId();
    }

    /**
     * Refuses a search whose stated model and reference vector disagree.
     *
     * <p>⚠️ <b>Not protection against ranking across two embedding spaces</b> -- that cannot happen, because
     * the storage is chosen from the reference. It is protection against a caller asking for one of a
     * container's two coherent summaries and passing the other: both are real vectors, both rank correctly
     * against their own storage, and the search would answer the wrong question rather than fail. This is
     * the only point at which that intent is expressed, so it is the only point at which it can be checked.
     */
    void requireModelAgreement() {
        if (modelId != null && !modelId.equals(reference.modelId())) {
            throw new IllegalArgumentException("This search names model '" + modelId
                    + "' but the reference vector reports '" + reference.modelId() + "'. Cosine similarity"
                    + " across two embedding spaces is not a weaker answer, it is no answer -- pass a"
                    + " reference from '" + modelId + "', or name the model the reference actually came"
                    + " from.");
        }
    }

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
