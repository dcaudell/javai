package dev.xtrafe.javai.vector;

/**
 * The synthetic bookkeeping doc/spec/vector-core.md describes as "never meant to be called directly" --
 * {@code isDirty()}/{@code markDirty()}/{@code dependents()} -- formalized as a real, internal-but-shared
 * contract so {@link JavAIRuntime}'s graph algorithms (back-edge propagation, dependency registration) can
 * call it polymorphically instead of branching per implementation.
 *
 * <p>Two families of implementer: the concrete collection types in this package ({@link JavAIArrayList}
 * etc.) implement this directly, since they're hand-written library code with real fields. Woven
 * {@code @JavAIVectorizable} user classes implement it too, synthesized by {@code javai-substrate}'s weaver,
 * backed by reflection over fields the weaver adds at load time -- from {@link JavAIRuntime}'s point of
 * view both look identical through this interface.
 *
 * <p>Deliberately separate from the fully public {@link JavAIVectorizable}: application code should never
 * see or call these methods, so they don't belong on that interface.
 */
public interface JavAIDirtyTracking {

    /** Registers {@code dependent} as something to mark {@code SummaryDirty} when this object changes. */
    void addDependent(Object dependent);

    /** Live dependents, already pruned of any that have been garbage-collected. */
    Iterable<Object> dependents();

    boolean isFieldDirty();

    void markFieldDirty();

    void clearFieldDirty();

    boolean isSummaryDirty();

    void markSummaryDirty();

    void clearSummaryDirty();
}
