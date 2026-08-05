package dev.xtrafe.javai.persistence;

import dev.xtrafe.javai.vector.VectorMath;

/**
 * One vector-search hit paired with how similar it actually was (OMI-230).
 *
 * <p>{@code findNearestBy…Vector} returns bare entities, which is enough when the result is used as-is and
 * not enough the moment a caller has to post-filter: a strong 50th match and a weak 5th are indistinguishable,
 * so there is no way to decide whether to fetch more. Declaring the query's return type as
 * {@code List<Ranked<T>>} -- or calling {@link NearestQuery#ranked()} -- keeps the number the ranking was
 * done on.
 *
 * <h2>{@code similarity} means cosine similarity, on every backend</h2>
 *
 * In {@code [-1, 1]}, higher is nearer: <b>the same number</b>
 * {@link VectorMath#cosineSimilarity(dev.xtrafe.javai.vector.EmbeddingVector, dev.xtrafe.javai.vector.EmbeddingVector)}
 * and {@code JavAIVectorizable.similarityTo} return in process, so a hit's score can be compared against an
 * in-memory computation, or against a threshold written once, without knowing which store answered.
 *
 * <p>That equivalence is a deliberate normalization rather than a passthrough, because the three stores do not
 * agree with each other: pgvector's {@code <=>} is cosine <em>distance</em> ({@code 1 - cosine}), while Neo4j's
 * and MongoDB's vector indexes both report a score rescaled into {@code (0, 1]} as {@code (1 + cosine) / 2}.
 * Each backend converts to the common convention as it reads its own result, so the difference never reaches
 * a caller. {@link #distance()} is offered for the cosine-distance view.
 *
 * @param entity     the hit itself, hydrated exactly as {@code findById} would hydrate it
 * @param similarity cosine similarity to the reference vector, in {@code [-1, 1]}, higher being nearer
 */
public record Ranked<T>(T entity, double similarity) {

    /** Cosine <em>distance</em> ({@code 1 - similarity}), in {@code [0, 2]}, lower being nearer -- the same
     *  quantity pgvector's {@code <=>} operator yields, for callers who would rather think in distances. */
    public double distance() {
        return 1.0 - similarity;
    }
}
