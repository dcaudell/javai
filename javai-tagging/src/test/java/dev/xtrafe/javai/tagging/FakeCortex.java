package dev.xtrafe.javai.tagging;

import dev.xtrafe.javai.completion.CompletionRequest;
import dev.xtrafe.javai.completion.CompletionResult;
import dev.xtrafe.javai.completion.Cortex;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.Flow;
import java.util.function.Consumer;

/**
 * Hermetic stand-in for classification tests -- matches this project's established "fake the LLM response
 * itself, not the backend it's persisted through" pattern (see {@code CortexOllamaTest}, javai-completion):
 * {@link JavAITagging#classify}'s own diffing/marshalling logic is what's under test here, not whether a
 * real model understands the task, which is a separate, lower-value concern for this specific area (unlike
 * vector search, an LLM's classification judgment can't be meaningfully faked either way -- what this
 * fixture controls is only the *shape* of the response, letting the test drive the diff logic precisely).
 * Responses are queued in order, one per {@link #complete} call; the last queued response repeats once the
 * queue is empty, so a test doesn't need to enqueue more than it actually varies.
 */
final class FakeCortex implements Cortex {

    private final Deque<String> responses = new ArrayDeque<>();
    private String lastResponse = "[]";

    void willRespond(String json) {
        responses.addLast(json);
    }

    void reset() {
        responses.clear();
        lastResponse = "[]";
    }

    @Override
    public CompletionResult complete(CompletionRequest request) {
        String text = responses.isEmpty() ? lastResponse : responses.removeFirst();
        lastResponse = text;
        return new CompletionResult(text, providerId(), modelId(), Instant.now());
    }

    @Override
    public void completeStreaming(CompletionRequest request, Flow.Subscriber<String> subscriber) {
        throw new UnsupportedOperationException("not needed for classification tests");
    }

    @Override
    public void completeStreaming(CompletionRequest request, Consumer<String> onChunk) {
        throw new UnsupportedOperationException("not needed for classification tests");
    }

    @Override
    public String providerId() {
        return "fake";
    }

    @Override
    public String modelId() {
        return "fake-test-model";
    }

    @Override
    public int contextWindowTokens() {
        return 8192;
    }
}
