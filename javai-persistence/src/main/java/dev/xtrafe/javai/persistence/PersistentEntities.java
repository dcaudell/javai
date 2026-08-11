package dev.xtrafe.javai.persistence;

import org.hibernate.Hibernate;

/**
 * Resolves a persistent instance to the real object behind it -- the one thing a caller outside this module
 * needs from Hibernate without taking a dependency on Hibernate itself.
 *
 * <p><b>Why this is public where {@link EntityReflection} is not.</b> {@code javai-tagging} builds a
 * {@code TaggableRef} from an instance's class name and {@code @Id} field, and both are wrong for a proxy:
 * {@code getClass()} answers {@code Target$HibernateProxy$xyz} rather than {@code Target}, and the
 * {@code @Id} <em>field</em> on a proxy is never populated -- a proxy delegates through an interceptor
 * rather than holding state -- so field reflection reads {@code null} from it whether it has been
 * initialized or not. The same pair of failures {@code RepositoryBackendHibernatePostgres} documents on its
 * own related-entity write path (OMI-161), one module up. Rather than have {@code javai-tagging} take a
 * Hibernate dependency it otherwise has no use for (its own Postgres backend is raw JDBC), the knowledge
 * stays here and is exposed as one method -- the same call {@code ModelIds.sanitize} already makes for
 * per-model naming.
 *
 * <p><b>⚠️ This initializes an uninitialized proxy, deliberately -- unlike the persistence walks.</b> Those
 * skip one, and are right to: an association nobody touched holds no mutation to flush, so resolving it
 * would be a SELECT issued purely to discover there was nothing to do. Tagging is the opposite situation.
 * It is an explicit act on one named instance, and it cannot be performed at all without knowing which
 * instance that is -- so the load is the point rather than a cost to avoid. A detached uninitialized proxy
 * therefore raises {@code LazyInitializationException} here, which is the correct outcome and a strict
 * improvement on silently filing an association under a type name nothing can ever look up again.
 */
public final class PersistentEntities {

    private PersistentEntities() {
    }

    /**
     * {@code instance} itself when it is an ordinary object, or the entity behind it when it is a proxy.
     *
     * <p>A no-op for anything Hibernate did not create, so a caller on the Neo4j or MongoDB backend -- where
     * no proxy exists -- can route through this unconditionally rather than branching on backend.
     */
    public static Object resolve(Object instance) {
        return instance == null ? null : Hibernate.unproxy(instance);
    }
}
