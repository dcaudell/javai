package dev.xtrafe.javai.supervision;

/**
 * A non-blocking, observation-only witness of a {@code @AsyncSupervision}-woven call. Dispatched
 * fire-and-forget on a virtual-thread-per-task executor (see {@code JavAISupervisionRuntime}) -- the
 * calling thread never waits on this, regardless of how long a listener takes, which is deliberately where
 * a long-running "let an agent go do something else" reaction belongs rather than in a {@link
 * SyncSupervisionListener}. See doc/spec/agentic-supervision.md.
 *
 * <p>Any mutation this listener makes to the {@link SupervisionEvent} it receives is discarded -- the
 * dispatcher has already committed the final arguments/return value/throwable (post any {@link
 * SyncSupervisionListener} mutations) by the time an async listener sees them. This isn't an oversight:
 * an async listener can, by design, run well after the triggering call has already returned to its caller,
 * so there is no longer a call in flight for a mutation to apply to.
 *
 * <p>An exception thrown from one of these methods is logged and swallowed, never propagated -- the
 * calling thread has typically moved on by the time this runs, and an unreliable (e.g. LLM-backed) listener
 * failing shouldn't be able to affect anything outside itself.
 */
public interface AsyncSupervisionListener {

    default void onPre(SupervisionEvent event) {
    }

    default void onPost(SupervisionEvent event) {
    }

    default void onException(SupervisionEvent event) {
    }

    /** Same scoping contract as {@link SyncSupervisionListener#supportedClass()}. */
    default Class<?> supportedClass() {
        return Object.class;
    }
}
