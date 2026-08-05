package dev.xtrafe.javai.persistence;

import jakarta.transaction.Status;
import jakarta.transaction.Synchronization;
import org.hibernate.Session;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Defers a summary recomputation to the moment someone else's transaction commits (OMI-255).
 *
 * <p>When JavAI owns the transaction it simply recomputes after its own {@code commit()} returns. When it
 * has <em>joined</em> one -- a {@code JavAIPI.inTransaction} body, or a Spring {@code @Transactional}
 * method -- {@code save} returns while the transaction is still open, and there is no later point in JavAI's
 * own code to hook. Recomputing there would be wrong twice over: it would read a graph nothing else can see
 * yet, and it would write the shared summary row from inside the very transaction this whole mechanism
 * exists to keep it out of.
 *
 * <p>So the work is accumulated for the duration of that transaction and run from a commit callback. Two
 * consequences worth stating, because both are deliberate:
 *
 * <ul>
 *   <li><b>One callback per transaction, not per save.</b> A loop saving two hundred entities inside one
 *       {@code inTransaction} block recomputes once at the end, over the union of what it touched, rather
 *       than opening two hundred transactions after the fact.</li>
 *   <li><b>Nothing runs on rollback.</b> The queue rows were written on the caller's own connection and roll
 *       back with it, so an abandoned transaction leaves neither a queued recomputation nor a performed
 *       one.</li>
 * </ul>
 *
 * <p>Thread-bound for the same reason {@link JavAITransactionScope} is, and with the same standing against
 * this repository's preference for explicit state: what is being scoped is "the transaction this thread is
 * currently inside," which no constructor argument can express -- the repository proxy was obtained long
 * before the transaction existed. It is scoped to one transaction, cleared by the callback that consumes it,
 * and is never a configuration pointer.
 */
final class SummaryDrainScope {

    /**
     * Keyed by backend, not merely by thread. Two independently-configured backends -- one per database, a
     * shape this module explicitly supports -- can both be reached inside one transaction on one thread, and
     * each must drain against its own store. A single shared accumulator would hand one backend's owners to
     * the other's drain, which would look for them in a database that has never heard of them.
     */
    private static final ThreadLocal<Map<Object, Set<Containment.OwnerRef>>> PENDING =
            ThreadLocal.withInitial(HashMap::new);

    private SummaryDrainScope() {
    }

    /**
     * Adds {@code owners} to the work this thread's current transaction owes {@code backend}, registering
     * the commit callback the first time it is called for that backend within that transaction.
     *
     * @param drain what to run, once, with everything accumulated by the time the transaction commits
     */
    static void afterCommit(Object backend, Session ambient, Set<Containment.OwnerRef> owners,
            Consumer<Set<Containment.OwnerRef>> drain) {
        Map<Object, Set<Containment.OwnerRef>> byBackend = PENDING.get();
        Set<Containment.OwnerRef> pending = byBackend.get(backend);
        if (pending != null) {
            pending.addAll(owners); // the callback is already registered and will pick these up
            return;
        }
        Set<Containment.OwnerRef> accumulated = new LinkedHashSet<>(owners);
        byBackend.put(backend, accumulated);
        try {
            ambient.getTransaction().registerSynchronization(new Synchronization() {
                @Override
                public void beforeCompletion() {
                    // Nothing: the point of this class is to act strictly after the commit, never before it.
                }

                @Override
                public void afterCompletion(int status) {
                    forget(backend);
                    if (status == Status.STATUS_COMMITTED) {
                        drain.accept(accumulated);
                    }
                }
            });
        } catch (RuntimeException e) {
            // The transaction would not take a callback (an implementation that doesn't support them, or one
            // already completing). Leave the queue rows to a later drain rather than recomputing now, which
            // would be the one thing that is definitely wrong: reading a graph that is not committed.
            forget(backend);
            throw e;
        }
    }

    /** Clears this backend's accumulator, and the map itself once nothing is left in it -- a
     *  {@code ThreadLocal} on a pooled request thread that is only ever added to is a leak. */
    private static void forget(Object backend) {
        Map<Object, Set<Containment.OwnerRef>> byBackend = PENDING.get();
        byBackend.remove(backend);
        if (byBackend.isEmpty()) {
            PENDING.remove();
        }
    }
}
