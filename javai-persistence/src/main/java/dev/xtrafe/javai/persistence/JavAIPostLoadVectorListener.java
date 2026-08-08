package dev.xtrafe.javai.persistence;

import org.hibernate.event.spi.PostLoadEvent;
import org.hibernate.event.spi.PostLoadEventListener;

import java.util.function.BiConsumer;

import org.hibernate.Session;

/**
 * Serves an entity its stored vectors at the moment Hibernate actually loads it (OMI-271).
 *
 * <h2>Why an event, rather than a walk</h2>
 *
 * The cost being avoided is real and was measured in OMI-256: an entity that arrives with empty cache slots
 * is re-embedded on the next save, once per entity, for content that has not changed. The backend used to
 * pay it by walking the loaded root's whole reachable graph and hydrating everything it found -- which
 * bought the property at the price of loading the graph, and that over-fetch is what OMI-271 removed.
 *
 * <p>Neither half of that trade was necessary. Hibernate already knows exactly which entities it has
 * materialized, and says so. Hydrating from the event means the cost is one SELECT per entity <em>actually
 * loaded</em> -- so a read of one entity pays for one entity, and a caller who goes on to initialize a lazy
 * collection pays for its members at the moment they arrive rather than not at all. That last case is the
 * one no walk at load time can cover, because the initialization happens after the walk has finished.
 *
 * <h2>Why the write path suspends it</h2>
 *
 * {@code session.merge(...)} loads the existing row and only then copies the caller's values onto the
 * managed copy -- reflectively, not through the woven setter, so the cache slot is never marked dirty. A
 * hydration that fired during that load would therefore leave the managed copy holding the <em>old</em>
 * vector against the <em>new</em> field value, and the save would persist it: the exact laundering
 * {@code hydrateVectors}' own javadoc describes and {@code savedVectorIsAlwaysAccurateUnderImmediateConsistency}
 * catches. So {@code save()} suspends this for the duration of its own unit of work and does its own
 * hydration, which can tell a caller-held instance from a freshly-loaded one and treats them differently.
 *
 * <p>Suspension is thread-scoped and always restored in a {@code finally}, so it can neither leak past the
 * call nor affect another thread -- the same shape {@link JavAIFlushVectorListener}'s collection window uses.
 */
final class JavAIPostLoadVectorListener implements PostLoadEventListener {

    private static final ThreadLocal<Boolean> SUSPENDED = ThreadLocal.withInitial(() -> Boolean.FALSE);

    /** Hydration is a load-path behavior; a write path that manages its own says so here. */
    static boolean suspend() {
        boolean previous = SUSPENDED.get();
        SUSPENDED.set(Boolean.TRUE);
        return previous;
    }

    static void restore(boolean previous) {
        SUSPENDED.set(previous);
    }

    /** {@code RepositoryBackendHibernatePostgres::hydrateVectors}, passed rather than reached for, so this
     *  listener holds no opinion about where stored vectors live. */
    private final BiConsumer<Session, Object> hydrate;

    JavAIPostLoadVectorListener(BiConsumer<Session, Object> hydrate) {
        this.hydrate = hydrate;
    }

    @Override
    public void onPostLoad(PostLoadEvent event) {
        if (SUSPENDED.get()) {
            return;
        }
        // Fires once the entity is fully initialized, so its @Id is readable and its woven state field
        // exists. hydrateVectors returns immediately for anything that is not a JavAIVectorizable with a
        // stored table under the currently-configured model, which is most of what passes through here.
        hydrate.accept(event.getSession(), event.getEntity());
    }
}
