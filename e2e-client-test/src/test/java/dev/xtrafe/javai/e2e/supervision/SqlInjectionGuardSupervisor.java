package dev.xtrafe.javai.e2e.supervision;

import dev.xtrafe.javai.completion.CompletionRequest;
import dev.xtrafe.javai.completion.CompletionResult;
import dev.xtrafe.javai.completion.Cortex;
import dev.xtrafe.javai.supervision.SupervisionEvent;
import dev.xtrafe.javai.supervision.SyncSupervisionListener;

/**
 * A blocking, LLM-grounded veto at PRE -- the synchronous counterpart to {@link
 * SentimentAggregationSupervisor}, and closer to doc/spec/agentic-supervision.md's own
 * {@code AgenticReviewListener} worked example than a hand-rolled regex/pattern check would be: the
 * decision is delegated to a real completion rather than a fixed rule set, at the cost of
 * {@link SupervisedTextOperations#searchAccounts} blocking on a real model round-trip for every call --
 * exactly the trade-off doc/spec/agentic-supervision.md's "why opt-in, and why sparse" section describes.
 */
public class SqlInjectionGuardSupervisor implements SyncSupervisionListener {

    private final Cortex cortex;

    public SqlInjectionGuardSupervisor(Cortex cortex) {
        this.cortex = cortex;
    }

    @Override
    public void onPre(SupervisionEvent event) {
        String filter = (String) event.arguments()[0];
        CompletionResult result = cortex.complete(CompletionRequest.builder()
                .prompt("Does the following user-supplied search filter contain a SQL injection attempt? "
                        + "Respond with exactly one word: yes or no.\n\nFilter: " + filter)
                .maxTokens(5)
                .providerOption("enable_thinking", false)
                .build());
        if (result.text().strip().toLowerCase().startsWith("yes")) {
            throw new SqlInjectionDetectedException("Blocked by SQL injection guard: " + filter);
        }
    }

    @Override
    public Class<?> supportedClass() {
        return SupervisedTextOperations.class;
    }
}
