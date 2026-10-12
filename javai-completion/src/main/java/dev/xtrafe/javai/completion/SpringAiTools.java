package dev.xtrafe.javai.completion;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;

import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * A request's {@link CompletionTool}s as Spring AI {@link ToolCallback}s, and the loop that runs them, for one
 * completion (OMI-643). {@link #run} calls the model, has Spring AI's {@link ToolCallingManager} execute the tool calls
 * it made -- each callback runs the caller's {@link CompletionToolExecutor}, records the call, and stops the loop at
 * the request's limit -- and calls again with the results, until the model answers.
 *
 * <p>⚠️ Single use: it counts the calls of one completion.
 */
final class SpringAiTools {

    private static final ToolCallingManager MANAGER = ToolCallingManager.builder().build();

    private final CompletionRequest request;
    private final CompletionToolExecutor executor;
    private final List<CompletionToolCall> calls = Collections.synchronizedList(new ArrayList<>());

    SpringAiTools(CompletionRequest request, CompletionToolExecutor executor) {
        this.request = request;
        this.executor = executor;
    }

    /**
     * Runs the loop: each response that calls tools has them executed (through the callbacks) and the conversation,
     * with their results, sent again, until a response calls none. Spring AI's chat models leave this to the caller.
     *
     * @param call one model call, with whatever retry the Cortex applies
     * @return the last response's text and the usage summed over every turn
     */
    CompletionResult run(Prompt prompt, Function<Prompt, ChatResponse> call, String providerId, String model) {
        ChatResponse response = call.apply(prompt);
        int input = 0;
        int output = 0;
        boolean usageReported = false;
        while (true) {
            Usage usage = response.getMetadata() == null ? null : response.getMetadata().getUsage();
            if (usage != null && usage.getPromptTokens() != null) {
                input += usage.getPromptTokens();
                output += usage.getCompletionTokens() == null ? 0 : usage.getCompletionTokens();
                usageReported = true;
            }
            if (!response.hasToolCalls()) {
                break;
            }
            // Checked only when the model wants another turn: an answer that arrives over the limit is still the
            // answer, already paid for.
            if (request.maxToolLoopTokens() != null && input + output > request.maxToolLoopTokens()) {
                throw CompletionToolLimitException.tokens(request.maxToolLoopTokens(), input + output, calls());
            }
            ToolExecutionResult executed = MANAGER.executeToolCalls(prompt, response);
            prompt = new Prompt(executed.conversationHistory(), prompt.getOptions());
            response = call.apply(prompt);
        }
        String text = response.getResult() == null || response.getResult().getOutput().getText() == null ? ""
                : response.getResult().getOutput().getText();
        return new CompletionResult(text, providerId, model, Instant.now(), usageReported ? input : null,
                usageReported ? output : null, calls());
    }

    List<ToolCallback> callbacks() {
        return request.tools().stream().map(this::callback).toList();
    }

    /** The calls made so far, in order. */
    List<CompletionToolCall> calls() {
        synchronized (calls) {
            return List.copyOf(calls);
        }
    }

    private ToolCallback callback(CompletionTool tool) {
        ToolDefinition definition = ToolDefinition.builder()
                .name(tool.name())
                .description(tool.description())
                .inputSchema(tool.inputSchema())
                .build();
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return definition;
            }

            @Override
            public String call(String arguments) {
                return SpringAiTools.this.call(tool.name(), arguments);
            }
        };
    }

    private String call(String name, String arguments) {
        if (calls().size() >= request.toolCallLimit()) {
            throw new CompletionToolLimitException(request.toolCallLimit(), calls());
        }
        long started = System.nanoTime();
        try {
            String result = executor.execute(new CompletionToolCall(name, arguments, null, null, 0));
            calls.add(new CompletionToolCall(name, arguments, result, null, (System.nanoTime() - started) / 1_000_000));
            return result;
        } catch (RuntimeException e) {
            calls.add(new CompletionToolCall(name, arguments, null, String.valueOf(e.getMessage()),
                    (System.nanoTime() - started) / 1_000_000));
            throw new ToolFailure(name, e);
        }
    }

    /**
     * What a tool-calling completion threw, with the library's own failures -- the limit, an executor's exception --
     * found wherever Spring AI wrapped them.
     */
    static RuntimeException unwrap(RuntimeException thrown) {
        for (Throwable t = thrown; t != null; t = t.getCause()) {
            if (t instanceof CompletionToolLimitException limit) {
                return limit;
            }
            if (t instanceof ToolFailure failure) {
                return new CompletionException("Tool '" + failure.tool + "' failed: " + failure.getCause().getMessage(),
                        failure.getCause());
            }
        }
        return thrown;
    }

    /** An executor's exception, carried out through Spring AI's loop. */
    private static final class ToolFailure extends RuntimeException {
        private final String tool;

        ToolFailure(String tool, RuntimeException cause) {
            super(cause);
            this.tool = tool;
        }
    }
}
