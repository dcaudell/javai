package dev.xtrafe.javai.e2e.supervision;

import dev.xtrafe.javai.completion.CompletionRequest;
import dev.xtrafe.javai.completion.CompletionResult;
import dev.xtrafe.javai.completion.Cortex;
import dev.xtrafe.javai.supervision.AsyncSupervisionListener;
import dev.xtrafe.javai.supervision.SupervisionEvent;

import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * An {@code AgenticListener} in the sense doc/spec/agentic-supervision.md's worked example describes: a
 * fire-and-forget POST observer that grounds its reaction in a real completion. Registered once per test
 * and reused across every {@link SupervisedTextOperations#submitFeedback} call, aggregating a running count
 * of classifications rather than reacting to any single call in isolation -- the point being demonstrated
 * is that async dispatch accumulates *across* several calls, not just that one call can be classified.
 *
 * <p>The Cortex call happens on the async tier's own virtual thread (see {@code JavAISupervisionRuntime}),
 * so it never holds up {@code submitFeedback}'s return to its caller -- the test has to {@link
 * #awaitCompletion} explicitly rather than assert immediately after the calls that triggered it.
 */
public class SentimentAggregationSupervisor implements AsyncSupervisionListener {

    public enum Sentiment {
        POSITIVE, NEGATIVE, NEUTRAL
    }

    private final Cortex cortex;
    private final CountDownLatch completed;
    private final Map<Sentiment, AtomicInteger> counts = new EnumMap<>(Sentiment.class);

    public SentimentAggregationSupervisor(Cortex cortex, int expectedCalls) {
        this.cortex = cortex;
        this.completed = new CountDownLatch(expectedCalls);
        for (Sentiment sentiment : Sentiment.values()) {
            counts.put(sentiment, new AtomicInteger());
        }
    }

    @Override
    public void onPost(SupervisionEvent event) {
        try {
            String feedbackText = (String) event.returnValue();
            CompletionResult result = cortex.complete(CompletionRequest.builder()
                    .prompt("Classify the sentiment of the following feedback. Respond with exactly one "
                            + "word: positive, negative, or neutral.\n\nFeedback: " + feedbackText)
                    .maxTokens(10)
                    .providerOption("enable_thinking", false)
                    .build());
            counts.get(classify(result.text())).incrementAndGet();
        } finally {
            completed.countDown();
        }
    }

    private static Sentiment classify(String responseText) {
        String normalized = responseText.toLowerCase();
        if (normalized.contains("positive")) {
            return Sentiment.POSITIVE;
        }
        if (normalized.contains("negative")) {
            return Sentiment.NEGATIVE;
        }
        return Sentiment.NEUTRAL;
    }

    public int count(Sentiment sentiment) {
        return counts.get(sentiment).get();
    }

    public boolean awaitCompletion(Duration timeout) throws InterruptedException {
        return completed.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    @Override
    public Class<?> supportedClass() {
        return SupervisedTextOperations.class;
    }
}
