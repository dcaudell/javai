package dev.xtrafe.javai.completion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.Test;

import dev.xtrafe.javai.model.LiteralTextEntry;
import dev.xtrafe.javai.model.PromptContext;
import dev.xtrafe.javai.model.PromptContextTrace;

/** OMI-619: a request's trace lays out its context exactly as {@link CompletionRequest#render(int)} sends it. */
class CompletionRequestTraceTest {

    private static final int WINDOW_TOKENS = 100;

    @Test
    void theRootBudgetIsTheOneRenderGivesTheContext() {
        CompletionRequest request = CompletionRequest.builder().prompt("Speak.").maxTokens(10)
                .context(PromptContext.builder().entry(new LiteralTextEntry("hello")).build()).build();

        // 100 tokens × 4 chars, less the prompt's 6 chars and 10 tokens of output.
        assertEquals(400 - 6 - 40, request.trace(WINDOW_TOKENS).budget());
    }

    @Test
    void theTracedTextIsWhatTheModelReceives() {
        PromptContext region = PromptContext.builder().targetPercentage(1.0)
                .entry(new LiteralTextEntry("100%% sure")).build();
        CompletionRequest request = CompletionRequest.builder().prompt("Speak.")
                .context(PromptContext.builder().entry(region).build()).build();

        PromptContextTrace trace = request.trace(WINDOW_TOKENS);

        assertEquals("Speak.\n\n" + trace.text(), request.render(WINDOW_TOKENS));
        assertEquals("100%% sure", trace.nested().get(0).text());
        assertSame(region, trace.nested().get(0).context());
    }

    @Test
    void theRootContextIsTheRequestsOwn() {
        PromptContext context = PromptContext.builder().entry(new LiteralTextEntry("x")).build();
        CompletionRequest request = CompletionRequest.builder().prompt("Speak.").context(context).build();

        assertSame(context, request.trace(WINDOW_TOKENS).context());
    }

    @Test
    void anExplicitMaxLengthWins() {
        CompletionRequest request = CompletionRequest.builder().prompt("Speak.")
                .context(PromptContext.builder().maxLength(3).entry(new LiteralTextEntry("x")).build()).build();

        assertEquals(3, request.trace(WINDOW_TOKENS).budget());
    }

    @Test
    void noContextNoTrace() {
        assertNull(CompletionRequest.builder().prompt("Speak.").build().trace(WINDOW_TOKENS));
    }
}
