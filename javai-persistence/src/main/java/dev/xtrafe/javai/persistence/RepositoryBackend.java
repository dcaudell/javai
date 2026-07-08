package dev.xtrafe.javai.persistence;

import dev.xtrafe.javai.runtime.EmbeddingVector;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The backend-agnostic contract {@link RepositoryInvocationHandler} dispatches to --
 * {@code HibernatePostgresRepositoryBackend} and {@code Neo4jRepositoryBackend} are its two
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
     * invoked -- see {@code HibernatePostgresRepositoryBackend}'s javadoc for why registration and first
     * use are different moments (Hibernate's {@code SessionFactory} metadata is immutable once built).
     */
    void registerEntityType(Class<?> entityType);

    Object save(Class<?> entityType, Object entity);

    Optional<Object> findById(Class<?> entityType, UUID id);

    List<Object> findAll(Class<?> entityType);

    void deleteById(Class<?> entityType, UUID id);

    /** {@code fieldName} may be {@link #COMBINED_VECTOR_FIELD} for the object's own combined vector. */
    List<Object> findNearestByFieldVector(Class<?> entityType, String fieldName, EmbeddingVector reference, int limit);

    List<Object> findNearestBySummaryVector(Class<?> entityType, EmbeddingVector reference, int limit);
}
