package dev.xtrafe.javai.supervision;

/**
 * An observer of a {@code @SyncSupervision}/{@code @AsyncSupervision}-woven call, registered at one of the
 * three {@link dev.xtrafe.javai.annotations.SupervisionPointcut}s. One shape, not two: the same {@code
 * onPre}/{@code onPost}/{@code onException} methods serve both the sync and async tiers, since a listener's
 * own code has no reason to differ by tier -- what differs is entirely in <em>how</em> {@link
 * JavAISupervisionRuntime} dispatches to it, which is a property of the registration call, not the
 * interface:
 *
 * <ul>
 *   <li>{@link JavAISupervisionRuntime#registerSyncListener(SupervisionListener)} runs this listener
 *       blocking, on the calling thread, in registration order, before the call proceeds to whatever's next
 *       -- with real read-write access to the {@link SupervisionEvent} it receives (see that class's own
 *       setters). A listener that wants to veto a call entirely does so at PRE by throwing.
 *   <li>{@link JavAISupervisionRuntime#registerAsyncListener(SupervisionListener)} dispatches this listener
 *       fire-and-forget, on a virtual-thread-per-task executor -- the calling thread never waits on it, and
 *       any mutation it makes to the event is discarded (the dispatcher has already committed the final
 *       arguments/return value/throwable, post any sync mutations, by the time an async dispatch sees them).
 *       An exception thrown here is logged and swallowed, never propagated.
 * </ul>
 *
 * <p><b>Registering the very same listener instance both ways is legal, and independent of whether the
 * woven call itself carries one or both of {@code @SyncSupervision}/{@code @AsyncSupervision}</b> -- nothing
 * about registration ties a listener to a particular annotation. A call annotated with both fires the sync
 * tier first (to completion) and then the async tier, observing whatever the sync tier already committed
 * to, per {@code doc/spec/agentic-supervision.md}; a single listener registered via both {@code
 * registerSyncListener} and {@code registerAsyncListener} simply receives both dispatches for such a call,
 * once blocking and once fire-and-forget, exactly as if two separate listeners had been registered one of
 * each way.
 *
 * <p>Default implementations are no-ops, so a listener only needs to override the pointcut(s) it cares
 * about.
 */
public interface SupervisionListener {

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
