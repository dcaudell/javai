package dev.xtrafe.javai.model;

import dev.xtrafe.javai.annotations.SearchVisibility;
import dev.xtrafe.javai.vector.DirtyTrackingSupport;
import dev.xtrafe.javai.vector.EmbeddingBatchLimits;
import dev.xtrafe.javai.vector.EmbeddingVector;
import dev.xtrafe.javai.vector.JavAIDirtyTracking;
import dev.xtrafe.javai.vector.JavAIEmbeddingProvider;
import dev.xtrafe.javai.vector.EmbeddingProviderTextEmbeddingsInference;
import dev.xtrafe.javai.vector.VectorCacheSlot;
import dev.xtrafe.javai.vector.VectorCacheSlot.PendingComputation;
import dev.xtrafe.javai.vector.VectorMath;

import dev.xtrafe.javai.annotations.ExternalVector;
import dev.xtrafe.javai.annotations.Summary;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.function.BiConsumer;
import java.util.function.Predicate;
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
    /** Everything is a real object until a persistence layer says otherwise -- see
     *  {@link #configureInitializationCheck(Predicate)}. */
    private static volatile Predicate<Object> initializationCheck = value -> true;

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
     * Lets a persistence layer tell Vector Core which values are unresolved placeholders it must not touch
     * (OMI-271).
     *
     * <p>Every graph walk here reads fields reflectively and iterates whatever collections it finds. Against
     * an ORM that is not a neutral act: iterating an uninitialized lazy collection <em>is</em> loading it,
     * so a walk that means only to look ends up issuing SELECTs -- and, off a detached instance, throwing
     * where it would otherwise have looked. Vector Core cannot recognise such a value itself without
     * depending on an ORM, which it deliberately does not.
     *
     * <p>So the recogniser is supplied instead: {@code javai-persistence}'s Hibernate backend installs
     * {@code Hibernate::isInitialized}. The default answers {@code true} for everything, which is exactly
     * right for a plain object graph with no persistence layer under it -- nothing is a placeholder, so
     * nothing is skipped.
     *
     * <p>Skipping is always safe, never a silent loss: an association nobody has resolved holds no mutation
     * to lock, no vector to warm, and no id to assign. The same argument {@code versionedEntitiesById} and
     * {@code writeVectorsForRelatedEntity} already make on the persistence side, one layer down.
     */
    public static void configureInitializationCheck(Predicate<Object> check) {
        initializationCheck = Objects.requireNonNull(check, "initialization check");
    }

    /** Whether {@code value} is a real, resolved object rather than a placeholder -- see
     *  {@link #configureInitializationCheck(Predicate)}. {@code null} counts as resolved: it is a value. */
    public static boolean isResolved(Object value) {
        return value == null || initializationCheck.test(value);
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
        // Visit each reachable dependent once, rather than stopping at the first already-SummaryDirty node.
        //
        // The old short-circuit was "if it's already dirty, its ancestors must be too". That holds for a
        // monotone boolean whenever clearing an ancestor also clears its descendants -- which is the case on
        // the path summaryVector() itself takes, since it recurses into each @Summary child and clears it on
        // the way. (Whether it holds for *every* dependent edge is a separate question: dependents() is a
        // back-edge set that a mutation registers, not only the @Summary graph. Not investigated here, and
        // moot below, since this walk no longer relies on the invariant either way.)
        //
        // Concatenated text broke that invariant (OMI-191). Assembling an ancestor's text calls
        // concatenatedText() on its descendants, which is pure string work and commits nothing -- so the
        // ancestor's slot becomes clean while its descendants' stay dirty. A later mutation down there would
        // then hit an already-SummaryDirty node, prune, and leave the clean ancestor holding text that is
        // silently out of date. Pruning on either consumer's state is unsound once two consumers clear
        // independently, which is the same lesson OMI-187 learned about sharing one flag, one level up: it
        // is the *walk* that was overfitted to a single reader, not just the flag.
        //
        // Cost: each mutation now walks its full reachable dependent set instead of stopping early. The set
        // is ancestors, not descendants -- typically shallow -- and each node is visited exactly once.
        Set<Object> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        while (!pending.isEmpty()) {
            JavAIDirtyTracking current = pending.remove(pending.size() - 1);
            if (!visited.add(current)) {
                continue; // already handled on this walk -- also what makes a dependency cycle terminate
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
        // Opt-in, and free when declined (OMI-191): no slot touched, no text assembled, no embedding call.
        // This method used to run for every vectorizable that was ever asked, at the cost of a real model
        // call, producing something no backend stored and no query could reach.
        if (!participatesInConcatenation(self.getClass())) {
            return EmbeddingVector.absent();
        }
        DirtyTrackingSupport state = stateOf(self);
        VectorCacheSlot slot = state.concatenatedTextSlot();
        return readSlot(state, slot, () -> concatenatedText(self, vectorizeFieldNames));
    }

    /**
     * Assembles the text behind {@link #concatenatedTextVector} -- this object's own {@code @Vectorize}
     * fields (if its type opted in), then each {@code @Summary(concatenate = true)} field's contribution,
     * parent-first, newline-separated.
     *
     * <p><b>Each node contributes exactly once per assembly</b>, tracked by {@link #CONCATENATION_PATH}.
     * This is a deliberate divergence from {@link #summaryVector}, where a node reachable by two paths
     * stacks additively: that is meaningful vector arithmetic, whereas the same paragraph appearing twice in
     * one text merely skews its embedding toward whatever it happens to say. The colouring doubles as cycle
     * safety, so no separate guard is needed.
     */
    public static String concatenatedText(Object self, String vectorizeFieldNames) {
        return assembleColoured(self, () -> {
            StringBuilder text = new StringBuilder();
            if (concatenatesOwnFields(self.getClass())) {
                text.append(concatenatedFieldText(self, vectorizeFieldNames));
            }
            for (String fieldName : concatenateFieldNames(self.getClass())) {
                Object value = readField(self, fieldName);
                if (value instanceof JavAIVectorizable child) {
                    // The child assembles its own contribution, using its own knowledge of its own fields --
                    // and, if it is a JavAI collection, of its members. Never re-derived from out here.
                    append(text, child.concatenatedText());
                }
            }
            // null, not "": no text is not the same as empty text. See JavAIVectorizable.concatenatedText.
            return text.isEmpty() ? null : text.toString();
        });
    }

    /**
     * A JavAI collection's contribution: its members' text, in iteration order.
     *
     * <p>A collection aggregates whenever it is asked, and is never asked unless the <b>owning field</b>
     * carries {@code @Summary(concatenate = true)} -- a collection never decides for itself whether folding
     * its members into its container is meaningful. Members still each decide whether they produce text at
     * all, via their own type-level opt-in.
     */
    public static String concatenatedTextOfCollection(Object collection, Collection<?> elements) {
        return assembleColoured(collection, () -> {
            StringBuilder text = new StringBuilder();
            for (Object element : elements) {
                if (element instanceof JavAIVectorizable member) {
                    append(text, member.concatenatedText());
                }
            }
            // A collection whose members contribute nothing has no text of its own to offer.
            return text.isEmpty() ? null : text.toString();
        });
    }

    /**
     * Runs {@code body} with {@code node} coloured, so it contributes exactly once per assembly.
     *
     * <p>The colouring is what makes a diamond contribute its shared node once and what makes a cycle
     * terminate; both fall out of the same set rather than needing separate handling.
     */
    private static String assembleColoured(Object node, Supplier<String> body) {
        Set<Object> path = CONCATENATION_PATH.get();
        boolean root = path.isEmpty();
        if (!path.add(node)) {
            // Already contributed somewhere in this assembly -- a diamond's shared node, or a cycle. Nothing
            // more to add from here, which is null rather than "" for the reason given on
            // JavAIVectorizable.concatenatedText.
            return null;
        }
        try {
            return body.get();
        } finally {
            // Only the root clears. Removing each node on exit -- the way exitSummaryComputation does --
            // would let a node reached again by a later path contribute a second time, which is exactly the
            // stacking this must not do. Clearing the ThreadLocal outright (not just emptying the set) keeps
            // pooled and virtual threads from retaining an identity set between unrelated assemblies.
            if (root) {
                CONCATENATION_PATH.remove();
            }
        }
    }

    /**
     * A collection's own {@code concatenatedTextVector()} -- the embedding of {@link
     * #concatenatedTextOfCollection}, cached in the collection's own slot and mode-aware exactly like every
     * other vector read.
     *
     * <p>These used to throw {@code UnsupportedOperationException("has no @Vectorize fields of its own")},
     * which was true and beside the point: a collection's concatenated text was never going to come from
     * fields it doesn't have, it comes from its members (OMI-191).
     */
    public static EmbeddingVector collectionConcatenatedTextVector(DirtyTrackingSupport state, Object collection,
            Collection<?> elements) {
        return readSlot(state, state.concatenatedTextSlot(),
                () -> concatenatedTextOfCollection(collection, elements));
    }

    /** Appends {@code addition} as its own block, keeping exactly one newline between contributions. */
    private static void append(StringBuilder text, String addition) {
        if (addition == null || addition.isBlank()) {
            return;
        }
        if (!text.isEmpty() && text.charAt(text.length() - 1) != '\n') {
            text.append('\n');
        }
        text.append(addition);
    }

    /**
     * Which nodes have already contributed to the concatenated text currently being assembled on this
     * thread. Identity-based, like {@link #SUMMARY_COMPUTATION_PATH}, but with the opposite lifetime: that
     * one is a stack (entries leave on exit, so a node reachable twice contributes twice), this one is a
     * colouring that survives for the whole assembly.
     */
    private static final ThreadLocal<Set<Object>> CONCATENATION_PATH =
            ThreadLocal.withInitial(() -> Collections.newSetFromMap(new IdentityHashMap<>()));

    /**
     * A single {@code @Vectorize} field's own cached vector -- real per-field caching (unlike this method's
     * pre-concurrency-model incarnation, which always recomputed with no cache at all: a single shared
     * {@code FieldDirty} flag couldn't safely gate several independently-cached per-field vectors, but a
     * dedicated {@link VectorCacheSlot} per field name can). Mode-aware -- see {@link EmbeddingConsistencyMode}'s
     * own javadoc for what each of the three modes guarantees on this read path.
     */
    public static EmbeddingVector fieldVector(Object self, String fieldName) {
        // An @ExternalVector shares this name space (its slot is an ordinary field slot keyed by its own
        // name) but nothing about its resolution: it is never computed, so it must not reach readSlot at
        // all. Checked first, and free for the overwhelmingly common class that declares none.
        if (isExternalVectorName(self.getClass(), fieldName)) {
            return externalVector(self, fieldName);
        }
        DirtyTrackingSupport state = stateOf(self);
        VectorCacheSlot slot = state.fieldSlot(fieldName);
        return readSlot(state, slot, () -> fieldTextOf(self, fieldName));
    }

    // ---- model-scoped aggregates (OMI-290) -------------------------------------------------------

    /**
     * This object's aggregate <b>restricted to one embedding model</b> -- the centroid of every vector it
     * carries that {@code modelId} produced, {@code @Vectorize} field and {@code @ExternalVector} alike.
     *
     * <p><b>Why an aggregate has to be able to name a model.</b> Cosine similarity between two models'
     * vectors is not a weaker answer, it is not an answer at all, and {@link VectorMath} enforces that by
     * refusing to combine them. So once an object carries vectors from two models -- an image embedding
     * beside a text one -- "this object's vector" stops being a single question. {@link #vector(Object,
     * String)} continues to answer the text one, unchanged and uncached; this answers whichever is asked
     * for.
     *
     * <p><b>Nothing is computed speculatively.</b> A {@code @Vectorize} field's model is whatever the
     * configured provider is, so when {@code modelId} is not that, the fields are skipped without being
     * read -- asking for the image aggregate must not embed the caption in order to discard it. An external
     * vector's model is declared, so it too is matched without touching the slot.
     *
     * <p>Uncached, like {@link #vector(Object, String)}, and for the same reason: it recombines
     * already-computed vectors, so there is no expensive work to defer and no third staleness question to
     * answer.
     */
    public static EmbeddingVector vector(Object self, String vectorizeFieldNames, String modelId) {
        if (modelId == null) {
            return EmbeddingVector.absent();
        }
        List<EmbeddingVector> matching = new ArrayList<>();
        if (!vectorizeFieldNames.isBlank() && modelId.equals(currentModelId())) {
            for (String fieldName : vectorizeFieldNames.split(",")) {
                collectIfPresent(matching, fieldVector(self, fieldName), modelId);
            }
        }
        for (Map.Entry<String, ExternalVectorSpec> declared : externalVectors(self.getClass()).entrySet()) {
            if (modelId.equals(declared.getValue().model())) {
                collectIfPresent(matching, externalVector(self, declared.getKey()), modelId);
            }
        }
        return VectorMath.centroid(matching);
    }

    /**
     * {@link #summaryVector(Object, String, String)} restricted to one embedding model -- this object's
     * own {@code modelId} aggregate at full weight, plus each {@code @Summary} child's {@code modelId}
     * summary at the decay factor.
     *
     * <p>The formula is unchanged; only the vectors admitted to it are. That is what lets a container
     * summarize its members' image vectors and its members' text vectors as two separate, individually
     * coherent aggregates, rather than one that cannot be computed at all.
     *
     * <p><b>Deliberately uncached</b>, unlike the unqualified form, which caches into the object's single
     * summary slot. Caching per model would mean a slot per model, an invalidation rule per model, and a
     * third dirty-flag family -- for arithmetic over vectors that are themselves already cached. A
     * container with nothing in that model simply returns absent, cheaply.
     */
    public static EmbeddingVector summaryVector(Object self, String summaryFieldNames,
            String vectorizeFieldNames, String modelId) {
        if (!enterSummaryComputation(self)) {
            // Cycle -- treat the repeated node as a leaf for this path, exactly as the unqualified form does.
            return vector(self, vectorizeFieldNames, modelId);
        }
        try {
            List<VectorMath.WeightedVector> terms = new ArrayList<>();
            terms.add(new VectorMath.WeightedVector(vector(self, vectorizeFieldNames, modelId), 1.0));
            if (!summaryFieldNames.isBlank()) {
                for (String fieldName : summaryFieldNames.split(",")) {
                    Object value = readField(self, fieldName);
                    if (value instanceof JavAIVectorizable child) {
                        terms.add(new VectorMath.WeightedVector(
                                child.summaryVector(modelId), DEFAULT_SUMMARY_DECAY));
                    }
                }
            }
            return VectorMath.normalize(VectorMath.weightedSum(terms));
        } finally {
            exitSummaryComputation(self);
        }
    }

    /** Adds {@code vector} to {@code target} when it is real and from {@code modelId} -- absent and
     *  foreign vectors are simply not admitted, which is what keeps the centroid computable. */
    private static void collectIfPresent(List<EmbeddingVector> target, EmbeddingVector vector, String modelId) {
        if (vector != null && !vector.isAbsent() && modelId.equals(vector.modelId())) {
            target.add(vector);
        }
    }

    // ---- externally-supplied vectors (OMI-290) ---------------------------------------------------

    /**
     * An {@code @ExternalVector}'s current value -- the vector most recently supplied for the content this
     * object currently references, or {@link EmbeddingVector#absent()} when there is none.
     *
     * <p><b>This never computes, never blocks and never dispatches</b>, under any
     * {@link EmbeddingConsistencyMode} and including on a thread inside
     * {@link #runWithSubgraphLockedForPersistence}. Saying "external vectors are eventually consistent"
     * would not be enough to get that: {@code EVENTUAL_CONSISTENCY} still blocks a slot's very first read
     * (there being no prior value to serve) and still yields to that method's forced-accuracy override --
     * so both would end up waiting on a provider that could never produce this vector, and would in fact
     * ask the *text* provider for it. The mode axis simply does not apply here, which is why this path
     * bypasses {@link #readSlot} rather than configuring it.
     *
     * <p><b>Absence has two causes and one meaning.</b> Nothing has been supplied yet, or what was supplied
     * describes content this object no longer references -- see {@link #supplyVector}. Both mean "no vector
     * here", which every arithmetic site already skips and every ranking already sorts last, so no caller
     * needs a new state to handle. Serving the superseded vector instead would be worse than serving
     * nothing: it is not stale, it is a confident description of different content.
     */
    public static EmbeddingVector externalVector(Object self, String vectorName) {
        ExternalVectorSpec spec = requireExternalVector(self.getClass(), vectorName);
        DirtyTrackingSupport state = stateOf(self);
        EmbeddingVector value = state.fieldSlot(vectorName).cachedValue();
        if (value == null) {
            return EmbeddingVector.absent();
        }
        String suppliedFor = state.externalVectorKey(vectorName);
        return suppliedFor != null && suppliedFor.equals(contentKeyOf(self, spec))
                ? value
                : EmbeddingVector.absent();
    }

    /**
     * Hands JavAI a vector it could not have computed -- the push half of {@code @ExternalVector}.
     *
     * <p>This overload is for a caller holding the live object; {@code JavAIRepository.supplyVector} is for
     * one holding only an id, which is the ordinary shape for a queue consumer and avoids loading an entity
     * graph purely to store one row. Prefer the repository form in a pipeline; prefer this one when the
     * object is already in hand (including in tests), since it also warms the in-memory slot.
     *
     * <p><b>{@code computedFor} is what makes this safe under at-least-once delivery.</b> The producer
     * echoes back the {@link dev.xtrafe.javai.annotations.ExternalVector#keyField()} value it actually
     * embedded; if the object has moved on to different content since, the vector is <em>discarded</em> and
     * this returns {@code false}. That is the same shape as {@link VectorCacheSlot}'s generation check, in
     * the only currency an out-of-process producer has -- it has no way to know about generations, and the
     * content key is the thing it does know.
     *
     * @return {@code true} if the vector was stored, {@code false} if it described content this object no
     *         longer references -- not an error, and the ordinary outcome of a slow producer racing an edit
     * @throws IllegalArgumentException if {@code vectorName} is not declared on this class, if the vector's
     *                                  own {@code modelId()} disagrees with the declared model, or if the
     *                                  vector is null/absent
     */
    public static boolean supplyVector(Object self, String vectorName, EmbeddingVector vector,
            String computedFor) {
        ExternalVectorSpec spec = requireExternalVector(self.getClass(), vectorName);
        if (vector == null || vector.isAbsent()) {
            // Absence is a *derived* state here -- "nothing supplied, or superseded" -- so accepting it as
            // an input would make two very different situations indistinguishable at rest, and would let a
            // producer bug quietly erase a good vector.
            throw new IllegalArgumentException("Cannot supply an absent vector for external vector '"
                    + vectorName + "' on " + self.getClass().getName() + ". Absence is what this vector"
                    + " already reports when nothing has been supplied; to withdraw one, change the content"
                    + " key it was computed for.");
        }
        if (!spec.model().equals(vector.modelId())) {
            throw new IllegalArgumentException("External vector '" + vectorName + "' on "
                    + self.getClass().getName() + " is declared as model '" + spec.model()
                    + "' but the supplied vector reports '" + vector.modelId() + "'. Storage is partitioned"
                    + " by model, so accepting this would file the vector where nothing looks for it.");
        }
        if (computedFor == null || !computedFor.equals(contentKeyOf(self, spec))) {
            return false;
        }
        DirtyTrackingSupport state = stateOf(self);
        VectorCacheSlot slot = state.fieldSlot(vectorName);
        slot.commitSuccess(slot.currentGeneration(), vector);
        state.recordExternalVectorKey(vectorName, computedFor);
        return true;
    }

    /**
     * Seeds an external vector read back from storage, together with the content key it was written for --
     * the {@link #hydrateFieldVector} counterpart for a vector nothing in process could recompute.
     *
     * <p>Deliberately <em>not</em> subject to that method's pristine-slot rule. That rule exists to stop a
     * stored vector overwriting a change the caller has already made in memory, which is a real hazard when
     * a read could otherwise recompute the right answer. Here nothing can: refusing to hydrate would leave
     * the slot empty forever rather than merely unoptimised. The content-key comparison covers the same
     * ground more directly -- a hydrated vector whose key no longer matches the entity simply reads absent.
     */
    public static void hydrateExternalVector(Object self, String vectorName, EmbeddingVector vector,
            String computedFor) {
        if (vector == null || vector.isAbsent()) {
            return;
        }
        DirtyTrackingSupport state = stateOf(self);
        VectorCacheSlot slot = state.fieldSlot(vectorName);
        slot.commitSuccess(slot.currentGeneration(), vector);
        state.recordExternalVectorKey(vectorName, computedFor);
    }

    /** Every {@code @ExternalVector} name declared on {@code type} or inherited, in declaration order --
     *  public for {@code javai-substrate} (which bakes them onto the woven class) and {@code javai-persistence}
     *  (which stores and hydrates them). */
    public static List<String> externalVectorNames(Class<?> type) {
        return List.copyOf(externalVectors(type).keySet());
    }

    /** The model {@code vectorName} is declared to come from -- what the persistence layer partitions
     *  storage by, and what {@link #supplyVector} checks a supplied vector against. */
    public static String externalVectorModel(Class<?> type, String vectorName) {
        return requireExternalVector(type, vectorName).model();
    }

    /** The current value of {@code vectorName}'s key field, as stored alongside the vector -- what the
     *  persistence layer writes into {@code computed_for}. */
    public static String externalVectorKey(Object self, String vectorName) {
        return contentKeyOf(self, requireExternalVector(self.getClass(), vectorName));
    }

    public static boolean isExternalVectorName(Class<?> type, String name) {
        Map<String, ExternalVectorSpec> declared = externalVectors(type);
        return !declared.isEmpty() && declared.containsKey(name);
    }

    private static String contentKeyOf(Object self, ExternalVectorSpec spec) {
        Object value = readField(self, spec.keyField());
        return value == null ? null : String.valueOf(value);
    }

    private static ExternalVectorSpec requireExternalVector(Class<?> type, String vectorName) {
        ExternalVectorSpec spec = externalVectors(type).get(vectorName);
        if (spec == null) {
            throw new IllegalArgumentException(type.getName() + " declares no @ExternalVector named '"
                    + vectorName + "'" + (externalVectors(type).isEmpty()
                            ? " (it declares none at all)"
                            : " -- it declares " + externalVectors(type).keySet()));
        }
        return spec;
    }

    /**
     * {@code @ExternalVector} declarations for a class, its own first and then each ancestor's, cached per
     * class exactly like the concatenation opt-ins.
     *
     * <p>Walking the hierarchy means a shared base may declare one for a whole family, while a nearer
     * declaration of the same name wins -- ordinary override intuition. Note this is a walk rather than
     * {@code @Inherited}: the annotation is {@code @Repeatable}, and the two interact in ways that are far
     * less obvious to a reader than a loop.
     */
    private static Map<String, ExternalVectorSpec> externalVectors(Class<?> type) {
        return EXTERNAL_VECTORS.computeIfAbsent(type, JavAIRuntime::readExternalVectors);
    }

    private static Map<String, ExternalVectorSpec> readExternalVectors(Class<?> type) {
        Map<String, ExternalVectorSpec> declared = new LinkedHashMap<>();
        for (Class<?> current = type; current != null && current != Object.class; current = current.getSuperclass()) {
            for (ExternalVector annotation : current.getDeclaredAnnotationsByType(ExternalVector.class)) {
                // putIfAbsent, walking downward-first: the nearest declaration of a name wins.
                declared.putIfAbsent(annotation.name(),
                        new ExternalVectorSpec(annotation.name(), annotation.keyField(), annotation.model()));
            }
        }
        // unmodifiableMap over the LinkedHashMap, not Map.copyOf: declaration order is part of what this
        // returns (externalVectorNames feeds the weaver and the storage layer), and Map.copyOf does not keep it.
        return Collections.unmodifiableMap(declared);
    }

    private record ExternalVectorSpec(String name, String keyField, String model) {
    }

    private static final Map<Class<?>, Map<String, ExternalVectorSpec>> EXTERNAL_VECTORS =
            new ConcurrentHashMap<>();

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
                // Captured *before* the text is read, so a mutation racing this pass makes the commit below
                // lose rather than land a vector computed from a value the field has already moved on from --
                // exactly what the concatenated pass below does, and for the same reason.
                long generation = slot.currentGeneration();
                String text = fieldTextOf(object, fieldName);
                if (text == null || text.isBlank()) {
                    continue; // no content: absent, and nothing to batch -- see embedText
                }
                pending.computeIfAbsent(text, key -> new ArrayList<>())
                        .add(new FieldRef(slot, generation));
            }
        }
        if (pending.isEmpty()) {
            return;
        }

        embedInBatches(new ArrayList<>(pending.keySet()), batchSize, (text, vector) -> {
            for (FieldRef ref : pending.get(text)) {
                // commitSuccess, not hydrateFieldVector (OMI-266). Hydration is for a vector read back
                // from the database, so it deliberately refuses any slot that has ever computed or has
                // been mutated since construction -- the pristine-slot rule that stops a stored vector
                // overwriting a real change. Applied here it silently discarded the result for every
                // slot that had computed before: the batch embedded the text, threw the vector away, and
                // the read that followed embedded the identical text a second time. Invisible while the
                // only caller seeded fresh objects, and strictly worse than not batching for the ordinary
                // load-mutate-save path. The generation captured above is what makes the plain commit
                // safe: a mutation that landed since is a newer attempt, and commitSuccess rejects this
                // one rather than overwriting it.
                ref.slot().commitSuccess(ref.generation(), vector);
            }
        });

        // The concatenated-text pass runs last, after every field vector is warm (OMI-191). Order matters:
        // assembling an object's text reads its descendants, and doing that while their own field vectors
        // were still cold would interleave text assembly with per-field embedding calls -- the very
        // one-at-a-time discovery this method exists to avoid.
        precomputeConcatenatedTextVectors(objects, batchSize);
    }

    /**
     * Warms the concatenated-text slot of every participating object, in as few provider calls as possible
     * (OMI-191).
     *
     * <p>This is why assembly is deliberately pure string work with no embedding in it: the whole subtree
     * walk can finish for every object first, and only then does anything reach the network. So a graph of
     * any size and shape costs one embedding per participating object, in batches -- rather than one call
     * discovered at a time, deep inside a read.
     *
     * <p>Texts are de-duplicated across the batch exactly like the field pass, so two objects that assemble
     * to the same text are embedded once between them.
     */
    private static void precomputeConcatenatedTextVectors(Collection<?> objects, int batchSize) {
        // Distinct text -> every slot waiting on it, each paired with the generation its text was read at.
        Map<String, List<PendingConcatenation>> pending = new LinkedHashMap<>();
        for (Object object : objects) {
            if (!(object instanceof JavAIVectorizable vectorizable)
                    || !participatesInConcatenation(object.getClass())) {
                continue;
            }
            VectorCacheSlot slot = stateOf(object).concatenatedTextSlot();
            if (!slot.isDirty()) {
                continue; // already accurate; nothing to compute
            }
            // Captured *before* assembling, so a mutation racing this pass makes the commit below lose
            // rather than land a vector computed from text that is already out of date.
            long generation = slot.currentGeneration();
            String text = vectorizable.concatenatedText();
            if (text == null || text.isBlank()) {
                continue; // nothing to embed -- absent, exactly as an unread slot already is
            }
            pending.computeIfAbsent(text, key -> new ArrayList<>())
                    .add(new PendingConcatenation(slot, generation));
        }
        if (pending.isEmpty()) {
            return;
        }

        embedInBatches(new ArrayList<>(pending.keySet()), batchSize, (text, vector) -> {
            for (PendingConcatenation waiting : pending.get(text)) {
                waiting.slot().commitSuccess(waiting.generation(), vector);
            }
        });
    }

    /**
     * Embeds {@code texts} in as few provider calls as the provider will actually accept, handing each text
     * its own vector as the batches come back.
     *
     * <p><b>Three ceilings, not one</b> (OMI-266). The caller's {@code batchSize} bounds how much work is
     * gathered before anything is sent; the provider's {@link JavAIEmbeddingProvider#maxBatchSize()} and
     * {@link JavAIEmbeddingProvider#maxBatchTokens()} bound what it is willing to receive. All three apply,
     * and the smallest wins -- a caller asking for 100 against a default Text Embeddings Inference server
     * gets batches of 32, because that is TEI's own {@code max_client_batch_size} and a 100-input request
     * would simply be refused.
     *
     * <p>The size ceiling is the one that did not exist before, and it is separate from
     * {@code EmbeddingInputLimits}' per-input truncation on purpose: that bounds each text against the
     * model's context window and is explicitly <em>per member, never per batch</em>, so a hundred
     * individually-legal texts still summed to a request no provider agreed to accept -- against a
     * 32,768-token local model, roughly 9.4 MiB in one body.
     *
     * <p>One batched request costs <b>one</b> permit from {@link #embeddingCallGate()}, not one per text
     * (OMI-213): the gate bounds concurrent <em>calls into the provider</em>, and a batch is one call on one
     * connection however many texts it carries. Charging per text would make the gate throttle batching
     * itself -- a 100-text batch against a gate of 8 could never acquire enough permits, so bulk seeding
     * would deadlock rather than be bounded.
     */
    private static void embedInBatches(List<String> texts, int batchSize,
            BiConsumer<String, EmbeddingVector> onVector) {
        JavAIEmbeddingProvider provider = embeddingProvider();
        int providerCap = provider.maxBatchSize();
        int countCap = providerCap > 0 ? Math.min(batchSize, providerCap) : batchSize;
        int charCap = EmbeddingBatchLimits.characterBudget(provider.maxBatchTokens());
        for (List<String> batch : EmbeddingBatchLimits.split(texts, countCap, charCap)) {
            List<EmbeddingVector> vectors;
            acquireUninterruptibly(embeddingCallGate());
            try {
                vectors = provider.embedAll(batch);
            } finally {
                embeddingCallGate().release();
            }
            if (vectors.size() != batch.size()) {
                throw new IllegalStateException("embedAll returned " + vectors.size() + " vectors for "
                        + batch.size() + " texts; a provider must answer one vector per input, in order");
            }
            for (int i = 0; i < batch.size(); i++) {
                onVector.accept(batch.get(i), vectors.get(i));
            }
        }
    }

    /** {@link #precomputeVectors(Collection, int)} with a batch size most provider APIs accept comfortably --
     *  and which the provider's own {@link JavAIEmbeddingProvider#maxBatchSize()} narrows further where it
     *  declares a smaller one. */
    public static void precomputeVectors(Collection<?> objects) {
        precomputeVectors(objects, EmbeddingBatchLimits.DEFAULT_MAX_BATCH_SIZE);
    }

    /** One {@code @Vectorize} field's cache slot, and the generation its text was read at, awaiting a
     *  batched embedding. */
    private record FieldRef(VectorCacheSlot slot, long generation) {
    }

    /** One object's concatenated-text slot, and the generation its text was assembled at. */
    private record PendingConcatenation(VectorCacheSlot slot, long generation) {
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
            if (isExternalVectorName(from.getClass(), fieldName)) {
                // An external vector must carry the content key it was supplied for, or the managed copy
                // holds a vector it will never serve -- externalVector() compares that key on every read,
                // so a vector transferred without one is indistinguishable from one that was superseded.
                // It also bypasses hydrateFieldVector's pristine-slot rule deliberately: nothing here can
                // recompute, so refusing would silently drop the vector at every merge (OMI-290).
                hydrateExternalVector(to, fieldName, sourceSlot.cachedValue(),
                        source.externalVectorKey(fieldName));
                continue;
            }
            hydrateFieldVector(to, fieldName, sourceSlot.cachedValue());
        }
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
     * The {@link #hydrateFieldVector} counterpart for the concatenated-text slot (OMI-191).
     *
     * <p>Worth more here than for a field vector. A loaded entity's {@code summaryVector()} is arithmetic
     * over field vectors that hydration already restored, so it costs nothing to recompute; a concatenated
     * text vector is a real embedding of assembled text, so <em>not</em> hydrating it would mean a model call
     * on every load of every participating entity -- precisely the waste OMI-187 existed to remove.
     *
     * <p>Same pristine-slot rule, and the same standing assumption behind it: hydrate only when nothing has
     * been computed and no JavAI-visible mutation has happened, so a real change always wins over a stored
     * value.
     *
     * <p>One gap worth naming, inherited rather than introduced: this object's text depends on its
     * descendants, which are separate entities with their own rows. If a descendant changed in some earlier
     * session and this entity was never re-saved, the stored text -- and so this vector -- is already stale
     * on disk, and hydration will faithfully serve that staleness. Recomputation would not have discovered
     * it either, since it reads the same graph.
     */
    public static void hydrateConcatenatedTextVector(Object self, EmbeddingVector vector) {
        if (vector == null || vector.isAbsent()) {
            return;
        }
        VectorCacheSlot slot = stateOf(self).concatenatedTextSlot();
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
                // This object's own vector at full weight, plus each @Summary child's summary at the decay
                // factor. An absent own-vector (no @Vectorize fields) doesn't make this object's summary
                // absent -- its @Summary children may still have content -- and an absent child contributes
                // nothing rather than being a dimension mismatch to report. VectorMath.weightedSum applies
                // both of those rules, so they are stated once there rather than re-derived here (OMI-218).
                List<VectorMath.WeightedVector> terms = new ArrayList<>();
                terms.add(new VectorMath.WeightedVector(vector(self, vectorizeFieldNames), 1.0));
                if (!summaryFieldNames.isBlank()) {
                    for (String fieldName : summaryFieldNames.split(",")) {
                        Object value = readField(self, fieldName);
                        if (value instanceof JavAIVectorizable child) {
                            terms.add(new VectorMath.WeightedVector(
                                    child.summaryVector(), DEFAULT_SUMMARY_DECAY));
                        }
                    }
                }
                EmbeddingVector recomputed;
                try {
                    recomputed = VectorMath.normalize(VectorMath.weightedSum(terms));
                } catch (IllegalArgumentException e) {
                    // Rethrown with the context VectorMath cannot have: which class, and which fields were
                    // in play. (The specific offending field name is no longer singled out -- the cost of
                    // having one implementation of the compatibility rule instead of three.)
                    throw new IllegalStateException("summaryVector() for " + self.getClass().getName()
                            + " cannot combine its own vector with its @Summary fields ["
                            + summaryFieldNames + "]: " + e.getMessage(), e);
                }
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
        // Every candidate's vector, in as few provider calls as the provider supports, before the sort rather
        // than inside it (OMI-266). The comparator below reads each match's vector(), so any candidate whose
        // fields have not been embedded yet was previously discovered one at a time, deep inside a sort --
        // the same one-round-trip-per-text shape as the persistence flows, in the one place where it is least
        // visible. Warming first changes no ranking: it computes exactly the vectors the comparator is about
        // to ask for, and skips every slot that is already accurate.
        warmSubgraph(matches);
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
     *
     * <p>Once the subgraph is locked and before {@code action} runs, every vector it is about to read is
     * computed in as few provider calls as the provider supports -- see {@link #warmSubgraph} (OMI-266).
     * That changes no result: it only fills, up front and in batches, the same caches {@code action}'s own
     * reads would otherwise fill one at a time.
     */
    public static void runWithSubgraphLockedForPersistence(Object root, Runnable action) {
        Set<Object> subgraph = reachableVectorizables(root);
        List<DirtyTrackingSupport> states = new ArrayList<>();
        for (Object node : subgraph) {
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
                warmSubgraph(subgraph);
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
     * {@link #warmSubgraph} across several roots at once -- everything reachable from any of {@code roots},
     * embedded in as few provider calls as the provider supports (OMI-266).
     *
     * <p>What a per-save warm cannot do. Each {@code save()} warms its own subgraph, so saving a hundred
     * entities in a loop still costs a hundred round trips however well each individual one batches; only a
     * caller who can see the whole batch at once can collapse them, which is what
     * {@code JavAIRepository.saveAll} exists to be. Texts are de-duplicated across the union, so a value
     * shared between two of the roots is embedded once between them.
     *
     * <p>Deliberately callable <em>before</em> a transaction is opened rather than inside one: these are
     * network round trips, and a bulk write has no reason to hold a database transaction open across them.
     * It is still safe -- nothing is locked here, so a field mutated between this call and its {@code save()}
     * simply leaves its slot dirty, and that save's own locked, accuracy-forced pass recomputes it.
     */
    public static void warmSubgraphsForPersistence(Collection<?> roots) {
        Set<Object> union = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Object root : roots) {
            union.addAll(reachableVectorizables(root));
        }
        warmSubgraph(union);
    }

    /**
     * Embeds everything a subsequent read is about to ask for, in as few provider calls as the provider
     * supports, before it asks for any of it (OMI-266).
     *
     * <p><b>Why this is worth doing at the point of a flush.</b> A flush reads one field at a time, so lazy
     * computation discovers one text at a time and every one of them is its own round trip: measured on the
     * Postgres backend, saving a container with twelve members cost thirteen texts in thirteen sequential
     * calls. Gathering first is the only way to hand a provider more than one, and
     * {@link #runWithSubgraphLockedForPersistence} is the one place that already knows the whole set -- it
     * computed it in order to lock it.
     *
     * <p><b>Round trips are not the only thing this shortens.</b> Called from there, these calls happen while
     * every object in the subgraph is locked, and on the Postgres and Neo4j backends inside an open database
     * transaction. So N sequential round trips hold the graph frozen against mutation, and hold a transaction
     * open, for N times as long as one batched call does. That is contention, not merely latency.
     *
     * <p><b>A failure here is not a failure of the operation being warmed.</b> This only fills caches that
     * the reads that follow would otherwise fill themselves, so if the provider is unreachable the right
     * outcome is the one those reads would have produced -- which is not the same outcome for every
     * configuration: {@link EmbeddingFailureMode#THROW} raises, {@link EmbeddingFailureMode#RETURN_NULL} does
     * not. Rather than reimplement that decision here and risk disagreeing with it, the exception is dropped
     * and the slots are left dirty for the ordinary path to resolve with whatever semantics are configured.
     * The cost of a genuinely broken provider is therefore one wasted batched call before the real failure
     * surfaces, and the observable behaviour of {@code save()} is unchanged under every failure mode.
     */
    private static void warmSubgraph(Collection<?> subgraph) {
        try {
            precomputeVectors(subgraph);
        } catch (RuntimeException e) {
            // Deliberately swallowed -- see this method's javadoc. Not a silent failure: every slot this
            // failed to fill is still dirty, so the read that follows attempts it again and reports it (or
            // not) exactly as the configured EmbeddingFailureMode says it should.
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
        // An unresolved association is not part of the subgraph being flushed: nothing has mutated it, so
        // there is nothing to lock, warm or write. Resolving one to find that out would be the load this
        // walk has no business issuing -- and, off a detached instance, cannot issue at all (OMI-271).
        if (node == null || !isResolved(node) || !visited.add(node)) {
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

    // ---- concatenated-text opt-in, read from annotations once per class (OMI-191) ----------------

    /**
     * Whether a class produces concatenated text of its own {@code @Vectorize} fields --
     * {@code @Summary(concatenate = true)} on the type.
     *
     * <p>Read reflectively rather than baked in at weave time, deliberately. The weaver bakes the
     * {@code @Vectorize} field list because deriving it needs {@code @VectorizeIgnore} precedence rules that
     * would be a second implementation to keep in sync; these opt-ins have no such subtlety, and reading
     * them here keeps the whole feature in one place. Cached per class, so it costs one map lookup per read
     * rather than an annotation scan.
     */
    private static boolean concatenatesOwnFields(Class<?> type) {
        return CONCATENATION_OPT_INS.computeIfAbsent(type, JavAIRuntime::readConcatenationOptIns).own();
    }

    /** The {@code @Summary(concatenate = true)} field names of a class, in declaration order. */
    private static List<String> concatenateFieldNames(Class<?> type) {
        return CONCATENATION_OPT_INS.computeIfAbsent(type, JavAIRuntime::readConcatenationOptIns).fields();
    }

    /**
     * Whether a class participates in concatenated text vectoring at all, by either opt-in.
     *
     * <p>Note the two are independent: a class may absorb its children's text without contributing its own
     * fields, which is exactly what you want for a container whose own fields are bookkeeping.
     *
     * <p>Public for {@code javai-persistence}, which needs it twice: to skip reading a stored text vector
     * for an entity that could not have one, and to reject {@code findNearestByConcatenatedTextVector} at
     * repository-creation time for an entity type that never participates.
     */
    public static boolean participatesInConcatenation(Class<?> type) {
        ConcatenationOptIns optIns = CONCATENATION_OPT_INS.computeIfAbsent(type, JavAIRuntime::readConcatenationOptIns);
        return optIns.own() || !optIns.fields().isEmpty();
    }

    private static ConcatenationOptIns readConcatenationOptIns(Class<?> type) {
        Summary typeLevel = type.getAnnotation(Summary.class);
        boolean own = typeLevel != null && typeLevel.concatenate();

        List<String> fields = new ArrayList<>();
        // Walk the hierarchy so an inherited field's opt-in is honoured, matching how field discovery
        // works elsewhere in this class.
        for (Class<?> current = type; current != null && current != Object.class; current = current.getSuperclass()) {
            for (Field field : current.getDeclaredFields()) {
                Summary summary = field.getAnnotation(Summary.class);
                if (summary != null && summary.concatenate() && !fields.contains(field.getName())) {
                    fields.add(field.getName());
                }
            }
        }
        return new ConcatenationOptIns(own, List.copyOf(fields));
    }

    private record ConcatenationOptIns(boolean own, List<String> fields) {
    }

    private static final Map<Class<?>, ConcatenationOptIns> CONCATENATION_OPT_INS = new ConcurrentHashMap<>();

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

    /**
     * Every <b>instance</b> field declared anywhere in {@code type}'s class hierarchy, not just on
     * {@code type} itself.
     *
     * <p>⚠️ {@code static} fields are excluded (OMI-290). Every caller here treats a field as a property of
     * one object: {@link #walkGraph} follows it as a graph edge, {@link #registerAllFieldDependencies} wires
     * a back-edge through it, {@link #collectReachableVectorizables} locks what it reaches for a flush. Class
     * state is none of those things -- a static holding a shared cache or a constant would put objects
     * nobody referenced into a {@code query()} result and into a persistence flush's lock set. The sibling
     * defect in {@code javai-persistence}'s own {@code EntityReflection.allFields} was the visible half of
     * this, failing outright on load; this half would merely have been quietly wrong.
     */
    private static List<Field> allFields(Class<?> type) {
        List<Field> fields = new ArrayList<>();
        for (Class<?> current = type; current != null && current != Object.class; current = current.getSuperclass()) {
            for (Field field : current.getDeclaredFields()) {
                if (!java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                    fields.add(field);
                }
            }
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
