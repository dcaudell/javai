package dev.xtrafe.javai.supervision;

/**
 * A blocking, read-write observer of a {@code @SyncSupervision}-woven call. Registered listeners scoped to
 * a given call (see {@link #supportedClass()}) run in registration order, on the calling thread, before the
 * call proceeds to whatever's next (the method body itself for PRE, the caller for POST/EXCEPTION) --
 * exactly the shape described in doc/spec/agentic-supervision.md's "why blocking" section. Each method
 * below reads and, if it wants to intervene, mutates {@code event} directly (see {@link
 * SupervisionEvent}'s setters); the default implementations are no-ops, so a listener only needs to
 * override the pointcut(s) it cares about.
 *
 * <p>A listener that wants to veto a call entirely does so at PRE by throwing -- the same mechanism this
 * project's AoP lineage used, still the simplest correct option since ordinary Java exception propagation
 * already does the right thing (skips the body, unwinds to the caller).
 */
public interface SyncSupervisionListener {

    default void onPre(SupervisionEvent event) {
    }

    default void onPost(SupervisionEvent event) {
    }

    default void onException(SupervisionEvent event) {
    }

    /**
     * Scopes this listener to instances assignable to the returned class -- an unregistered listener never
     * fires at all, regardless of this; this is a coarse filter on top of that, for a listener that's
     * registered globally but only meaningful for part of the object graph. Defaults to {@code
     * Object.class} (fires for every supervised call this listener is registered for).
     */
    default Class<?> supportedClass() {
        return Object.class;
    }
}
