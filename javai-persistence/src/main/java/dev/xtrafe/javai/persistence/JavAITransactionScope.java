package dev.xtrafe.javai.persistence;

import org.hibernate.Session;
import org.hibernate.SessionFactory;

/**
 * The thread-bound session opened by {@link JavAIPI#inTransaction}, so that every {@code JavAIRepository}
 * call made inside that body runs on one session and commits or rolls back once, as a unit -- OMI-146's
 * programmatic-transaction half, for callers who are not running under Spring.
 *
 * <p>Deliberately keyed by {@link SessionFactory}: two independently-configured backends can be in scope on
 * the same thread (nothing stops an application from holding one config per database), and a session opened
 * against one of them must never be handed to the other. {@link #current(SessionFactory)} returns
 * {@code null} rather than the wrong session when the factories don't match, which degrades to this
 * module's ordinary open-a-session-per-call behavior instead of corrupting a unit of work.
 *
 * <p>A {@code ThreadLocal} is the right shape here even though this repository's coding standard prefers
 * instance state to ambient state ({@code SPEC.md}): the thing being scoped <em>is</em> "what is this
 * thread currently inside of," which is not expressible as a constructor argument to a repository proxy the
 * caller obtained before the transaction existed. This is the same reason Spring's own
 * {@code TransactionSynchronizationManager} is thread-bound. It is scoped strictly to
 * {@link JavAIPI#inTransaction}'s own try/finally, never left set, and never a configuration pointer.
 */
final class JavAITransactionScope {

    private record Scope(SessionFactory factory, Session session) {
    }

    private static final ThreadLocal<Scope> CURRENT = new ThreadLocal<>();

    private JavAITransactionScope() {
    }

    /** The session this thread is inside a {@code JavAIPI.inTransaction} body for, or {@code null} -- also
     *  {@code null} when the active scope belongs to a different {@link SessionFactory}. */
    static Session current(SessionFactory factory) {
        Scope scope = CURRENT.get();
        return scope != null && scope.factory() == factory ? scope.session() : null;
    }

    /** Whether any scope is active on this thread, regardless of which factory owns it. */
    static boolean isActive() {
        return CURRENT.get() != null;
    }

    /**
     * Binds {@code session} as this thread's unit of work.
     *
     * <p>The guard is an internal invariant check, not a user-facing rule: a nested
     * {@code JavAIPI.inTransaction} never reaches this method, because the caller resolves the ambient
     * session first and joins it (Spring's {@code PROPAGATION_REQUIRED} semantics). Reaching here with a
     * scope already set would mean one leaked from an earlier body, which is a bug in this class's own
     * try/finally rather than anything the caller did.
     */
    static void begin(SessionFactory factory, Session session) {
        if (CURRENT.get() != null) {
            throw new IllegalStateException("A JavAI transaction scope is already bound to this thread -- an "
                    + "earlier JavAIPI.inTransaction(...) body must have failed to clear it. This is a bug in "
                    + "JavAI, not in calling code: nested inTransaction calls are expected to join the outer "
                    + "scope without ever reaching this method.");
        }
        CURRENT.set(new Scope(factory, session));
    }

    static void end() {
        CURRENT.remove();
    }
}
