package dev.xtrafe.javai.vector;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The core concurrency primitive backing every vector cache in the system (an object's own {@code vector()},
 * its {@code concatenatedTextVector()}, its {@code summaryVector()}, and every boxed {@code @Vectorize} field) -- lock-free,
 * deliberately allowing many parallel {@code embed()} calls rather than coalescing/single-flighting them, per
 * the design this class exists to support: any thread that observes a stale slot is free to compute its own
 * answer against the field state it actually saw and return that directly to its own caller, while this class's
 * only job is making sure the *shared cache* other readers see never regresses to something older than what's
 * already been recorded.
 *
 * <p>A slot has a monotonically increasing {@code generation} (bumped once per mutation -- see
 * {@link #bumpGeneration()}), and separately tracks the highest generation any completed computation has
 * <em>attempted</em> (whether it succeeded or failed) versus the highest generation that actually
 * <em>succeeded</em>. The distinction matters for a real race: if a computation for generation 3 fails before
 * a slower, stale computation for generation 2 finishes, the late generation-2 success must not be allowed to
 * land and make the slot look falsely clean/current -- {@code attemptedGeneration} is what rejects it, even
 * though generation 2 genuinely never previously committed anything.
 *
 * <p>{@code isDirty()} reflects only the {@code committedGeneration} versus the live {@code generation} --
 * a failed attempt, however it's recorded, never advances {@code committedGeneration}, so a slot is only ever
 * "clean" when a real, successful computation is on file for the current generation. This is deliberate: per
 * this project's own definition, an object is clean exactly when its vector is accurate, never as a side
 * effect of merely having tried.
 */
public final class VectorCacheSlot {

    private final AtomicLong generation = new AtomicLong(1);
    private final AtomicReference<State> state = new AtomicReference<>(new State(0, 0, null));

    /** Called on every mutation; returns the new generation the caller should compute against. */
    public long bumpGeneration() {
        return generation.incrementAndGet();
    }

    /** The current (live) generation, reflecting however many mutations have happened so far. */
    public long currentGeneration() {
        return generation.get();
    }

    /** True unless a successful computation is on file for the current generation. */
    public boolean isDirty() {
        return state.get().committedGeneration() != generation.get();
    }

    /** True once at least one computation has ever succeeded for this slot. */
    public boolean everComputed() {
        return state.get().committedGeneration() > 0;
    }

    /** The value currently being served to readers -- may be a real, possibly-stale vector, or {@code null}
     *  if nothing has ever succeeded, or if a failure was recorded in null-out mode. */
    public EmbeddingVector cachedValue() {
        return state.get().value();
    }

    public long committedGeneration() {
        return state.get().committedGeneration();
    }

    /**
     * Records a successful computation for {@code computedGeneration}, discarding it (returning {@code false})
     * if a strictly newer generation -- success or failure -- has already been recorded. Returns {@code true}
     * if this result actually landed.
     *
     * <p>Deliberately {@code >}, not {@code >=}: a same-generation retry must be allowed to land. A failed
     * attempt for generation {@code g} advances {@code attemptedGeneration} to {@code g} (see
     * {@link #commitFailure}) without ever advancing {@code committedGeneration} -- the slot is still dirty
     * for {@code g}, and {@code JavAIRuntime}'s opportunistic retry (a later read of that still-dirty,
     * already-computed-once slot) re-dispatches another attempt for that exact same generation, since no new
     * mutation has bumped it further. That retry's eventual success must be able to commit; only a
     * strictly newer generation already having been attempted should reject it.
     */
    public boolean commitSuccess(long computedGeneration, EmbeddingVector value) {
        while (true) {
            State current = state.get();
            if (current.attemptedGeneration() > computedGeneration) {
                return false;
            }
            State next = new State(computedGeneration, computedGeneration, value);
            if (state.compareAndSet(current, next)) {
                return true;
            }
        }
    }

    /**
     * Records that computing {@code computedGeneration} failed. Never advances {@code committedGeneration} --
     * a failure is never "accurate" -- but does advance {@code attemptedGeneration} regardless of
     * {@code nullOutValue}, so a slower, stale success for an older generation can't land afterward and look
     * newer than this failure. If {@code nullOutValue} is set, the served value is cleared to {@code null}
     * (still leaving the slot dirty); otherwise the last known-good value, if any, is left in place.
     */
    public void commitFailure(long computedGeneration, boolean nullOutValue) {
        while (true) {
            State current = state.get();
            if (current.attemptedGeneration() >= computedGeneration) {
                return;
            }
            EmbeddingVector nextValue = nullOutValue ? null : current.value();
            State next = new State(computedGeneration, current.committedGeneration(), nextValue);
            if (state.compareAndSet(current, next)) {
                return;
            }
        }
    }

    private record State(long attemptedGeneration, long committedGeneration, EmbeddingVector value) {
    }

    private final AtomicReference<PendingComputation> pending = new AtomicReference<>();

    /**
     * Claims the right to compute {@code generation} in a single-flight sense: if nothing is currently
     * outstanding for exactly this generation, installs a fresh {@link PendingComputation} and returns it
     * with {@link PendingComputation#owner()} {@code true} -- the caller must actually perform the
     * computation, complete the returned future (success or failure) with its outcome, and then call
     * {@link #clearPendingComputation} with the same generation. If an attempt for this exact generation is
     * already outstanding, returns that same instance with {@code owner()} {@code false} -- the caller
     * should simply await its future rather than computing anything itself.
     *
     * <p>Backs two distinct behaviors that share the same need to avoid duplicate concurrent work for the
     * same generation: {@code EmbeddingConsistencyMode.EVENTUAL_CONSISTENCY}'s "don't dispatch a redundant
     * background recompute if one is already in flight," and {@code COALESCED_CONSISTENCY}'s "block on
     * whatever's already outstanding rather than starting a duplicate blocking computation."
     */
    public PendingComputation claimPendingComputation(long generation) {
        while (true) {
            PendingComputation current = pending.get();
            if (current != null && current.generation() == generation) {
                return current.asJoiner();
            }
            PendingComputation next = new PendingComputation(generation, new CompletableFuture<>(), true);
            if (pending.compareAndSet(current, next)) {
                return next;
            }
        }
    }

    /** Called by whichever caller {@link #claimPendingComputation} made the owner, once its computation has
     *  completed (successfully or not) -- lets a later claim for a newer generation install a fresh entry
     *  rather than finding this now-finished one still occupying the slot. A no-op if a newer claim has
     *  already replaced this one (nothing to clear). */
    public void clearPendingComputation(long generation) {
        PendingComputation current = pending.get();
        if (current != null && current.generation() == generation) {
            pending.compareAndSet(current, null);
        }
    }

    /** @param owner {@code true} if the caller holding this instance is responsible for completing
     *  {@code future} and later clearing it; {@code false} if the caller should just await it. */
    public record PendingComputation(long generation, CompletableFuture<EmbeddingVector> future, boolean owner) {
        private PendingComputation asJoiner() {
            return new PendingComputation(generation, future, false);
        }
    }
}
