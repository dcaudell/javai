package dev.xtrafe.javai.persistence;

import org.hibernate.event.spi.PostDeleteEvent;
import org.hibernate.event.spi.PostDeleteEventListener;
import org.hibernate.event.spi.PreInsertEvent;
import org.hibernate.event.spi.PreInsertEventListener;
import org.hibernate.event.spi.PreUpdateEvent;
import org.hibernate.event.spi.PreUpdateEventListener;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Records which entities Hibernate <em>actually</em> inserted, updated, or deleted during a flush, so
 * {@code RepositoryBackendHibernatePostgres} can write (or clean up) their vectors afterwards -- including
 * entities it never sees itself, because Hibernate reached them by cascading an association at arbitrary
 * depth. Before this, vector writes came only from a one-level reflective walk of the saved object, so a
 * {@code @JavAIVectorizable} two hops away persisted relationally but silently had no vector row.
 *
 * <p><b>Why it only collects, and never writes.</b> {@code PreInsert}/{@code PreUpdate} fire in the middle
 * of Hibernate's flush. Issuing this project's own vector SQL there would mean running arbitrary statements
 * inside a flush that is still in progress (and risks re-entering the very flush that triggered it). Instead
 * the listener records the entity instances, and the backend writes their vectors after {@code flush()}
 * returns but before commit -- so the vector rows still land in the same transaction as the entity, keeping
 * the "the database never sees a vector inconsistent with the field value written in the same flush"
 * guarantee that {@code JavAIRuntime.runWithSubgraphLockedForPersistence} exists to provide.
 *
 * <p><b>Why it is a supplement, not a replacement.</b> Hibernate skips the UPDATE entirely for an entity
 * whose mapped columns are unchanged, so no event fires -- which is precisely the case
 * {@link JavAIRepository#reindexAll()} depends on (it re-saves entities whose relational state is unchanged
 * by definition, purely to re-embed them under a newly configured model). The backend therefore keeps its
 * explicit vector-write path as well; this listener adds depth coverage on top, it does not replace it.
 * Proven empirically in {@code PhaseZeroSpikeTest}'s Gate 3, which pins that behavior with an assertion.
 *
 * <p><b>Scoped, so a shared SessionFactory is safe.</b> Collection is only active between {@link #begin()}
 * and {@link #end()} on the calling thread. Registered on a {@code SessionFactory} the application owns
 * (see {@code JavAIPersistenceConfig.Builder.sessionFactory}), the listener is inert for every session the
 * application drives itself.
 */
final class JavAIFlushVectorListener
        implements PreInsertEventListener, PreUpdateEventListener, PostDeleteEventListener {

    /** One flush window's observations: entities written (identity-based, they may not implement equals)
     *  and the type/id of everything deleted. */
    record Flushed(Set<Object> persisted, List<DeletedRef> deleted) {
    }

    record DeletedRef(String ownerType, UUID id) {
    }

    private static final ThreadLocal<Flushed> ACTIVE = new ThreadLocal<>();

    /** Starts collecting on this thread. Always pair with {@link #end()} in a {@code finally}. */
    static void begin() {
        ACTIVE.set(new Flushed(Collections.newSetFromMap(new IdentityHashMap<>()), new ArrayList<>()));
    }

    /** What has been observed so far in this window, or an empty result if collection isn't active. */
    static Flushed current() {
        Flushed flushed = ACTIVE.get();
        return flushed != null ? flushed
                : new Flushed(Collections.newSetFromMap(new IdentityHashMap<>()), List.of());
    }

    static void end() {
        ACTIVE.remove();
    }

    @Override
    public boolean onPreInsert(PreInsertEvent event) {
        recordPersisted(event.getEntity());
        return false; // never veto
    }

    @Override
    public boolean onPreUpdate(PreUpdateEvent event) {
        recordPersisted(event.getEntity());
        return false; // never veto
    }

    @Override
    public void onPostDelete(PostDeleteEvent event) {
        Flushed flushed = ACTIVE.get();
        if (flushed != null && event.getEntity() != null && event.getId() instanceof UUID id) {
            flushed.deleted().add(new DeletedRef(event.getEntity().getClass().getName(), id));
        }
    }

    private static void recordPersisted(Object entity) {
        Flushed flushed = ACTIVE.get();
        if (flushed != null && entity != null) {
            flushed.persisted().add(entity);
        }
    }
}
