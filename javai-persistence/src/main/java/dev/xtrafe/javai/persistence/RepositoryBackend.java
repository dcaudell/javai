package dev.xtrafe.javai.persistence;

import dev.xtrafe.javai.vector.EmbeddingVector;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The backend-agnostic contract {@link RepositoryInvocationHandler} dispatches to --
 * {@code RepositoryBackendHibernatePostgres} and {@code RepositoryBackendNeo4j} are its two
 * implementations, selected once via {@link JavAIPersistenceConfig.Backend}. Every method operates in
 * terms of the entity's already-woven {@code JavAIVectorizable} vectors (already computed and cached
 * in-memory by the time {@code save} is called) -- this interface only ever moves them into/out of a real
 * store, never recomputes them itself.
 */
interface RepositoryBackend {

    /** Reserved field-name sentinel for the object's own combined {@code vector()} (not a per-field one).
     *  '$'-prefixed reserved names have precedent: {@code JavAIRuntime.STATE_FIELD = "$javai$state"}. */
    String COMBINED_VECTOR_FIELD = "$vector";

    /**
     * Registers {@code entityType} as one this backend must be able to persist. Called for every
     * repository interface {@link JavAIPI#repository(Class)} creates, before any method is actually
     * invoked -- see {@code RepositoryBackendHibernatePostgres}'s javadoc for why registration and first
     * use are different moments (Hibernate's {@code SessionFactory} metadata is immutable once built).
     */
    void registerEntityType(Class<?> entityType);

    Object save(Class<?> entityType, Object entity);

    /**
     * Re-embeds and re-persists <em>every registered entity type</em> under the currently-configured model.
     * A datastore is re-indexed as a whole: an {@code Article}'s {@code Comment}s must be re-embedded too, or
     * the store is left straddling two models. Takes no argument precisely because no single type scopes it.
     *
     * <p>Intentionally abstract: "everything I know about" is backend-specific (which types are registered,
     * and how), so a new backend must answer it deliberately rather than inherit a wrong default.
     */
    void reindexAll();

    /**
     * Re-embeds and re-persists just {@code entityType}. The narrow counterpart to {@link #reindexAll()},
     * for when a caller knowingly wants one type re-embedded and accepts that other types stay on whatever
     * model they were last written under.
     *
     * <p>Backend-agnostic by construction -- {@code save()} always writes under whichever provider is
     * currently configured -- so no backend needs to override this.
     */
    default void reindex(Class<?> entityType) {
        for (Object entity : findAll(entityType)) {
            save(entityType, entity);
        }
    }

    Optional<Object> findById(Class<?> entityType, UUID id);

    List<Object> findAll(Class<?> entityType);

    void deleteById(Class<?> entityType, UUID id);

    /** {@code fieldName} may be {@link #COMBINED_VECTOR_FIELD} for the object's own combined vector. */
    List<Object> findNearestByFieldVector(Class<?> entityType, String fieldName, EmbeddingVector reference, int limit);

    List<Object> findNearestBySummaryVector(Class<?> entityType, EmbeddingVector reference, int limit);

    // ---- ordinary Spring-Data-style derived finders (OMI-138) --------------------------------------
    // These four primitives + validation are all a backend implements; DerivedFinderQuery owns the method
    // name grammar, return-type adaptation, and Pageable/Sort/Limit handling. A backend only translates the
    // BoundPart predicate tree into its native query language and applies the resolved Constraints.

    /** Rejects, at repository-creation time, any derived finder this specific backend structurally cannot
     *  serve -- e.g. a nested property path reaching through a relationship the backend doesn't map for
     *  filtering. The default accepts everything {@link DerivedFinderQuery#parse} already validated;
     *  backends override to add their own store-specific feasibility checks. Must throw
     *  {@code IllegalArgumentException} with a clear, field-naming message, never fail later on first call. */
    default void validateDerivedQuery(Class<?> entityType, DerivedFinderQuery query) {
    }

    /** Runs the derived finder's predicate under the given {@code constraints} (ordering + windowing) and
     *  returns the matching entities, already hydrated the same way {@link #findAll} hydrates. */
    List<Object> findByDerivedQuery(
            Class<?> entityType, DerivedFinderQuery query, Object[] args, DerivedFinderQuery.Constraints constraints);

    /** Counts entities matching the derived finder's predicate (ordering/windowing intentionally ignored). */
    long countByDerivedQuery(Class<?> entityType, DerivedFinderQuery query, Object[] args);

    /** Whether any entity matches the derived finder's predicate. */
    boolean existsByDerivedQuery(Class<?> entityType, DerivedFinderQuery query, Object[] args);

    /** Deletes every entity matching the derived finder's predicate, returning how many were removed. */
    long deleteByDerivedQuery(Class<?> entityType, DerivedFinderQuery query, Object[] args);
}
