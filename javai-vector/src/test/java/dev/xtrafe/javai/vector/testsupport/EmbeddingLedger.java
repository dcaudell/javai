package dev.xtrafe.javai.vector.testsupport;

import dev.xtrafe.javai.vector.JavAIEmbeddingProvider;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The record of every {@code embed()} call a {@link RecordingEmbeddingProvider} saw, plus the assertion
 * that turns that record into a pass/fail answer to OMI-187's actual question: <em>is JavAI making any
 * wasted or erroneous embedding calls?</em>
 *
 * <h2>Why a ledger rather than a call count</h2>
 *
 * A bare count can only fail one way ("more calls than I expected") and gives you nothing to act on when
 * it does. The defect OMI-187 found has three genuinely different failure modes that a count conflates,
 * so {@link #assertEmbeddedExactlyOnce} checks them separately and names them separately:
 *
 * <ul>
 *   <li><b>REDUNDANT</b> -- a text that <em>is</em> a real {@code @Vectorize} field value, embedded more
 *       than once. Something recomputed a vector it already had. (OMI-187: the owning {@code TagSet}'s own
 *       slug, re-embedded once per child {@code save()}.)</li>
 *   <li><b>PHANTOM</b> -- a text embedded that is not the value of any {@code @Vectorize} field in the
 *       fixture at all. Something called the provider for a reason unrelated to embedding real field
 *       content. (OMI-187: the empty string, embedded once per child {@code save()}.)</li>
 *   <li><b>OMITTED</b> -- a real {@code @Vectorize} field value that was never embedded. Included so a
 *       "fix" cannot pass this assertion by simply doing less work than correctness requires; without it,
 *       deleting the embedding call entirely would look like success.</li>
 * </ul>
 *
 * <h2>Why assertions key on text, not on call-site attribution</h2>
 *
 * {@link JavAIEmbeddingProvider#embed} receives only a string -- the provider cannot see which object or
 * field it is being called for. Rather than reconstruct that from a stack walk (fragile, and it would make
 * the assertion depend on JavAI's internal call structure -- exactly the structure under test), the
 * fixtures give every {@code @Vectorize} field a <b>globally unique value</b>. Text then identifies
 * (object, field) unambiguously and a frequency map over texts is a complete, robust answer.
 *
 * <p>{@link Call#callSite()} is captured anyway, but it is <em>diagnostic only</em> -- never asserted on.
 * It exists because two structurally different bugs can produce the identical text: JavAI has two separate
 * {@code embed("")} sites ({@code JavAIRuntime.vector} on a class with no {@code @Vectorize} fields, and
 * {@code CollectionVectorSupport.computeCentroid} on a collection with no vectorizable elements), and a
 * text-frequency map alone cannot tell you which one you are looking at.
 *
 * @see RecordingEmbeddingProvider
 */
public final class EmbeddingLedger {

    /** How many calls {@link #report()} prints in full before truncating -- a 1,400-tag seed would otherwise bury the diagnosis. */
    private static final int MAX_REPORTED_CALLS = 60;

    /**
     * One observed embedding of one text. {@code sequence} is 1-based and assigned in the order calls
     * arrived at the provider, which under the async consistency modes is not the order the reads that
     * caused them were issued -- hence {@code threadName}, which is what makes a background recomputation
     * distinguishable from a blocking one after the fact.
     *
     * <p>{@code roundTrip} is which <em>provider invocation</em> carried this text: an {@code embed} call
     * is a round trip of its own, while every text in one {@code embedAll} call shares one. Texts and round
     * trips were the same number until batching existed, and OMI-266 is entirely about the difference --
     * see {@link #roundTrips()}.
     */
    public record Call(long sequence, long roundTrip, String text, String threadName, String callSite) {
    }

    private final List<Call> calls = Collections.synchronizedList(new ArrayList<>());
    private final AtomicLong sequence = new AtomicLong();
    private final AtomicLong roundTrips = new AtomicLong();

    /** Claims an id for one provider invocation, to be shared by every text that invocation carries. */
    long nextRoundTrip() {
        return roundTrips.incrementAndGet();
    }

    void record(long roundTrip, String text, String threadName, String callSite) {
        calls.add(new Call(sequence.incrementAndGet(), roundTrip, text, threadName, callSite));
    }

    /** Every call so far, in arrival order. Snapshot -- safe to iterate while embedding continues. */
    public List<Call> calls() {
        synchronized (calls) {
            return List.copyOf(calls);
        }
    }

    /** How many <b>texts</b> were embedded -- the OMI-187 measure, and what every existing assertion here
     *  counts. Unaffected by batching: a batch of ten texts is ten. */
    public int totalCalls() {
        return calls.size();
    }

    /**
     * How many <b>provider invocations</b> those texts arrived in -- the OMI-266 measure.
     *
     * <p>The distinction is the whole of that ticket. Waste (OMI-187) is texts embedded that needn't have
     * been, and batching cannot reduce it. Latency is round trips, and batching is the only thing that
     * reduces it: ten warranted texts cost ten sequential HTTP calls or one, and {@link #totalCalls()}
     * reports ten either way.
     *
     * <p>Counted from ids assigned at the provider, not inferred from timing, so it is exact under the
     * background consistency modes too.
     */
    public int roundTrips() {
        Set<Long> distinct = new LinkedHashSet<>();
        for (Call call : calls()) {
            distinct.add(call.roundTrip());
        }
        return distinct.size();
    }

    /** Texts per round trip, in the order the round trips began -- what shows whether batching actually
     *  grouped anything or merely renamed one-text calls. */
    public Map<Long, Integer> batchSizes() {
        Map<Long, Integer> sizes = new LinkedHashMap<>();
        for (Call call : calls()) {
            sizes.merge(call.roundTrip(), 1, Integer::sum);
        }
        return sizes;
    }

    /** How many times {@code text} was embedded. */
    public int countOf(String text) {
        int count = 0;
        for (Call call : calls()) {
            if (call.text().equals(text)) {
                count++;
            }
        }
        return count;
    }

    /** Embed count per distinct text, ordered by first occurrence. */
    public Map<String, Integer> frequency() {
        Map<String, Integer> frequency = new LinkedHashMap<>();
        for (Call call : calls()) {
            frequency.merge(call.text(), 1, Integer::sum);
        }
        return frequency;
    }

    /** Forget every recorded call. Call between phases of a test to scope an assertion to one operation. */
    public void reset() {
        synchronized (calls) {
            calls.clear();
        }
        sequence.set(0);
        roundTrips.set(0);
    }

    /**
     * Blocks until no new call has been recorded for {@code idleWindow}, or {@code timeout} elapses.
     *
     * <p>Required under {@code EVENTUAL_CONSISTENCY} and {@code COALESCED_CONSISTENCY}, where a stale read
     * returns the cached value immediately and dispatches the real embedding to a background virtual
     * thread: asserting the moment the read returns would count calls that have not happened yet and read
     * as a false pass. Under {@code IMMEDIATE_CONSISTENCY} every embed is already complete when the read
     * returns, so this is a no-op that costs one idle window.
     *
     * <p>Deliberately quiescence-based rather than "wait for N calls" -- the count is the thing under test,
     * so waiting for an expected count would make the harness assume its own answer.
     */
    public void awaitQuiescence(Duration idleWindow, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        int lastSeen = -1;
        long stableSince = System.nanoTime();
        while (System.nanoTime() < deadline) {
            int current = totalCalls();
            if (current != lastSeen) {
                lastSeen = current;
                stableSince = System.nanoTime();
            } else if (System.nanoTime() - stableSince >= idleWindow.toNanos()) {
                return;
            }
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /** {@link #awaitQuiescence(Duration, Duration)} with defaults sized for a hermetic, in-process fake provider. */
    public void awaitQuiescence() {
        awaitQuiescence(Duration.ofMillis(150), Duration.ofSeconds(10));
    }

    public void assertEmbeddedExactlyOnce(String... expectedFieldValues) {
        assertEmbeddedExactlyOnce(Arrays.asList(expectedFieldValues));
    }

    /**
     * Asserts the full OMI-187 invariant: every value in {@code expectedFieldValues} was embedded exactly
     * once, and nothing else was embedded at all.
     *
     * @param expectedFieldValues every {@code @Vectorize} field value that should have been embedded during
     *                            the recorded window -- each globally unique, so that text identifies
     *                            (object, field) unambiguously
     * @throws AssertionError naming each violation by class (REDUNDANT / PHANTOM / OMITTED), with the
     *                        captured call sites and a call log
     */
    public void assertEmbeddedExactlyOnce(Collection<String> expectedFieldValues) {
        Set<String> expected = new LinkedHashSet<>(expectedFieldValues);
        Map<String, Integer> actual = frequency();

        Map<String, Integer> redundant = new LinkedHashMap<>();
        Map<String, Integer> phantom = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> entry : actual.entrySet()) {
            if (!expected.contains(entry.getKey())) {
                phantom.put(entry.getKey(), entry.getValue());
            } else if (entry.getValue() > 1) {
                redundant.put(entry.getKey(), entry.getValue());
            }
        }
        List<String> omitted = new ArrayList<>();
        for (String value : expected) {
            if (!actual.containsKey(value)) {
                omitted.add(value);
            }
        }
        if (redundant.isEmpty() && phantom.isEmpty() && omitted.isEmpty()) {
            return;
        }

        int wasted = 0;
        for (Integer count : redundant.values()) {
            wasted += count - 1;
        }
        for (Integer count : phantom.values()) {
            wasted += count;
        }

        StringBuilder message = new StringBuilder();
        message.append("Embedding-call invariant violated (OMI-187): expected exactly one embed() per ")
                .append("distinct @Vectorize field value, and no other text embedded at all.\n\n")
                .append("  ").append(totalCalls()).append(" call(s) made, ").append(expected.size())
                .append(" correct, ").append(wasted).append(" wasted.\n\n");

        message.append("REDUNDANT -- a real @Vectorize field value, embedded more than once:\n");
        appendTextSection(message, redundant, true);

        message.append("PHANTOM -- embedded, but not the value of any @Vectorize field:\n");
        appendTextSection(message, phantom, false);

        message.append("OMITTED -- an expected @Vectorize field value that was never embedded:\n");
        if (omitted.isEmpty()) {
            message.append("  (none)\n");
        } else {
            for (String value : omitted) {
                message.append("  ").append(render(value)).append('\n');
            }
        }

        message.append('\n').append(report());
        throw new AssertionError(message.toString());
    }

    private void appendTextSection(StringBuilder message, Map<String, Integer> texts, boolean subtractOne) {
        if (texts.isEmpty()) {
            message.append("  (none)\n\n");
            return;
        }
        for (Map.Entry<String, Integer> entry : texts.entrySet()) {
            int count = entry.getValue();
            message.append("  ").append(count).append("x  ").append(render(entry.getKey()))
                    .append("   (").append(subtractOne ? count - 1 : count).append(" wasted)\n");
            for (Call call : firstAndLast(entry.getKey())) {
                message.append("        #").append(call.sequence()).append("  [").append(call.threadName())
                        .append("]  ").append(call.callSite()).append('\n');
            }
        }
        message.append('\n');
    }

    /** The first and last call for a text -- enough to tell "computed once then re-computed later" from a tight loop. */
    private List<Call> firstAndLast(String text) {
        List<Call> matching = new ArrayList<>();
        for (Call call : calls()) {
            if (call.text().equals(text)) {
                matching.add(call);
            }
        }
        if (matching.size() <= 2) {
            return matching;
        }
        return List.of(matching.get(0), matching.get(matching.size() - 1));
    }

    /** Human-readable dump of the whole ledger -- appended to every failure, and useful standalone while investigating. */
    public String report() {
        List<Call> snapshot = calls();
        StringBuilder out = new StringBuilder();
        out.append("Full call log (").append(snapshot.size()).append(" text(s) in ").append(roundTrips())
                .append(" round trip(s), ").append(frequency().size()).append(" distinct text(s)):\n");
        int shown = Math.min(snapshot.size(), MAX_REPORTED_CALLS);
        for (int i = 0; i < shown; i++) {
            Call call = snapshot.get(i);
            out.append("  #").append(call.sequence()).append("  trip ").append(call.roundTrip())
                    .append("  [").append(call.threadName()).append("]  ")
                    .append(render(call.text())).append("  <-  ").append(call.callSite()).append('\n');
        }
        if (snapshot.size() > shown) {
            out.append("  ... ").append(snapshot.size() - shown).append(" more\n");
        }
        return out.toString();
    }

    /** Renders a text for a failure message -- the empty string is the whole point of the PHANTOM class, so it must be visible. */
    private static String render(String text) {
        if (text.isEmpty()) {
            return "\"\" (empty string)";
        }
        String escaped = text.replace("\n", "\\n");
        if (escaped.length() > 60) {
            escaped = escaped.substring(0, 57) + "...";
        }
        return '"' + escaped + '"';
    }
}
