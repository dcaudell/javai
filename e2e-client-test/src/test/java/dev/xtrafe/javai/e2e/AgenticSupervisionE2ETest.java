package dev.xtrafe.javai.e2e;

import dev.xtrafe.javai.completion.Cortex;
import dev.xtrafe.javai.e2e.environment.JavAIEnvironment;
import dev.xtrafe.javai.e2e.supervision.SentimentAggregationSupervisor;
import dev.xtrafe.javai.e2e.supervision.SentimentAggregationSupervisor.Sentiment;
import dev.xtrafe.javai.e2e.supervision.SqlInjectionDetectedException;
import dev.xtrafe.javai.e2e.supervision.SqlInjectionGuardSupervisor;
import dev.xtrafe.javai.e2e.supervision.SupervisedTextOperations;
import dev.xtrafe.javai.supervision.JavAISupervisionRuntime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real end-to-end proof of Agentic Supervision against a real completion backend -- {@code
 * javai-supervision} was previously never wired into this project ("javai-supervision only, hermetic
 * tests" was the earlier pass's deliberate scope); this is that wiring's first real usage, mirroring how
 * {@code CompletionE2ETest} was Completion Fabric's first real usage.
 *
 * <p>Two demos, matching doc/spec/agentic-supervision.md's own worked examples almost exactly:
 * <ul>
 *   <li>{@link #asyncSentimentSupervisorAggregatesSentimentAcrossSeveralCalls()} -- a {@code
 *       SupervisionListener} registered asynchronously classifies each {@code submitFeedback} call's return
 *       value via a real completion, fire-and-forget, and the test proves the aggregation accumulates
 *       across calls rather than only ever reacting to one in isolation.
 *   <li>{@link #syncSecuritySupervisorBlocksSqlInjectionButAllowsBenignQueries()} -- a {@code
 *       SupervisionListener} registered synchronously vetoes a {@code searchAccounts} call at PRE, blocking
 *       on a real completion to decide, exactly the shape doc/spec/agentic-supervision.md's own
 *       {@code AgenticReviewListener} sketches.
 * </ul>
 *
 * <p>Kept to the minimum call count each demo needs to prove its point (3 feedback calls, 2 search calls --
 * five real completions total): each real call against {@code qwen3:8b} costs real wall-clock time, and
 * {@code enable_thinking} is explicitly disabled with a small token budget on every call, same as {@code
 * CompletionE2ETest} and {@code javai-completion}'s own {@code CortexOllamaRealContainerTest} already
 * establish, specifically to keep this fast and to stop Qwen3's default extended reasoning from consuming
 * the whole token budget before producing the one-word answer each prompt asks for.
 */
class AgenticSupervisionE2ETest {

    private static final Cortex CORTEX = JavAIEnvironment.cortex();

    private SentimentAggregationSupervisor sentimentSupervisor;
    private SqlInjectionGuardSupervisor securitySupervisor;

    @AfterEach
    void unregisterListeners() {
        if (sentimentSupervisor != null) {
            JavAISupervisionRuntime.unregisterAsyncListener(sentimentSupervisor);
        }
        if (securitySupervisor != null) {
            JavAISupervisionRuntime.unregisterSyncListener(securitySupervisor);
        }
    }

    @Test
    void asyncSentimentSupervisorAggregatesSentimentAcrossSeveralCalls() throws InterruptedException {
        sentimentSupervisor = new SentimentAggregationSupervisor(CORTEX, 3);
        JavAISupervisionRuntime.registerAsyncListener(sentimentSupervisor);

        SupervisedTextOperations operations = new SupervisedTextOperations();
        operations.submitFeedback("This completely exceeded my expectations -- I love it and will "
                + "recommend it to everyone!");
        operations.submitFeedback("This was the worst experience I've ever had; everything was broken "
                + "and support never responded.");
        operations.submitFeedback("Absolutely fantastic service, five stars, couldn't be happier.");

        assertTrue(sentimentSupervisor.awaitCompletion(Duration.ofSeconds(90)),
                "all three async dispatches must complete well within the timeout");

        int total = sentimentSupervisor.count(Sentiment.POSITIVE)
                + sentimentSupervisor.count(Sentiment.NEGATIVE)
                + sentimentSupervisor.count(Sentiment.NEUTRAL);
        assertEquals(3, total, "the aggregate must reflect all three calls, not just the most recent one");
        assertTrue(sentimentSupervisor.count(Sentiment.POSITIVE) >= 1,
                "at least one of the two clearly positive submissions must be classified positive");
        assertTrue(sentimentSupervisor.count(Sentiment.NEGATIVE) >= 1,
                "the clearly negative submission must be classified negative");
    }

    @Test
    void syncSecuritySupervisorBlocksSqlInjectionButAllowsBenignQueries() {
        securitySupervisor = new SqlInjectionGuardSupervisor(CORTEX);
        JavAISupervisionRuntime.registerSyncListener(securitySupervisor);

        SupervisedTextOperations operations = new SupervisedTextOperations();

        List<String> benignResult = operations.searchAccounts("ali");
        assertTrue(benignResult.contains("alice"), "a benign filter must reach the method body and match normally");

        SqlInjectionDetectedException thrown = assertThrows(SqlInjectionDetectedException.class,
                () -> operations.searchAccounts("'; DROP TABLE accounts; --"));
        assertTrue(thrown.getMessage().contains("DROP TABLE"),
                "the veto's own message should reference the blocked input for diagnosability");
    }
}
