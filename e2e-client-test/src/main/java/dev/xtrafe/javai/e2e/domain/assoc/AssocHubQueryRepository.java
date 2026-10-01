package dev.xtrafe.javai.e2e.domain.assoc;

import dev.xtrafe.javai.persistence.JavAIRepository;
import dev.xtrafe.javai.vector.EmbeddingVector;

import java.util.Collection;
import java.util.List;

/**
 * Predicates over {@link AssocHub}'s {@code @Any} associations (OMI-407), against the hardest polymorphic
 * shape this project has: every one of these fields may resolve, per row, to a target that is
 * {@code @JavAIVectorizable} ({@link AssocAnyVectorizable}) or one that is not ({@link AssocAnyPlain}), and
 * one of them is lazy.
 *
 * <p>Separate from {@link AssocHubRepository} for the same reason {@code ArticleQueryRepository} is separate
 * from {@code ArticleRepository}: keeping the query surface that only one backend can serve out of the
 * interface everything else uses. (Here the split is precautionary rather than forced -- {@code AssocHub} is
 * only ever realized against Postgres, since Neo4j and MongoDB refuse `@Any` fields at registration.)
 */
public interface AssocHubQueryRepository extends JavAIRepository<AssocHub> {

    // ---- by target instance: the @Any field is an ordinary property ---------------------------------

    List<AssocHub> findByEagerAny(AssocAnyTarget target);

    List<AssocHub> findByEagerAnyIn(Collection<AssocAnyTarget> targets);

    List<AssocHub> findByEagerAnyIsNull();

    List<AssocHub> findByLazyAny(AssocAnyTarget target);

    // ---- by target type: the OfType keyword ---------------------------------------------------------

    List<AssocHub> findByEagerAnyOfType(Class<?> targetType);

    List<AssocHub> findByEagerAnyOfTypeNot(Class<?> targetType);

    List<AssocHub> findByEagerAnyOfTypeIn(Collection<Class<?>> targetTypes);

    long countByEagerAnyOfType(Class<?> targetType);

    boolean existsByEagerAnyOfType(Class<?> targetType);

    /** Composed with an ordinary predicate on the hub's own column. */
    List<AssocHub> findByEagerAnyOfTypeAndLabel(Class<?> targetType, String label);

    /** Two different `@Any` fields, each with its own target-type predicate, in one query. */
    List<AssocHub> findByEagerAnyOfTypeAndLazyAnyOfType(Class<?> eagerType, Class<?> lazyType);

    /** A lazy `@Any` -- the discriminator lives on the hub's own row, so no target need be loaded to filter. */
    List<AssocHub> findByLazyAnyOfType(Class<?> targetType);

    /** A `@Summary` `@Any`: filtering on it must not disturb the summary vector it feeds. */
    List<AssocHub> findBySummaryLazyAnyOfType(Class<?> targetType);

    /** The same keyword narrowing a real vector search over woven embeddings. */
    List<AssocHub> findNearestByLabelVectorAndEagerAnyOfType(
            EmbeddingVector reference, int limit, Class<?> targetType);
}
