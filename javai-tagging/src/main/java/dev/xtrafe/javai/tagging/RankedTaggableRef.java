package dev.xtrafe.javai.tagging;

/**
 * A {@link TaggableRef} paired with the score that ranked it. For a vector query
 * ({@link TaggingBackend#nearestByTagSummaryVector}/{@link TaggingBackend#nearestByTagTextVector}) the
 * score is cosine similarity in {@code [-1, 1]} -- the same number {@code JavAIVectorizable.similarityTo}
 * returns in process, on every backend (each converts its own store's score as it reads it, per OMI-460),
 * which lets {@link TagVectorIndex#filterByMinSimilarity} apply its own threshold without a second,
 * separate backend query shape and lets a caller compare a hit against a threshold written once. For the structural
 * {@code JavAITagRepository#rankedByTags} query it is the exact sum of the ref's affinities for the query
 * tags ({@code null} counting 1.0) -- unbounded above, not a similarity, but the same "ref plus how
 * strongly" shape. Public since {@code rankedByTags} returns it (OMI-302).
 */
public record RankedTaggableRef(TaggableRef ref, double similarity) {
}
