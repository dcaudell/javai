package dev.xtrafe.javai.vector;

/**
 * One vector-search hit paired with how similar it actually was (OMI-230).
 *
 * <p>A search that returns bare hits is enough when the result is used as-is and not enough the moment a
 * caller has to post-filter, threshold, or show a match strength: a strong 50th match and a weak 5th are
 * indistinguishable, so there is no way to decide whether to fetch more. Declaring a repository query's
 * return type as {@code List<Ranked<T>>} -- or calling {@code NearestQuery.ranked()}, or
 * {@code VectorIndex.nearestNRanked(...)} -- keeps the number the ranking was done on.
 *
 * <h2>Why this lives in {@code javai-vector} (OMI-460)</h2>
 *
 * It was {@code dev.xtrafe.javai.persistence.Ranked} until {@code VectorIndex} needed the same shape.
 * {@code javai-collections} sits <em>below</em> {@code javai-persistence}, so a ranked search surface there
 * could not have named it, and the alternative -- a second record meaning exactly the same thing one module
 * down -- would have left two "hit plus its cosine similarity" types whose only difference was which module
 * happened to declare one first. What the record actually describes is a similarity, which is
 * {@code javai-vector}'s own subject, so it moved to the one module every other module depends on. Nothing
 * about its meaning changed; adopters update an import.
 *
 * <h2>{@code similarity} means cosine similarity, on every backend</h2>
 *
 * In {@code [-1, 1]}, higher is nearer: <b>the same number</b>
 * {@link VectorMath#cosineSimilarity(EmbeddingVector, EmbeddingVector)}
 * and {@code JavAIVectorizable.similarityTo} return in process, so a hit's score can be compared against an
 * in-memory computation, or against a threshold written once, without knowing which store answered.
 *
 * <p>That equivalence is a deliberate normalization rather than a passthrough, because the three stores do not
 * agree with each other: pgvector's {@code <=>} is cosine <em>distance</em> ({@code 1 - cosine}), while Neo4j's
 * and MongoDB's vector indexes both report a score rescaled into {@code (0, 1]} as {@code (1 + cosine) / 2}.
 * Each backend converts to the common convention as it reads its own result, so the difference never reaches
 * a caller. {@link #distance()} is offered for the cosine-distance view.
 *
 * @param entity     the hit itself -- an entity hydrated exactly as {@code findById} would hydrate it when a
 *                   repository answered, or whatever element type the queried {@code VectorIndex} holds
 * @param similarity cosine similarity to the reference vector, in {@code [-1, 1]}, higher being nearer
 */
public record Ranked<T>(T entity, double similarity) {

    /** Cosine <em>distance</em> ({@code 1 - similarity}), in {@code [0, 2]}, lower being nearer -- the same
     *  quantity pgvector's {@code <=>} operator yields, for callers who would rather think in distances. */
    public double distance() {
        return 1.0 - similarity;
    }
}
