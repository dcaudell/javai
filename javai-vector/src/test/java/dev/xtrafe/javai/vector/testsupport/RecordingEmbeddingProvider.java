package dev.xtrafe.javai.vector.testsupport;

import dev.xtrafe.javai.vector.EmbeddingVector;
import dev.xtrafe.javai.vector.JavAIEmbeddingProvider;

import java.util.Optional;
import java.util.stream.Stream;

/**
 * A {@link JavAIEmbeddingProvider} decorator that records every call into an {@link EmbeddingLedger} and
 * then delegates. This is OMI-187's measuring instrument: the ticket's conclusion (3 embed calls per tag
 * where 1 is correct -- the tag's own slug, plus the owning {@code TagSet}'s slug re-embedded, plus an
 * empty string) was reached with an ad-hoc version of this class, and reading the code alone had produced
 * two different, both-wrong diagnoses before it.
 *
 * <p>Instrumenting at the provider is deliberate and is the whole point: it is the one place where JavAI's
 * intent ("recompute this vector") becomes an observable, countable, expensive event, and it sits
 * <em>outside</em> every cache, dirty flag, and consistency-mode branch that could otherwise be reasoned
 * about incorrectly. A test written against JavAI's internal state can agree with a buggy implementation;
 * a test written against the provider cannot.
 *
 * <p>Thread-safe, and it has to be: under {@code EVENTUAL_CONSISTENCY} and {@code COALESCED_CONSISTENCY}
 * a stale read dispatches its recomputation to a background virtual thread, so calls arrive off the test
 * thread. See {@link EmbeddingLedger#awaitQuiescence()} for the matching read-side discipline.
 *
 * <p>Wraps a {@link FakeEmbeddingProvider} by default, but takes any delegate -- wrapping a real provider
 * is exactly how you would check the same invariant against a live model.
 */
public final class RecordingEmbeddingProvider implements JavAIEmbeddingProvider {

    /**
     * Frames skipped when attributing a call to a site: this harness itself, plus the JDK's own
     * reflection/lambda plumbing. Everything else -- including all of {@code dev.xtrafe.javai} -- is kept,
     * because the JavAI frame nearest the provider is precisely what distinguishes the two {@code embed("")}
     * sites from each other.
     */
    private static final String[] IGNORED_FRAME_PREFIXES = {
            "dev.xtrafe.javai.vector.testsupport.",
            "java.lang.invoke.",
            "jdk.internal.reflect.",
            "java.lang.reflect.",
    };

    /** How many attributed frames to keep. Two is enough to separate the callers of a shared helper. */
    private static final int CALL_SITE_DEPTH = 3;

    private final JavAIEmbeddingProvider delegate;
    private final EmbeddingLedger ledger = new EmbeddingLedger();

    public RecordingEmbeddingProvider() {
        this(new FakeEmbeddingProvider());
    }

    public RecordingEmbeddingProvider(JavAIEmbeddingProvider delegate) {
        this.delegate = delegate;
    }

    @Override
    public EmbeddingVector embed(String text) {
        ledger.record(ledger.nextRoundTrip(), text, Thread.currentThread().getName(), captureCallSite());
        return delegate.embed(text);
    }

    /**
     * Records a batched call as <em>one</em> round trip carrying several texts, and passes it to the
     * delegate <em>as a batch</em>.
     *
     * <p>Both halves matter, and the absence of this override was a real hole in the instrument (OMI-266).
     * Without it this decorator inherited {@code embedAll}'s looping {@code default}, so wrapping a provider
     * that genuinely batches -- Ollama, TEI, OpenAI, vLLM -- silently turned its one request back into N
     * sequential ones. A measurement taken through this class could therefore never observe batching at all,
     * whether or not JavAI was doing any: the ledger reported N texts in N round trips either way. Any
     * before/after number for batching work is meaningless until this exists.
     */
    @Override
    public java.util.List<EmbeddingVector> embedAll(java.util.List<String> texts) {
        long roundTrip = ledger.nextRoundTrip();
        String threadName = Thread.currentThread().getName();
        String callSite = captureCallSite();
        for (String text : texts) {
            ledger.record(roundTrip, text, threadName, callSite);
        }
        return delegate.embedAll(texts);
    }

    /** The delegate's model -- a decorator that answered otherwise would silently disable any behaviour
     *  keyed on model identity (e.g. persistence deciding a stored vector is still valid). */
    @Override
    public String modelId() {
        return delegate.modelId();
    }

    public EmbeddingLedger ledger() {
        return ledger;
    }

    /** Convenience for the common "arrange, reset, act, assert" shape. */
    public void reset() {
        ledger.reset();
    }

    /**
     * A short {@code Class.method} trail identifying who asked for this embedding.
     *
     * <p>Diagnostic only -- deliberately never asserted on, since it describes JavAI's internal call
     * structure, which is the thing under test rather than a fixed contract. Its job is to make a failure
     * message actionable: "the empty string was embedded 12 times" is a symptom, while
     * "...from {@code CollectionVectorSupport.computeCentroid}" is a defect location.
     */
    private static String captureCallSite() {
        return StackWalker.getInstance().walk(frames -> frames
                .filter(frame -> !isIgnored(frame.getClassName()))
                .limit(CALL_SITE_DEPTH)
                .map(frame -> simpleName(frame.getClassName()) + "." + frame.getMethodName())
                .reduce((outer, inner) -> outer + " <- " + inner)
                .orElse("(unattributed)"));
    }

    private static boolean isIgnored(String className) {
        return Stream.of(IGNORED_FRAME_PREFIXES).anyMatch(className::startsWith);
    }

    private static String simpleName(String className) {
        return Optional.of(className.lastIndexOf('.'))
                .filter(index -> index >= 0)
                .map(index -> className.substring(index + 1))
                .orElse(className);
    }
}
