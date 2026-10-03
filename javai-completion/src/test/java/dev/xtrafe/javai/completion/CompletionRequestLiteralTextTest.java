package dev.xtrafe.javai.completion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

import com.github.jknack.handlebars.HandlebarsException;

import dev.xtrafe.javai.model.LiteralTextEntry;
import dev.xtrafe.javai.model.PromptContext;

/**
 * OMI-604: text that is data -- a chat message, a memory -- reaches the model verbatim, even when it contains the
 * template delimiter. Before the fix every case below either threw or was substituted.
 */
class CompletionRequestLiteralTextTest {

    private static CompletionRequest withContext(PromptContext context) {
        return CompletionRequest.builder()
                .prompt("Speak as %%persona%%.")
                .promptParam("persona", "Eliza")
                .context(context)
                .build();
    }

    @ParameterizedTest
    @ValueSource(strings = {"lone %% here", "100%% sure", "trailing %%", "%%%% four", "plain %%persona%% here",
            "\\%%persona%% escaped", "%%%%raw%%%%x%%%%/raw%%%%", "a % b"})
    void literalTextRendersVerbatim(String userText) {
        String rendered = withContext(PromptContext.builder().entry(new LiteralTextEntry(userText)).build())
                .render();

        assertEquals("Speak as Eliza.\n\n" + userText, rendered);
    }

    @Test
    void theDefectStillExistsForTemplateText() {
        // The reproduction, kept: plain context text IS a template, so a stray delimiter in it still fails. That
        // is why data must arrive as LiteralTextEntry.
        assertThrows(HandlebarsException.class, () -> withContext(PromptContext.of("lone %% here")).render());
        assertEquals("Speak as Eliza.\n\nplain Eliza here",
                withContext(PromptContext.of("plain %%persona%% here")).render());
    }

    @Test
    void templateAndLiteralEntriesMixInOneContext() {
        PromptContext context = PromptContext.builder()
                .entry(PromptContext.of("%%persona%%'s instructions"))
                .entry(new LiteralTextEntry("User: 50%% of %%persona%%?"))
                .build();

        assertEquals("Speak as Eliza.\n\nEliza's instructions\n\nUser: 50%% of %%persona%%?",
                withContext(context).render());
    }

    @Test
    void aBudgetCountsLiteralTextAtItsVisibleLength() {
        String text = "100%% sure";
        PromptContext exact = PromptContext.builder().maxLength(text.length())
                .entry(new LiteralTextEntry(text)).build();
        PromptContext oneShort = PromptContext.builder().maxLength(text.length() - 1)
                .entry(new LiteralTextEntry(text)).build();

        assertEquals("Speak as Eliza.\n\n" + text, withContext(exact).render());
        assertEquals("Speak as Eliza.\n\n", withContext(oneShort).render());
    }

    @Test
    void aNullLiteralIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> new LiteralTextEntry(null));
    }
}
