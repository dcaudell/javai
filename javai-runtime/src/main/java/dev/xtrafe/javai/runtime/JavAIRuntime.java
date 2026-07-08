package dev.xtrafe.javai.runtime;

import java.lang.reflect.Field;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The back-edge propagation engine and lazy-recompute machinery doc/spec/vector-core.md's worked example
 * calls by name (<code>JavAIRuntime.propagateDirty(this)</code>). Every method here is synthesized-code
 * plumbing: {@code javai-agent}'s weaver wires a woven class's {@code vector()}/{@code summaryVector()}/
 * {@code markDirty()}-family methods, and every mutating setter, to call straight through to these static
 * methods. Application code never calls anything in this class directly.
 *
 * <p>Reflection appears in exactly one place -- {@link #stateOf}, the single field lookup that reaches a
 * woven class's one synthesized {@link DirtyTrackingSupport} field. Everything past that point is typed,
 * ordinary Java: recursing into a child's {@code summaryVector()} or checking {@code instanceof
 * JavAIDirtyTracking} needs no reflection, because real implementations of those interfaces exist now
 * (unlike the load-time weaving spike this replaces, which reflected on ad hoc fields throughout).
 */
public final class JavAIRuntime {

    /** Name of the one field the weaver adds to every woven class. Public: {@code javai-agent} needs it. */
    public static final String STATE_FIELD = "$javai$state";

    /**
     * Fixed global decay rate for {@code summaryVector()} (doc/spec/vector-core.md): "the fixed global
     * decay constant is sufficient for a first, functionally-complete Phase 0." Per-field
     * {@code @Summary(decay=...)} tuning is an explicitly-deferred proposal, not built here.
     */
    public static final float DEFAULT_SUMMARY_DECAY = 0.5f;

    private static volatile JavAIEmbeddingProvider embeddingProvider;

    private JavAIRuntime() {
    }

    // ---- provider configuration -------------------------------------------------------------

    public static void configureEmbeddingProvider(JavAIEmbeddingProvider provider) {
        embeddingProvider = provider;
    }

    static JavAIEmbeddingProvider embeddingProvider() {
        JavAIEmbeddingProvider provider = embeddingProvider;
        if (provider == null) {
            synchronized (JavAIRuntime.class) {
                provider = embeddingProvider;
                if (provider == null) {
                    provider = defaultProviderFromSystemProperties();
                    embeddingProvider = provider;
                }
            }
        }
        return provider;
    }

    private static JavAIEmbeddingProvider defaultProviderFromSystemProperties() {
        String endpoint = System.getProperty("javai.embedding.endpoint");
        if (endpoint == null) {
            throw new IllegalStateException("No JavAIEmbeddingProvider configured. Call "
                    + "JavAIRuntime.configureEmbeddingProvider(...) or set the javai.embedding.endpoint "
                    + "system property (and optionally javai.embedding.model).");
        }
        String modelId = System.getProperty("javai.embedding.model", "qwen3-embedding-0.6b");
        return new TextEmbeddingsInferenceProvider(URI.create(endpoint), modelId);
    }

    // ---- dirty-flag / dependents passthroughs, wired onto every woven class -----------------

    public static void markFieldDirty(Object self) {
        stateOf(self).markFieldDirty();
    }

    public static boolean isFieldDirty(Object self) {
        return stateOf(self).isFieldDirty();
    }

    public static void clearFieldDirty(Object self) {
        stateOf(self).clearFieldDirty();
    }

    public static void markSummaryDirty(Object self) {
        stateOf(self).markSummaryDirty();
    }

    public static boolean isSummaryDirty(Object self) {
        return stateOf(self).isSummaryDirty();
    }

    public static void clearSummaryDirty(Object self) {
        stateOf(self).clearSummaryDirty();
    }

    public static void addDependent(Object self, Object dependent) {
        stateOf(self).addDependent(dependent);
    }

    public static Iterable<Object> dependents(Object self) {
        return stateOf(self).dependents();
    }

    // ---- graph wiring, called from every woven setter ----------------------------------------

    /** No-op unless {@code newValue} is itself dependency-tracked -- harmless for a plain data field. */
    public static void registerDependency(Object owner, Object newValue) {
        if (newValue instanceof JavAIDirtyTracking tracked) {
            tracked.addDependent(owner);
        }
    }

    /**
     * Wired onto every woven constructor: registers {@code self} as a dependent of whichever of its own
     * declared fields are currently graph-shaped. Covers the common case a setter-based edge alone
     * misses -- a {@code @Summary} collection field initialized inline and never reassigned (elements are
     * added through the collection itself, e.g. {@code getItems().add(...)}, not through a setter this
     * weaver could instrument). Harmless no-op per field otherwise, so this scans every declared field
     * rather than needing the weaver to pass down which ones are annotated.
     */
    public static void registerAllFieldDependencies(Object self) {
        for (Field field : self.getClass().getDeclaredFields()) {
            if (field.getName().equals(STATE_FIELD)) {
                continue;
            }
            field.setAccessible(true);
            try {
                Object value = field.get(self);
                if (value != null) {
                    registerDependency(self, value);
                }
            } catch (IllegalAccessException e) {
                throw new IllegalStateException("Cannot read " + field + " while wiring dependencies", e);
            }
        }
    }

    /**
     * Walks {@code obj}'s dependents, marking each {@code SummaryDirty} and recursing, stopping the
     * instant it reaches a node already marked -- the cycle-safety guarantee from doc/spec/vector-core.md.
     */
    public static void propagateDirty(Object obj) {
        if (!(obj instanceof JavAIDirtyTracking tracked)) {
            return;
        }
        List<JavAIDirtyTracking> pending = new ArrayList<>();
        for (Object dependent : tracked.dependents()) {
            if (dependent instanceof JavAIDirtyTracking dependentTracked) {
                pending.add(dependentTracked);
            }
        }
        while (!pending.isEmpty()) {
            JavAIDirtyTracking current = pending.remove(pending.size() - 1);
            if (current.isSummaryDirty()) {
                continue;
            }
            current.markSummaryDirty();
            for (Object dependent : current.dependents()) {
                if (dependent instanceof JavAIDirtyTracking dependentTracked) {
                    pending.add(dependentTracked);
                }
            }
        }
    }

    // ---- vector computation, wired onto every woven class's vector()/summaryVector()/etc. ----

    /** {@code vectorizeFieldNames} is a comma-joined list of field names, baked in at weave time. */
    public static EmbeddingVector vector(Object self, String vectorizeFieldNames) {
        DirtyTrackingSupport state = stateOf(self);
        if (state.cachedVector() == null || state.isFieldDirty()) {
            String canonicalText = canonicalFieldText(self, vectorizeFieldNames);
            EmbeddingVector recomputed = embeddingProvider().embed(canonicalText);
            state.cacheVector(recomputed);
            state.clearFieldDirty();
        }
        return state.cachedVector();
    }

    /**
     * Always recomputes -- no per-field cache. Doc/spec/vector-core.md's lazy-recompute lifecycle is
     * specified for {@code vector()}/{@code summaryVector()} only; a single shared {@code FieldDirty} flag
     * can't safely gate several independently-cached per-field vectors (whichever accessor is read first
     * after a mutation would clear the flag and leave every other field's cache stale). Simpler and
     * correct beats a caching scheme that needs per-field dirty bits this spec doesn't have.
     */
    public static EmbeddingVector fieldVector(Object self, String fieldName) {
        Object value = readField(self, fieldName);
        return embeddingProvider().embed(value == null ? "" : String.valueOf(value));
    }

    public static EmbeddingVector summaryVector(Object self, String summaryFieldNames, String vectorizeFieldNames) {
        DirtyTrackingSupport state = stateOf(self);
        // Recompute on FieldDirty too, not just SummaryDirty: summaryVector() always includes vector(self)
        // as its base term, so a mutation to this object's own fields invalidates its own summaryVector()
        // even though propagateDirty() only marks *ancestors* SummaryDirty, never the mutated object itself.
        if (state.cachedSummaryVector() == null || state.isSummaryDirty() || state.isFieldDirty()) {
            if (!enterSummaryComputation(self)) {
                // self is already being computed further up this same call stack -- a cycle. Per
                // doc/spec/vector-core.md: "treat the repeated node as a leaf for that path rather than
                // recursing forever." Contribute only this object's own vector(), not another recursive
                // descent into its children; self's own dirty flags are left untouched so a later,
                // non-reentrant call still computes it properly.
                return vector(self, vectorizeFieldNames);
            }
            try {
                EmbeddingVector own = vector(self, vectorizeFieldNames);
                float[] sum = own.values().clone();
                if (!summaryFieldNames.isBlank()) {
                    for (String fieldName : summaryFieldNames.split(",")) {
                        Object value = readField(self, fieldName);
                        if (value instanceof JavAIVectorizable child) {
                            EmbeddingVector childSummary = child.summaryVector();
                            if (childSummary.dims() != sum.length) {
                                throw new IllegalStateException(
                                        "summaryVector() dimension mismatch: " + self.getClass() + "'s own vector has "
                                                + sum.length + " dims but @Summary field " + fieldName + " contributed "
                                                + childSummary.dims() + " -- are they using the same model?");
                            }
                            VectorMath.addWeighted(sum, childSummary.values(), DEFAULT_SUMMARY_DECAY);
                        }
                    }
                }
                EmbeddingVector recomputed =
                        new EmbeddingVector(VectorMath.normalize(sum), own.modelId(), sum.length, Instant.now());
                state.cacheSummaryVector(recomputed);
                state.clearSummaryDirty();
            } finally {
                exitSummaryComputation(self);
            }
        }
        return state.cachedSummaryVector();
    }

    /**
     * Tracks, per thread, which objects' {@code summaryVector()} is currently being computed further up
     * the call stack -- the cycle-safety guard for the *recursive value computation* itself, distinct
     * from {@link #propagateDirty}'s cycle-safety (which guards the dirty-marking walk, not the
     * arithmetic). Shared between this class's own {@code summaryVector} and
     * {@link CollectionVectorSupport#summaryVector}, since a cycle can pass through a collection and back
     * to an object, not just object-to-object.
     */
    private static final ThreadLocal<Set<Object>> SUMMARY_COMPUTATION_PATH =
            ThreadLocal.withInitial(() -> Collections.newSetFromMap(new IdentityHashMap<>()));

    /** Returns {@code false} if {@code self} is already on the current thread's in-progress path. */
    static boolean enterSummaryComputation(Object self) {
        return SUMMARY_COMPUTATION_PATH.get().add(self);
    }

    static void exitSummaryComputation(Object self) {
        SUMMARY_COMPUTATION_PATH.get().remove(self);
    }

    public static double similarityToVectorizable(Object self, String vectorizeFieldNames, JavAIVectorizable other) {
        return VectorMath.cosineSimilarity(vector(self, vectorizeFieldNames), other.vector());
    }

    public static double similarityToReference(Object self, String vectorizeFieldNames, EmbeddingVector reference) {
        return VectorMath.cosineSimilarity(vector(self, vectorizeFieldNames), reference);
    }

    // ---- query(): reflection-based, cycle-safe, depth-limited graph walk ---------------------

    public static <T> JavAIList<T> query(Object self, EmbeddingVector reference, Class<T> type, int maxDepth) {
        List<T> matches = new ArrayList<>();
        Set<Object> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        visited.add(self);
        if (self instanceof Map<?, ?> || self instanceof Iterable<?>) {
            // self is itself a collection -- e.g. queried directly rather than reached via a field.
            // Treat its own elements as the depth-1 candidates instead of reflecting over ArrayList's
            // own private fields, which wouldn't turn up anything meaningful.
            for (Object unit : expand(self)) {
                visitCandidate(unit, 1, maxDepth, type, visited, matches);
            }
        } else {
            walkGraph(self, 0, maxDepth, type, visited, matches);
        }
        matches.sort(Comparator.comparingDouble((T match) -> similarityOf(match, reference)).reversed());
        JavAIArrayList<T> result = new JavAIArrayList<>();
        result.addAll(matches);
        return result;
    }

    private static double similarityOf(Object candidate, EmbeddingVector reference) {
        if (candidate instanceof JavAIVectorizable vectorizable) {
            return VectorMath.cosineSimilarity(vectorizable.vector(), reference);
        }
        return Double.NEGATIVE_INFINITY;
    }

    private static <T> void walkGraph(Object node, int depth, int maxDepth, Class<T> type,
            Set<Object> visited, List<T> matches) {
        if (depth >= maxDepth) {
            return;
        }
        for (Field field : node.getClass().getDeclaredFields()) {
            field.setAccessible(true);
            Object value;
            try {
                value = field.get(node);
            } catch (IllegalAccessException e) {
                throw new IllegalStateException("Cannot read " + field + " while querying the object graph", e);
            }
            if (value == null) {
                continue;
            }
            for (Object unit : expand(value)) {
                visitCandidate(unit, depth + 1, maxDepth, type, visited, matches);
            }
        }
    }

    private static <T> void visitCandidate(Object unit, int depth, int maxDepth, Class<T> type,
            Set<Object> visited, List<T> matches) {
        if (unit == null || !visited.add(unit)) {
            return;
        }
        if (type.isInstance(unit)) {
            matches.add(type.cast(unit));
        }
        // Only descend into graph-shaped values (another vectorizable object, or a container of them).
        // A plain leaf value -- a String, a boxed number, an enum -- is eligible to *match* type above,
        // but reflecting over ITS declared fields is both pointless and unsafe: java.lang.String's own
        // fields are JDK-internal and setAccessible(true) on them throws InaccessibleObjectException
        // under the module system (module java.base does not open itself to arbitrary reflection).
        if (unit instanceof JavAIVectorizable || unit instanceof Map<?, ?> || unit instanceof Iterable<?>) {
            walkGraph(unit, depth, maxDepth, type, visited, matches);
        }
    }

    private static Iterable<?> expand(Object value) {
        if (value instanceof Map<?, ?> map) {
            return map.values();
        }
        if (value instanceof Iterable<?> iterable) {
            return iterable;
        }
        return List.of(value);
    }

    // ---- shared reflection helpers -------------------------------------------------------------

    private static String canonicalFieldText(Object self, String vectorizeFieldNames) {
        if (vectorizeFieldNames.isBlank()) {
            return "";
        }
        StringBuilder text = new StringBuilder();
        for (String fieldName : vectorizeFieldNames.split(",")) {
            Object value = readField(self, fieldName);
            if (value != null) {
                text.append(fieldName).append(": ").append(value).append('\n');
            }
        }
        return text.toString();
    }

    private static Object readField(Object self, String fieldName) {
        try {
            Field field = self.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            return field.get(self);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Expected field " + fieldName + " on " + self.getClass(), e);
        }
    }

    private static DirtyTrackingSupport stateOf(Object self) {
        try {
            Field field = self.getClass().getDeclaredField(STATE_FIELD);
            field.setAccessible(true);
            DirtyTrackingSupport state = (DirtyTrackingSupport) field.get(self);
            if (state == null) {
                state = new DirtyTrackingSupport();
                field.set(self, state);
            }
            return state;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(
                    "Woven class is missing the expected " + STATE_FIELD + " field on " + self.getClass(), e);
        }
    }
}
