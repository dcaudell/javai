package dev.xtrafe.javai.completion;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.Flow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link Cortex#completeTyped}: one caller-written repair, and a typed reason when there is still no value (OMI-640). */
class CortexCompleteTypedTest {

    record Verdict(boolean needsNarration) {
    }

    /** Answers each request with the next scripted text, recording what it was asked. */
    static final class ScriptedCortex implements Cortex {
        private final Deque<String> replies;
        final List<CompletionRequest> asked = new ArrayList<>();

        ScriptedCortex(String... replies) {
            this.replies = new ArrayDeque<>(List.of(replies));
        }

        @Override
        public CompletionResult complete(CompletionRequest request) {
            asked.add(request);
            return new CompletionResult(replies.removeFirst(), "scripted", "m", Instant.now());
        }

        @Override
        public void completeStreaming(CompletionRequest request, Flow.Subscriber<String> subscriber) {
            throw new UnsupportedOperationException();
        }

        @Override
        public String providerId() {
            return "scripted";
        }

        @Override
        public String modelId() {
            return "m";
        }

        @Override
        public int contextWindowTokens() {
            return 8000;
        }
    }

    private static final CompletionRequest ASK = CompletionRequest.builder().prompt("ask")
            .responseType(Verdict.class).build();

    /** The caller's repair: the failed text and why, appended to the original prompt. */
    private static final CompletionRepair REPAIR = (original, failed, why) -> CompletionRequest.builder()
            .prompt(original.prompt()).prompt(failed.text()).prompt(why.getMessage())
            .responseType(Verdict.class).build();

    @Test
    void aReadableFirstAnswerIsReturnedWithoutRepair() {
        ScriptedCortex cortex = new ScriptedCortex("{\"needsNarration\": true}");
        Verdict verdict = cortex.completeTyped(ASK, r -> r.as(Verdict.class), REPAIR);
        assertTrue(verdict.needsNarration());
        assertEquals(1, cortex.asked.size());
    }

    @Test
    void aSchemaViolationIsRepairedOnceWithTheCallersRequest() {
        ScriptedCortex cortex = new ScriptedCortex("{\"needsNarration\": \"maybe\"}", "{\"needsNarration\": false}");
        Verdict verdict = cortex.completeTyped(ASK, r -> r.as(Verdict.class), REPAIR);
        assertEquals(new Verdict(false), verdict);
        assertEquals(2, cortex.asked.size());
        String repairText = String.join("\n", cortex.asked.get(1).prompt());
        assertTrue(repairText.contains("\"maybe\""), "the repair carries the failed text: " + repairText);
        assertTrue(repairText.contains("needsNarration"), "the repair carries why: " + repairText);
    }

    @Test
    void noValueAtAllIsAFailureAndIsRepaired() {
        ScriptedCortex cortex = new ScriptedCortex("I would rather not say.", "{\"needsNarration\": true}");
        assertEquals(new Verdict(true), cortex.completeTyped(ASK, r -> r.as(Verdict.class), REPAIR));
        assertEquals(2, cortex.asked.size());
    }

    @Test
    void aSecondFailureIsTypedCarriesItsTextAndHasTheFirstAsItsCause() {
        ScriptedCortex cortex = new ScriptedCortex("{\"needsNarration\": 3}", "{\"needsNarration\": tru}");
        CompletionResponseException thrown = assertThrows(CompletionResponseException.class,
                () -> cortex.completeTyped(ASK, r -> r.as(Verdict.class), REPAIR));
        assertEquals(CompletionResponseException.Reason.MALFORMED, thrown.reason());
        assertEquals("{\"needsNarration\": tru}", thrown.text());
        CompletionResponseException first = (CompletionResponseException) thrown.getCause();
        assertEquals(CompletionResponseException.Reason.SCHEMA_VIOLATION, first.reason());
        assertEquals("{\"needsNarration\": 3}", first.text());
        assertEquals(2, cortex.asked.size(), "exactly one repair, never a loop");
    }

    @Test
    void withoutARepairTheFirstFailureIsThrownTyped() {
        ScriptedCortex cortex = new ScriptedCortex("nothing here");
        CompletionResponseException thrown = assertThrows(CompletionResponseException.class,
                () -> cortex.completeTyped(ASK, r -> r.as(Verdict.class), null));
        assertEquals(CompletionResponseException.Reason.NO_VALUE, thrown.reason());
        assertEquals("nothing here", thrown.text());
        assertEquals(1, cortex.asked.size());
    }

    @Test
    void listsReadThroughTheSameRepair() {
        ScriptedCortex cortex = new ScriptedCortex("[{\"needsNarration\": 1}]", "[{\"needsNarration\": true}]");
        List<Verdict> verdicts = cortex.completeTyped(ASK, r -> r.asList(Verdict.class), REPAIR);
        assertEquals(List.of(new Verdict(true)), verdicts);
    }

    @Test
    void typedFailuresFromAsStillAreCompletionExceptions() {
        CompletionResult result = new CompletionResult("{\"needsNarration\": \"x\"}", "p", "m", Instant.now());
        CompletionException thrown = assertThrows(CompletionException.class, () -> result.as(Verdict.class));
        assertSame(CompletionResponseException.class, thrown.getClass());
        assertEquals(CompletionResponseException.Reason.SCHEMA_VIOLATION,
                ((CompletionResponseException) thrown).reason());
    }
}
