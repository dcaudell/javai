package dev.xtrafe.javai.model;

import dev.xtrafe.javai.annotations.SearchVisibility;
import dev.xtrafe.javai.vector.DirtyTrackingSupport;
import dev.xtrafe.javai.vector.EmbeddingVector;
import dev.xtrafe.javai.vector.JavAIDirtyTracking;
import dev.xtrafe.javai.vector.JavAIEmbeddingProvider;
import dev.xtrafe.javai.vector.EmbeddingProviderTextEmbeddingsInference;
import dev.xtrafe.javai.vector.VectorCacheSlot;
import dev.xtrafe.javai.vector.VectorCacheSlot.PendingComputation;
import dev.xtrafe.javai.vector.VectorMath;

import dev.xtrafe.javai.annotations.Vectorize;

import java.lang.reflect.Field;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;

/**
 * The back-edge propagation engine and lazy-recompute machinery doc/spec/vector-core.md's worked example
 * calls by name (<code>JavAIRuntime.propagateDirty(this)</code>). Every method here is synthesized-code
 * plumbing: {@code javai-substrate}'s weaver wires a woven class's {@code vector()}/{@code summaryVector()}/
 * {@code markDirty()}-family methods, and every mutating setter, to call straight through to these static
 * methods. Application code never calls anything in this class directly.
 *
 * <p>Reflection lives in two places, both read-side and both hierarchy-aware (a class's own declared
 * fields plus every superclass's, stopping at {@code Object}): {@link #stateOf}/{@link #findField}, which
 * reach a woven class's one synthesized {@link DirtyTrackingSupport} field or a named
 * {@code @Vectorize}/{@code @Summary} field's current value, and {@link #walkGraph}/
 * {@link #registerAllFieldDependencies}, which reflect over *every* declared field generically to find
 * graph-shaped values, independent of any annotation. Nothing else needs reflection: recursing into a
 * child's {@code summaryVector()} or checking {@code instanceof JavAIDirtyTracking} is typed, ordinary
 * Java, because real implementations of those interfaces exist now (unlike the load-time weaving spike
 * this replaces, which reflected on ad hoc fields throughout). Note the asymmetry with {@code javai-substrate}'s
 * weaver: setter-triggered dirty-marking only reaches a superclass if that superclass is itself woven (see
 * {@code JavAIWeaver}'s javadoc) -- Advice can't instrument bytecode outside the class being transformed,
 * a constraint plain reflection here doesn't share.
 */
public final class JavAIRuntime {

    /** Name of the one field the weaver adds to every woven class. Public: {@code javai-substrate} needs it. */
    public static final String STATE_FIELD = "$javai$state";

    /**
     * Fixed global decay rate for {@code summaryVector()} (doc/spec/vector-core.md): "the fixed global
     * decay constant is sufficient for a first, functionally-complete Phase 0." Per-field
     * {@code @Summary(decay=...)} tuning is an explicitly-deferred proposal, not built here.
     */
    public static final float DEFAULT_SUMMARY_DECAY = 0.5f;

    /** A reasonable default bound on simultaneous in-flight {@code embed()} calls -- see
     *  {@link #configureMaxConcurrentEmbeddingCalls(int)}. */
    public static final int DEFAULT_MAX_CONCURRENT_EMBEDDING_CALLS = 8;

    private static volatile JavAIEmbeddingProvider embeddingProvider;
    private static volatile EmbeddingConsistencyMode consistencyMode = EmbeddingConsistencyMode.IMMEDIATE_CONSISTENCY;
    private static volatile EmbeddingFailureMode failureMode = EmbeddingFailureMode.THROW;
    private static volatile Semaphore embeddingCallGate = new Semaphore(DEFAULT_MAX_CONCURRENT_EMBEDDING_CALLS);

    /** Set for the duration of {@link #runWithSubgraphLockedForPersistence} on whichever thread is running
     *  it -- forces every {@code fieldVector}/{@code concatenatedTextVector} read on that thread to block for
     *  an accurate value regardless of the globally configured {@link EmbeddingConsistencyMode}, since a
     *  persistence flush must never write a stale vector to the database. */
    private static final ThreadLocal<Boolean> FORCE_ACCURATE = ThreadLocal.withInitial(() -> false);

    private JavAIRuntime() {
    }

    // ---- provider / concurrency-mode configuration ----------------------------------------------

    public static void configureEmbeddingProvider(JavAIEmbeddingProvider provider) {
        embeddingProvider = provider;
    }

    /** Configure once, ideally at startup alongside {@link #configureEmbeddingProvider} -- see
     *  {@link EmbeddingConsistencyMode}'s own javadoc for what each mode guarantees. Undefined behavior if
     *  changed after the provider is already handling real traffic. */
    public static void configureConsistencyMode(EmbeddingConsistencyMode mode) {
        consistencyMode = mode;
    }

    public static EmbeddingConsistencyMode consistencyMode() {
        return consistencyMode;
    }

    /** See {@link EmbeddingFailureMode}'s own javadoc for the exact THROW/RETURN_NULL semantics and their
     *  paired background-failure behavior. */
    public static void configureFailureMode(EmbeddingFailureMode mode) {
        failureMode = mode;
    }

    public static EmbeddingFailureMode failureMode() {
        return failureMode;
    }

    /** Bounds how many {@code embed()} calls may be in flight simultaneously, across every woven object and
     *  every consistency mode -- both {@link EmbeddingConsistencyMode#IMMEDIATE_CONSISTENCY}'s blocking
     *  calls and {@link EmbeddingConsistencyMode#EVENTUAL_CONSISTENCY}'s eager background dispatches acquire
     *  a permit from the same gate before ever calling into the configured provider. Under a burst of rapid
     *  mutation this is what makes the system degrade to "slower" rather than "unboundedly many concurrent
     *  HTTP calls" -- callers simply block on the gate until a permit frees up.
     *
     *  <p>A batched {@link #precomputeVectors(Collection, int)} call takes <b>one</b> permit for the whole
     *  batch, not one per text: this bounds calls into the provider, and a batch is one call however many
     *  texts it carries (OMI-213). */
    public static void configureMaxConcurrentEmbeddingCalls(int max) {
        embeddingCallGate = new Semaphore(max);
    }

    static Semaphore embeddingCallGate() {
        return embeddingCallGate;
    }

    /**
     * The configured provider's model, or {@code null} if there is no provider or it cannot name one.
     *
     * <p>Public where {@link #embeddingProvider()} is deliberately not: {@code javai-persistence} needs to
     * know which model's stored vectors are still valid before deciding whether to reuse them rather than
     * re-embed (see {@link #hydrateFieldVector}), and that is a strictly narrower thing to expose than the
     * provider itself.
     */
    public static String currentModelId() {
        JavAIEmbeddingProvider provider = embeddingProvider();
        return provider == null ? null : provider.modelId();
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

    // ---- shared mode-aware compute helpers -- used by both a woven object's own vector()/concatenatedTextVector()
    // cache slots and every VectorizableString-boxed field's own slot ------------------------------------

    /** Backs {@link EmbeddingConsistencyMode#EVENTUAL_CONSISTENCY}'s background dispatches -- one shared,
     *  unbounded-thread-count executor (virtual threads: cheap to spawn one per dispatch, and this work is
     *  entirely I/O-bound waiting on the embedding provider's own HTTP call), gated by
     *  {@link #embeddingCallGate()} for the actual concurrency bound, not by this executor itself. */
    private static final ExecutorService BACKGROUND_EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();

    /**
     * Computes {@code text} for {@code targetGeneration} inline, blocking the calling thread -- used for
     * every {@link EmbeddingConsistencyMode#IMMEDIATE_CONSISTENCY} read of a dirty slot, and for the very
     * first computation ever performed for any slot regardless of mode (there is no prior real value to
     * fall back on, so even {@code EVENTUAL_CONSISTENCY} must block here). Returns the vector this
     * particular caller should see -- the result of its own snapshot, not necessarily whatever a racing
     * newer computation ends up winning the shared cache -- per "the thread that asked for a stale
     * embedding receives a vector accurate to the state of the field at that time." Applies
     * {@link #failureMode()} on failure: {@link EmbeddingFailureMode#THROW} rethrows the provider's
     * exception (wrapped, if it isn't already a {@code RuntimeException}); {@link EmbeddingFailureMode#RETURN_NULL}
     * swallows it and returns {@code null}. Either way, {@code slot} is left dirty -- see
     * {@link VectorCacheSlot#commitFailure}.
     */
    /**
     * The one place a field's text becomes a provider call -- and the one place "there is no text" is
     * decided.
     *
     * <p>A {@code @Vectorize} field holding null, "", or only whitespace has no content to embed, so it
     * yields {@link EmbeddingVector#absent()} rather than a vector of nothing. This was the last of
     * OMI-187's {@code embed("")} sources: {@code fieldTextOf} renders a null field as {@code ""}, real
     * providers substitute a single space for an empty input, and the result was a live model call whose
     * answer was the embedding of a space -- then stored, and mixed into every ancestor's summary.
     *
     * <p>It also makes "this field lost its content" representable at rest, which is what lets the
     * persistence backends delete a vector row instead of leaving a stale one behind.
     */
    private static EmbeddingVector embedText(String text) {
        if (text == null || text.isBlank()) {
            return EmbeddingVector.absent();
        }
        return embeddingProvider().embed(text);
    }

    static EmbeddingVector computeBlocking(VectorCacheSlot slot, long targetGeneration, String text) {
        acquireUninterruptibly(embeddingCallGate());
        try {
            EmbeddingVector result;
            try {
                result = embedText(text);
            } catch (RuntimeException e) {
                slot.commitFailure(targetGeneration, failureMode() == EmbeddingFailureMode.RETURN_NULL);
                if (failureMode() == EmbeddingFailureMode.THROW) {
                    throw e;
                }
                return null;
            }
            slot.commitSuccess(targetGeneration, result);
            return result;
        } finally {
            embeddingCallGate().release();
        }
    }

    /**
     * Fires a background computation of {@code text} for {@code targetGeneration}, but only if nothing is
     * already outstanding for that exact generation ({@link VectorCacheSlot#claimPendingComputation}) --
     * {@link EmbeddingConsistencyMode#EVENTUAL_CONSISTENCY}'s eager-on-mutation dispatch and its
     * opportunistic re-dispatch from a stale (but already-computed-at-least-once) read both funnel through
     * here, so a burst of concurrent readers observing the same stale generation triggers at most one real
     * {@code embed()} call between them, not one each. Also completes the claimed {@link PendingComputation}'s
     * future on success or failure -- what lets {@link EmbeddingConsistencyMode#COALESCED_CONSISTENCY}'s
     * blocking reads join this same dispatch instead of starting their own. Nobody who *dispatched* this is
     * waiting on it directly, so a failure is recorded per {@link #failureMode()}'s paired background
     * behavior (see that enum's javadoc) and never thrown from here -- only a joiner's own {@link #awaitPending}
     * call surfaces it.
     */
    static void dispatchBackground(VectorCacheSlot slot, long targetGeneration, String text) {
        PendingComputation claim = slot.claimPendingComputation(targetGeneration);
        if (!claim.owner()) {
            return;
        }
        BACKGROUND_EXECUTOR.execute(() -> {
            acquireUninterruptibly(embeddingCallGate());
            try {
                EmbeddingVector result;
                try {
                    result = embedText(text);
                } catch (RuntimeException e) {
                    boolean nullOut = failureMode() == EmbeddingFailureMode.RETURN_NULL;
                    slot.commitFailure(targetGeneration, nullOut);
                    // Mirrors computeBlocking's own failureMode() branch: RETURN_NULL resolves the future
                    // normally (with null), THROW resolves it exceptionally -- so a COALESCED_CONSISTENCY
                    // joiner's awaitPending sees exactly the outcome this mode promises, not always an
                    // exception regardless of mode.
                    if (nullOut) {
                        claim.future().complete(null);
                    } else {
                        claim.future().completeExceptionally(e);
                    }
                    return;
                }
                slot.commitSuccess(targetGeneration, result);
                claim.future().complete(result);
            } finally {
                embeddingCallGate().release();
                slot.clearPendingComputation(targetGeneration);
            }
        });
    }

    /**
     * {@link EmbeddingConsistencyMode#COALESCED_CONSISTENCY}'s read path: claims the right to compute
     * {@code targetGeneration}, and either becomes the one real, blocking computation (exactly like
     * {@link #computeBlocking}, plus completing the claim's future so any joiners unblock) or -- if a
     * computation for this generation is already outstanding, whether dispatched by this same read path or
     * by {@link #dispatchBackground}'s eager-on-mutation trigger -- simply awaits it instead of duplicating
     * the work.
     */
    static EmbeddingVector coalescedRead(VectorCacheSlot slot, long targetGeneration, String text) {
        PendingComputation claim = slot.claimPendingComputation(targetGeneration);
        if (!claim.owner()) {
            return awaitPending(claim.future());
        }
        try {
            EmbeddingVector result = computeBlocking(slot, targetGeneration, text);
            claim.future().complete(result);
            return result;
        } catch (RuntimeException e) {
            claim.future().completeExceptionally(e);
            throw e;
        } finally {
            slot.clearPendingComputation(targetGeneration);
        }
    }

    /** Blocks for {@code future} to resolve, unwrapping and rethrowing the original {@code RuntimeException}
     *  if the computation that owns it failed under {@link EmbeddingFailureMode#THROW} -- every joiner sees
     *  the exact same outcome the owning computation itself produced (a value, possibly {@code null} under
     *  {@link EmbeddingFailureMode#RETURN_NULL}, or a thrown exception), never a re-derived one of its own. */
    private static EmbeddingVector awaitPending(CompletableFuture<EmbeddingVector> future) {
        try {
            return future.join();
        } catch (CompletionException e) {
            if (e.getCause() instanceof RuntimeException cause) {
                throw cause;
            }
            throw e;
        }
    }

    private static void acquireUninterruptibly(Semaphore gate) {
        boolean interrupted = false;
        while (true) {
            try {
                gate.acquire();
                break;
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static JavAIEmbeddingProvider defaultProviderFromSystemProperties() {
        String endpoint = System.getProperty("javai.embedding.endpoint");
        if (endpoint == null) {
            throw new IllegalStateException("No JavAIEmbeddingProvider configured. Call "
                    + "JavAIRuntime.configureEmbeddingProvider(...) or set the javai.embedding.endpoint "
                    + "system property (and optionally javai.embedding.model).");
        }
        String modelId = System.getProperty("javai.embedding.model", "qwen3-embedding-0.6b");
        return new EmbeddingProviderTextEmbeddingsInference(URI.create(endpoint), modelId);
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
        for (Field field : allFields(self.getClass())) {
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

    // ---- vector computation, wired onto every woven class's vector()/concatenatedTextVector()/summaryVector()/etc. ----

    /**
     * The compositional aggregate: combines each {@code @Vectorize} field's own {@link #fieldVector} into
     * one centroid (the same {@link VectorMath#centroid} operation a collection already uses to combine its
     * elements' vectors -- not a single embedding of concatenated text; see {@link #concatenatedTextVector} for that).
     * Deliberately uncached at this level: each constituent {@link #fieldVector} already caches (and
     * respects {@link EmbeddingConsistencyMode} for) its own recomputation, so recombining them here is
     * cheap, in-memory arithmetic every time, never itself a source of staleness or blocking beyond
     * whatever an individual field's own first-ever computation requires.
     *
     * <p>{@code vectorizeFieldNames} is a comma-joined list of field names, baked in at weave time.
     */
    public static EmbeddingVector vector(Object self, String vectorizeFieldNames) {
        if (vectorizeFieldNames.isBlank()) {
            // No @Vectorize fields at all -- no content, so no vector. Absent rather than a fabricated one,
            // and free rather than a live embedding call: this used to embed("") on every single read, with
            // no cache slot, on the reasoning that a real dimensioned vector was needed for arithmetic
            // elsewhere. Arithmetic now skips absent vectors instead (OMI-187).
            return EmbeddingVector.absent();
        }
        List<EmbeddingVector> fieldVectors = new ArrayList<>();
        for (String fieldName : vectorizeFieldNames.split(",")) {
            fieldVectors.add(fieldVector(self, fieldName));
        }
        return VectorMath.centroid(fieldVectors);
    }

    /**
     * A single embedding of every {@code @Vectorize} field's current value concatenated into one
     * text block -- exactly what {@code vector()} computed before this class introduced per-field caching;
     * preserved verbatim under its own name (see {@link dev.xtrafe.javai.model.JavAIVectorizable#concatenatedTextVector}'s
     * own javadoc for why). Cached via its own {@link VectorCacheSlot} (invalidated by any {@code @Vectorize}
     * field changing -- there's no way to incrementally update one holistic text embedding when only one of
     * several concatenated fields changed), and mode-aware exactly like {@link #fieldVector}.
     */
    public static EmbeddingVector concatenatedTextVector(Object self, String vectorizeFieldNames) {
        DirtyTrackingSupport state = stateOf(self);
        VectorCacheSlot slot = state.concatenatedTextSlot();
        return readSlot(state, slot, () -> concatenatedFieldText(self, vectorizeFieldNames));
    }

    /**
     * A single {@code @Vectorize} field's own cached vector -- real per-field caching (unlike this method's
     * pre-concurrency-model incarnation, which always recomputed with no cache at all: a single shared
     * {@code FieldDirty} flag couldn't safely gate several independently-cached per-field vectors, but a
     * dedicated {@link VectorCacheSlot} per field name can). Mode-aware -- see {@link EmbeddingConsistencyMode}'s
     * own javadoc for what each of the three modes guarantees on this read path.
     */
    public static EmbeddingVector fieldVector(Object self, String fieldName) {
        DirtyTrackingSupport state = stateOf(self);
        VectorCacheSlot slot = state.fieldSlot(fieldName);
        return readSlot(state, slot, () -> fieldTextOf(self, fieldName));
    }

    /**
     * Seeds {@code fieldName}'s cache slot with an already-known vector, so the next read serves it instead
     * of paying a model call to recompute a value we already have.
     *
     * <p>Exists for the persistence layer (OMI-187). Every vector caching structure in this runtime hangs
     * off {@link #stateOf}, which resolves a woven <em>instance</em> field -- so vector caches are keyed to
     * object identity and cannot survive a persistence round trip, which by nature hands back a different
     * instance of the same logical entity. Meanwhile the vectors themselves were already written to the
     * database, keyed by {@code (owner_type, owner_id, field_name)}: precisely this slot's identity. A
     * loaded entity re-embedding a field whose vector is sitting in the row it was just loaded from is pure
     * waste, and this is how a backend hands it back instead.
     *
     * <p>Not a cache and not memoization -- there is no new store, no eviction policy, and no invalidation
     * question beyond the one persistence already answers: vectors are stored per model, so a caller must
     * only hydrate from the model it would embed with now (see {@link JavAIEmbeddingProvider#modelId()}).
     * Hydrating an {@link EmbeddingVector#isAbsent() absent} vector is a no-op, since absence is the state
     * a fresh slot is already in.
     */
    /**
     * Computes every not-yet-computed {@code @Vectorize} field vector across {@code objects} in as few
     * provider calls as the provider supports, then seeds each field's cache slot with the result.
     *
     * <p>This is OMI-187's "bulk-seed path" ask, and it is where {@link JavAIEmbeddingProvider#embedAll}
     * earns its keep. Ordinary lazy computation discovers one text at a time, deep inside a read, which
     * makes batching impossible however fast the provider is -- seeding 1,400 tags means 1,400 sequential
     * round trips no matter what. Gathering first turns that into a handful of requests. Nothing else about
     * the object model changes: this only pre-fills caches that a later read (or {@code save()}) would have
     * filled one at a time, so calling it is always optional and never changes a result.
     *
     * <p>Recommended shape for seeding a large reference set:
     * <pre>{@code
     * JavAIRuntime.precomputeVectors(allTags);        // one batched round trip per chunk
     * for (Tag tag : allTags) tagRepository.save(tag); // finds every vector already on file
     * }</pre>
     *
     * <p>Texts are de-duplicated across the whole batch, so a value shared by several objects is embedded
     * once and seeded into every slot holding it. Fields whose slot is already clean are skipped entirely --
     * including anything a previous call already warmed -- so this is safely re-runnable.
     *
     * @param objects the objects to warm; non-{@link JavAIVectorizable} entries are ignored
     * @param batchSize maximum texts per provider call, so one enormous request is never built
     */
    public static void precomputeVectors(Collection<?> objects, int batchSize) {
        if (batchSize < 1) {
            throw new IllegalArgumentException("batchSize must be at least 1, was " + batchSize);
        }
        // Distinct text -> every (object, field) slot waiting on it. LinkedHashMap so the batch order is
        // deterministic, which keeps a failure reproducible.
        Map<String, List<FieldRef>> pending = new LinkedHashMap<>();
        for (Object object : objects) {
            if (!(object instanceof JavAIVectorizable)) {
                continue;
            }
            for (Field field : allFields(object.getClass())) {
                if (!field.isAnnotationPresent(Vectorize.class)) {
                    continue;
                }
                String fieldName = field.getName();
                VectorCacheSlot slot = stateOf(object).fieldSlot(fieldName);
                if (!slot.isDirty()) {
                    continue; // already accurate; nothing to compute
                }
                String text = fieldTextOf(object, fieldName);
                if (text == null || text.isBlank()) {
                    continue; // no content: absent, and nothing to batch -- see embedText
                }
                pending.computeIfAbsent(text, key -> new ArrayList<>())
                        .add(new FieldRef(object, fieldName));
            }
        }
        if (pending.isEmpty()) {
            return;
        }

        List<String> texts = new ArrayList<>(pending.keySet());
        JavAIEmbeddingProvider provider = embeddingProvider();
        for (int start = 0; start < texts.size(); start += batchSize) {
            List<String> chunk = texts.subList(start, Math.min(start + batchSize, texts.size()));
            // One batched request costs one permit, not one per text (OMI-213). The gate bounds concurrent
            // *calls into the provider* -- it exists so a burst degrades to "slower" rather than to
            // unboundedly many concurrent HTTP calls -- and a batch is one call on one connection however
            // many texts it carries. Charging it per text would instead make the gate throttle batching
            // itself, penalising the very thing that reduces load on the endpoint: a 100-text batch against a
            // gate of 8 would block outright, since no batch could ever acquire enough permits.
            //
            // Taking a permit at all is the point. Before this, precomputeVectors called the provider without
            // touching the gate, so a bulk seed ran entirely outside the bound this class documents as
            // applying to every provider call in every consistency mode.
            List<EmbeddingVector> vectors;
            acquireUninterruptibly(embeddingCallGate());
            try {
                vectors = provider.embedAll(chunk);
            } finally {
                embeddingCallGate().release();
            }
            if (vectors.size() != chunk.size()) {
                throw new IllegalStateException("embedAll returned " + vectors.size() + " vectors for "
                        + chunk.size() + " texts; a provider must answer one vector per input, in order");
            }
            for (int i = 0; i < chunk.size(); i++) {
                for (FieldRef ref : pending.get(chunk.get(i))) {
                    hydrateFieldVector(ref.owner(), ref.fieldName(), vectors.get(i));
                }
            }
        }
    }

    /** {@link #precomputeVectors(Collection, int)} with a batch size most provider APIs accept comfortably. */
    public static void precomputeVectors(Collection<?> objects) {
        precomputeVectors(objects, 100);
    }

    /** One object's one {@code @Vectorize} field, awaiting a batched embedding. */
    private record FieldRef(Object owner, String fieldName) {
    }

    /**
     * Carries every already-computed, still-accurate field vector from one instance of a logical entity to
     * another -- the fix for what {@code merge()} otherwise destroys (OMI-187).
     *
     * <p>JavAI's dirty-tracking state lives in a woven {@code $javai$state} field, which is exactly the kind
     * of transient state {@code merge()} does not reconcile: Hibernate copies the mapped field <em>values</em>
     * onto its managed copy and leaves the tracking state behind on the caller's instance. The managed copy
     * therefore looks brand new and re-embeds values the caller had already computed. {@code javai-persistence}
     * already reads {@code @Transient} collection and geo-point state off the original instance for precisely
     * this reason; vectors are the same situation and get the same treatment.
     *
     * <p><b>A dirty source slot is skipped, and that is the whole safety argument.</b> If the caller mutated
     * a field, their own slot is dirty -- the setter bumped its generation -- so nothing is transferred and
     * the target computes the new value. If the caller did not mutate it, their slot holds a vector that is
     * accurate by definition, and handing it over costs a reference instead of a model call.
     *
     * <p>Deliberately not a hash comparison against the stored text. Field content is not necessarily small
     * or cheap to digest -- vectorizing large binary content is a plausible future -- so validity is decided
     * from cache state that is already maintained, never by re-reading and re-hashing the value itself.
     */
    public static void transferComputedVectors(Object from, Object to) {
        if (from == to || from == null || to == null) {
            return;
        }
        // Guarded here rather than at each call site: a persisted @Entity is not necessarily
        // @JavAIVectorizable (a @Taggable-only entity is the standard example), and such a class has no
        // woven $javai$state for stateOf to find. Callers walking a collection of entities shouldn't each
        // have to remember that.
        if (!(from instanceof JavAIVectorizable) || !(to instanceof JavAIVectorizable)) {
            return;
        }
        DirtyTrackingSupport source = stateOf(from);
        DirtyTrackingSupport target = stateOf(to);
        for (String fieldName : source.fieldSlotNames()) {
            VectorCacheSlot sourceSlot = source.fieldSlot(fieldName);
            // Dirty or never-computed: the caller has nothing trustworthy to hand over. Let `to` compute.
            if (sourceSlot.isDirty() || !sourceSlot.everComputed()) {
                continue;
            }
            hydrateFieldVector(to, fieldName, sourceSlot.cachedValue());
        }
    }

    public static void hydrateFieldVector(Object self, String fieldName, EmbeddingVector vector) {
        if (vector == null || vector.isAbsent()) {
            return;
        }
        VectorCacheSlot slot = stateOf(self).fieldSlot(fieldName);
        // Only into a pristine slot: nothing computed, and no JavAI-visible mutation since construction
        // (a fresh slot sits at generation 1; every vectorizeFieldMutated bumps it). This is what keeps
        // eager hydration from ever overwriting a real change -- load an entity, call a setter, save it,
        // and the setter's bump puts the slot past generation 1, so the stored vector is ignored and the
        // new value is embedded, exactly as it should be.
        //
        // The remaining case -- a field mutated *behind* JavAI's back, bypassing the woven setter -- will
        // be served its stored vector, which is now stale. That is deliberate and is JavAI's standing
        // assumption everywhere, not a gap introduced here: nothing but JavAI is expected to mutate a
        // @Vectorize field, and code that does so has already opted out of automatic re-embedding.
        if (slot.everComputed() || slot.currentGeneration() != 1) {
            return;
        }
        slot.commitSuccess(slot.currentGeneration(), vector);
    }

    /**
     * Shared read path for both {@link #fieldVector} and {@link #concatenatedTextVector}: a clean slot
     * returns its cached value with no locking or coordination at all. A dirty slot dispatches to one of
     * two strategies depending on whether this call needs to be serialized against concurrent access to the
     * same object:
     *
     * <ul>
     *   <li>{@link #mustBlockUnderObjectLock} cases (the slot's very first-ever computation,
     *       {@link EmbeddingConsistencyMode#IMMEDIATE_CONSISTENCY}, or {@link #runWithSubgraphLockedForPersistence}
     *       overriding the ambient mode for this thread) -- {@link #computeBlockingUnderObjectLock} holds
     *       the whole object's lock for the duration, so no concurrent getter or setter on this object can
     *       race the computation.</li>
     *   <li>Otherwise, mode-specific and lock-free: {@link EmbeddingConsistencyMode#COALESCED_CONSISTENCY}
     *       blocks via {@link #coalescedRead}; {@link EmbeddingConsistencyMode#EVENTUAL_CONSISTENCY} fires
     *       {@link #dispatchBackground} (a no-op if one's already outstanding) and returns the cached value
     *       immediately.</li>
     * </ul>
     */
    private static EmbeddingVector readSlot(DirtyTrackingSupport state, VectorCacheSlot slot,
            Supplier<String> textSupplier) {
        if (!slot.isDirty()) {
            return slot.cachedValue();
        }
        if (mustBlockUnderObjectLock(slot)) {
            return computeBlockingUnderObjectLock(state, slot, textSupplier);
        }
        long generation = slot.currentGeneration();
        String text = textSupplier.get();
        if (consistencyMode() == EmbeddingConsistencyMode.COALESCED_CONSISTENCY) {
            return coalescedRead(slot, generation, text);
        }
        // Snapshot before dispatching, not after: dispatchBackground's virtual thread can start and commit
        // before this thread's very next line runs, so reading cachedValue() afterward would sometimes hand
        // this caller the freshly-redispatched value instead of the value that was actually on file when it
        // asked -- breaking the "this call sees state as of entry" guarantee for a stale/failed slot's very
        // next read.
        EmbeddingVector valueAtEntry = slot.cachedValue();
        dispatchBackground(slot, generation, text);
        return valueAtEntry;
    }

    /** True when a stale slot must be computed under the object's own lock rather than lock-free: the
     *  slot's very first computation (nothing to fall back on, under any mode), {@link EmbeddingConsistencyMode#IMMEDIATE_CONSISTENCY},
     *  or {@link #runWithSubgraphLockedForPersistence} overriding the ambient mode for the current thread --
     *  persistence must never write a stale vector to the database, regardless of the globally configured
     *  mode (see that method's own javadoc). */
    private static boolean mustBlockUnderObjectLock(VectorCacheSlot slot) {
        return !slot.everComputed()
                || consistencyMode() == EmbeddingConsistencyMode.IMMEDIATE_CONSISTENCY
                || FORCE_ACCURATE.get();
    }

    /**
     * Holds {@code state.objectLock()} for the full duration of a blocking recompute -- the guarantee
     * {@link EmbeddingConsistencyMode#IMMEDIATE_CONSISTENCY} makes explicit ("no further vector getters or
     * field setters can run until the embedding call returns"): every setter briefly takes this same lock
     * around its own bookkeeping (see {@link #vectorizeFieldMutated}), so a concurrent setter genuinely
     * waits rather than racing this computation, and a concurrent getter re-checks {@code isDirty()} after
     * acquiring the lock rather than assuming it still needs to compute (whoever held the lock first may
     * have already landed a fresh value while this thread was waiting). Reentrant: a persistence flush
     * already holding this same lock (via {@link #runWithSubgraphLockedForPersistence}) re-enters it
     * trivially when its own forced-accurate reads recurse through {@code vector()}/{@code summaryVector()}.
     */
    private static EmbeddingVector computeBlockingUnderObjectLock(DirtyTrackingSupport state, VectorCacheSlot slot,
            Supplier<String> textSupplier) {
        state.objectLock().lock();
        try {
            if (!slot.isDirty()) {
                return slot.cachedValue();
            }
            long generation = slot.currentGeneration();
            String text = textSupplier.get();
            return computeBlocking(slot, generation, text);
        } finally {
            state.objectLock().unlock();
        }
    }

    /**
     * Wired onto every woven {@code @Vectorize} field's setter (replacing that field's old direct
     * {@code markFieldDirty}/{@code registerDependency}/{@code propagateDirty} calls, which still happen
     * here, unchanged): a no-op reassignment (the new value {@link Objects#equals} the old one) skips
     * everything below entirely -- nothing about the field's own state, its dependents, or any cache
     * actually needs to change, under any consistency mode. Otherwise briefly holds {@code self}'s
     * {@link DirtyTrackingSupport#objectLock()} around this bookkeeping -- both so a concurrent
     * {@link EmbeddingConsistencyMode#IMMEDIATE_CONSISTENCY} getter's blocking computation genuinely
     * excludes this setter (see {@link #computeBlockingUnderObjectLock}), and so a persistence flush
     * (which holds every reachable object's lock for the whole flush -- see
     * {@link #runWithSubgraphLockedForPersistence}) actually excludes ordinary mutation regardless of mode,
     * not just {@code IMMEDIATE_CONSISTENCY}'s -- then bumps this field's own {@link VectorCacheSlot}
     * generation, and this object's {@link DirtyTrackingSupport#concatenatedTextSlot()} generation
     * (concatenatedTextVector's concatenated text depends on every field, so any one of them changing
     * invalidates it too -- but recomputing it is comparatively expensive, so it stays lazily refreshed on
     * next read rather than eagerly dispatched here). Under {@link EmbeddingConsistencyMode#EVENTUAL_CONSISTENCY}
     * or {@link EmbeddingConsistencyMode#COALESCED_CONSISTENCY}, additionally dispatches this field's own
     * eager background recompute immediately, using the value the setter already has in hand -- "vector
     * calculations are eager on mutation." Under {@link EmbeddingConsistencyMode#IMMEDIATE_CONSISTENCY},
     * mutation never triggers computation; only a subsequent read of the now-dirty slot does.
     */
    public static void vectorizeFieldMutated(Object self, String fieldName, Object oldValue, Object newValue) {
        if (Objects.equals(oldValue, newValue)) {
            return;
        }

        DirtyTrackingSupport state = stateOf(self);
        state.objectLock().lock();
        try {
            vectorizeFieldMutatedLocked(self, fieldName, newValue, state);
        } finally {
            state.objectLock().unlock();
        }
    }

    private static void vectorizeFieldMutatedLocked(Object self, String fieldName, Object newValue,
            DirtyTrackingSupport state) {
        markFieldDirty(self);
        registerDependency(self, newValue);
        propagateDirty(self);

        VectorCacheSlot fieldSlot = state.fieldSlot(fieldName);
        long fieldGeneration = fieldSlot.bumpGeneration();
        state.concatenatedTextSlot().bumpGeneration();

        EmbeddingConsistencyMode mode = consistencyMode();
        // Skip the eager dispatch for a slot that's never computed anything yet: readSlot's
        // mustBlockUnderObjectLock forces ANY read of such a slot to block and compute directly,
        // regardless of mode, since there's no prior value to fall back on -- so this eager dispatch
        // would either race that forced blocking computation (a wasted, duplicate embed() call for the
        // exact same generation) or simply be discarded unread. Once the slot has computed at least once,
        // a later mutation's eager dispatch is exactly what lets EVENTUAL_CONSISTENCY/COALESCED_CONSISTENCY
        // reads stay lock-free, so this guard only ever suppresses the genuinely redundant first case.
        if ((mode == EmbeddingConsistencyMode.EVENTUAL_CONSISTENCY || mode == EmbeddingConsistencyMode.COALESCED_CONSISTENCY)
                && fieldSlot.everComputed()) {
            String text = newValue == null ? "" : String.valueOf(newValue);
            dispatchBackground(fieldSlot, fieldGeneration, text);
        }
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
                // An absent own-vector (no @Vectorize fields) doesn't make this object's summary absent --
                // its @Summary children may still have content. So the accumulator stays null until the
                // first contributor with real dimensionality appears, and only if none ever does is the
                // whole summary absent. See EmbeddingVector.absent() (OMI-187).
                float[] sum = own.isAbsent() ? null : own.values().clone();
                String modelId = own.isAbsent() ? null : own.modelId();
                if (!summaryFieldNames.isBlank()) {
                    for (String fieldName : summaryFieldNames.split(",")) {
                        Object value = readField(self, fieldName);
                        if (value instanceof JavAIVectorizable child) {
                            EmbeddingVector childSummary = child.summaryVector();
                            // A child with no embeddable content contributes nothing at all -- it is not a
                            // dimension mismatch to report, it is an absence to skip.
                            if (childSummary.isAbsent()) {
                                continue;
                            }
                            if (sum == null) {
                                sum = new float[childSummary.dims()];
                                modelId = childSummary.modelId();
                            } else if (childSummary.dims() != sum.length) {
                                throw new IllegalStateException(
                                        "summaryVector() dimension mismatch: " + self.getClass() + "'s own vector has "
                                                + sum.length + " dims but @Summary field " + fieldName + " contributed "
                                                + childSummary.dims() + " -- are they using the same model?");
                            }
                            VectorMath.addWeighted(sum, childSummary.values(), DEFAULT_SUMMARY_DECAY);
                        }
                    }
                }
                EmbeddingVector recomputed = sum == null
                        ? EmbeddingVector.absent()
                        : new EmbeddingVector(VectorMath.normalize(sum), modelId, sum.length, Instant.now());
                state.cacheSummaryVector(recomputed);
                state.clearSummaryDirty();
                // vector()'s own cache no longer clears this (it has no cache of its own to gate on
                // anymore -- see vector()'s javadoc); summaryVector() is now the only remaining consumer of
                // this flag, so it must be the one to clear it, or it would stay set forever after the
                // first mutation and force a full recompute on every subsequent call.
                state.clearFieldDirty();
            } finally {
                exitSummaryComputation(self);
            }
        }
        return state.cachedSummaryVector();
    }

    private static String fieldTextOf(Object self, String fieldName) {
        Object value = readField(self, fieldName);
        return value == null ? "" : String.valueOf(value);
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
        for (Field field : allFields(node.getClass())) {
            if (field.getName().equals(STATE_FIELD) || !isFieldSearchVisible(field)) {
                // STATE_FIELD is internal bookkeeping, never part of the domain graph. A field marked
                // @SearchVisibility(PRIVATE) is a hard stop -- doc/spec/vector-core.md's "search-semantic
                // visibility... independent of Java access modifiers": don't traverse through it at all,
                // so nothing reachable only via this field is discoverable through query().
                continue;
            }
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
        // Type-level @SearchVisibility(PRIVATE) gates *matching* only, not traversal: a node can be a
        // deliberate pass-through (its own instances never surface as hits) while its descendants remain
        // fully reachable. That's a different axis than the field-level check above, which gates whether
        // we recurse through a specific edge at all.
        if (type.isInstance(unit) && isTypeSearchVisible(unit.getClass())) {
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

    private static boolean isFieldSearchVisible(Field field) {
        SearchVisibility visibility = field.getAnnotation(SearchVisibility.class);
        return visibility == null || visibility.value() != SearchVisibility.Visibility.PRIVATE;
    }

    private static boolean isTypeSearchVisible(Class<?> type) {
        SearchVisibility visibility = type.getAnnotation(SearchVisibility.class);
        return visibility == null || visibility.value() != SearchVisibility.Visibility.PRIVATE;
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

    // ---- persistence support: whole-subgraph locking + forced accuracy ----------------------

    /**
     * Runs {@code action} with every {@link JavAIVectorizable} reachable from {@code root} (root included)
     * locked for the duration, and every {@code fieldVector}/{@code concatenatedTextVector} read on this
     * thread forced to compute accurately (blocking, never serving a stale value) regardless of the
     * globally configured {@link EmbeddingConsistencyMode} -- the two guarantees {@code javai-persistence}
     * needs: the database must never see a vector that doesn't match its field's current value, and nothing
     * in the locked subgraph can mutate out from under the flush while it's in progress. The second
     * guarantee is only real because every setter, under every consistency mode, briefly takes this same
     * per-object lock around its own bookkeeping (see {@link #vectorizeFieldMutated}) -- without that, an
     * ordinary {@link EmbeddingConsistencyMode#EVENTUAL_CONSISTENCY}/{@link EmbeddingConsistencyMode#COALESCED_CONSISTENCY}
     * setter would proceed completely unobstructed while this method believes the subgraph is frozen.
     *
     * <p>Locks are acquired in {@link DirtyTrackingSupport#sequenceNumber()} order (assigned once, at each
     * object's construction) -- a fixed, global, per-object order that holds regardless of which root or
     * traversal order a particular call started from, which is what makes this deadlock-free even when two
     * overlapping subgraphs are locked concurrently by separate persistence operations.
     */
    public static void runWithSubgraphLockedForPersistence(Object root, Runnable action) {
        List<DirtyTrackingSupport> states = new ArrayList<>();
        for (Object node : reachableVectorizables(root)) {
            states.add(stateOf(node));
        }
        states.sort(Comparator.comparingLong(DirtyTrackingSupport::sequenceNumber));

        int locked = 0;
        try {
            for (DirtyTrackingSupport state : states) {
                state.objectLock().lock();
                locked++;
            }
            boolean alreadyForcing = FORCE_ACCURATE.get();
            FORCE_ACCURATE.set(true);
            try {
                action.run();
            } finally {
                FORCE_ACCURATE.set(alreadyForcing);
            }
        } finally {
            for (int i = 0; i < locked; i++) {
                states.get(i).objectLock().unlock();
            }
        }
    }

    /**
     * Every {@link JavAIVectorizable} reachable from {@code root} (including {@code root} itself, and
     * including an intermediate collection like a {@code JavAIArrayList} in its own right, not just its
     * elements -- {@code CollectionVectorSupport} reads and writes that collection's own
     * {@link DirtyTrackingSupport} state too, so it needs locking exactly like any domain object does).
     *
     * <p>Deliberately narrower than it might look: only ever reflects over a node's declared fields when
     * that node is itself {@code JavAIVectorizable} (matching {@link #query}'s own "only descend into
     * graph-shaped values" rule) -- a plain leaf (a {@code String}, a boxed number...) is never reflected
     * into, both because its own fields aren't meaningful graph edges and because {@code java.lang}'s own
     * fields aren't reliably reflectively accessible under the module system regardless.
     */
    public static Set<Object> reachableVectorizables(Object root) {
        Set<Object> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        Set<Object> result = Collections.newSetFromMap(new IdentityHashMap<>());
        collectReachableVectorizables(root, visited, result);
        return result;
    }

    private static void collectReachableVectorizables(Object node, Set<Object> visited, Set<Object> result) {
        if (node == null || !visited.add(node)) {
            return;
        }
        if (node instanceof JavAIVectorizable) {
            result.add(node);
        }
        if (node instanceof Map<?, ?> || node instanceof Iterable<?>) {
            // A collection's own elements are the graph edges worth following; its JDK-internal fields
            // (backing array, size, ...) never are, so this never falls through to the field-reflection
            // loop below for the collection object itself.
            for (Object element : expand(node)) {
                collectReachableVectorizables(element, visited, result);
            }
            return;
        }
        if (!(node instanceof JavAIVectorizable)) {
            return;
        }
        for (Field field : allFields(node.getClass())) {
            if (field.getName().equals(STATE_FIELD)) {
                continue;
            }
            field.setAccessible(true);
            Object value;
            try {
                value = field.get(node);
            } catch (IllegalAccessException e) {
                throw new IllegalStateException(
                        "Cannot read " + field + " while collecting reachable vectorizables", e);
            }
            if (value != null) {
                collectReachableVectorizables(value, visited, result);
            }
        }
    }

    // ---- shared reflection helpers -------------------------------------------------------------

    private static String concatenatedFieldText(Object self, String vectorizeFieldNames) {
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

    /** Public (not just used internally) so {@code javai-substrate}'s woven setter advice can read a
     *  field's pre-assignment value at {@code @Advice.OnMethodEnter} time -- the no-op-reassignment check
     *  (see {@link #vectorizeFieldMutated}'s own javadoc) needs the value as it was *before* the setter's
     *  own assignment runs, which this same reflective path already knows how to find. */
    public static Object readField(Object self, String fieldName) {
        Field field = findField(self.getClass(), fieldName);
        try {
            field.setAccessible(true);
            return field.get(self);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Cannot read field " + fieldName + " on " + self.getClass(), e);
        }
    }

    private static DirtyTrackingSupport stateOf(Object self) {
        Field field = findField(self.getClass(), STATE_FIELD);
        try {
            field.setAccessible(true);
            DirtyTrackingSupport state = (DirtyTrackingSupport) field.get(self);
            if (state == null) {
                state = new DirtyTrackingSupport();
                field.set(self, state);
            }
            return state;
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(
                    "Woven class is missing the expected " + STATE_FIELD + " field on " + self.getClass(), e);
        }
    }

    /** Every field declared anywhere in {@code type}'s class hierarchy, not just on {@code type} itself. */
    private static List<Field> allFields(Class<?> type) {
        List<Field> fields = new ArrayList<>();
        for (Class<?> current = type; current != null && current != Object.class; current = current.getSuperclass()) {
            fields.addAll(Arrays.asList(current.getDeclaredFields()));
        }
        return fields;
    }

    /** Finds {@code fieldName} anywhere in {@code type}'s class hierarchy, not just declared on {@code type}. */
    private static Field findField(Class<?> type, String fieldName) {
        for (Class<?> current = type; current != null && current != Object.class; current = current.getSuperclass()) {
            try {
                return current.getDeclaredField(fieldName);
            } catch (NoSuchFieldException ignored) {
                // keep searching up the hierarchy
            }
        }
        throw new IllegalStateException(
                "Expected field " + fieldName + " on " + type + " or one of its superclasses");
    }
}
