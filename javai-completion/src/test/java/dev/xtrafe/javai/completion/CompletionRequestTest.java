package dev.xtrafe.javai.completion;

import dev.xtrafe.javai.model.ContextableObject;
import dev.xtrafe.javai.model.PromptContext;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers {@link CompletionRequest#render()}'s full pipeline (join prompts, append context, run the whole
 * thing through Handlebars against {@code promptParams}) and, in particular, the collision scenarios from
 * combining real JSON (via {@link PromptContext#defaultMarshall(Object)}) with Handlebars templating in the
 * same render -- see {@link CompletionRequest}'s own javadoc for the {@code EscapingStrategy.NOOP}/
 * {@code %%...%%}-delimiter rationale these tests lock in.
 */
class CompletionRequestTest {

    /** A record with one {@code @PromptContext}-annotated field, mirroring the allowlist pattern used
     *  throughout {@code PromptContextTest}. */
    record Note(@dev.xtrafe.javai.annotations.PromptContext String text) {
    }

    /** A nested shape -- marshalling this produces JSON with adjacent closing braces purely from nesting
     *  (e.g. {@code {"outer":{"inner":"x"}}}), the "nested JSON" collision scenario. */
    record Outer(@dev.xtrafe.javai.annotations.PromptContext Inner inner) {
    }

    record Inner(@dev.xtrafe.javai.annotations.PromptContext String value) {
    }

    @Test
    void renderWithASinglePromptStringNoContextNoParamsIsUnchanged() {
        CompletionRequest request = CompletionRequest.builder().prompt("Say hi").build();

        assertEquals("Say hi", request.render());
    }

    @Test
    void multiplePromptCallsConcatenateInOrderWithDoubleNewline() {
        CompletionRequest request = CompletionRequest.builder()
                .prompt("first")
                .prompt("second")
                .prompt("third")
                .build();

        assertEquals("first\n\nsecond\n\nthird", request.render());
    }

    @Test
    void promptListOverloadMergesWithAnyAlreadyAddedSingleStrings() {
        CompletionRequest request = CompletionRequest.builder()
                .prompt("first")
                .prompt(List.of("second", "third"))
                .build();

        assertEquals("first\n\nsecond\n\nthird", request.render());
    }

    @Test
    void renderWithContextAndNoParamsMatchesOldPromptTextBehaviorExactly() {
        CompletionRequest request = CompletionRequest.builder()
                .prompt("Summarize this:")
                .context(PromptContext.of("the informing material"))
                .build();

        assertEquals("Summarize this:\n\nthe informing material", request.render());
    }

    @Test
    void placeholderInAPlainPromptStringIsResolvedFromPromptParams() {
        CompletionRequest request = CompletionRequest.builder()
                .prompt("Hello %%name%%, welcome!")
                .promptParam("name", "Ada")
                .build();

        assertEquals("Hello Ada, welcome!", request.render());
    }

    @Test
    void placeholderEmbeddedInsideContextMarshalledJsonIsResolvedFromPromptParams() {
        CompletionRequest request = CompletionRequest.builder()
                .prompt("Greeting:")
                .context(PromptContext.builder()
                        .entry(new ContextableObject<>(new Note("Say hello to %%name%% please")))
                        .build())
                .promptParam("name", "Ada")
                .build();

        assertEquals("Greeting:\n\n{\"text\":\"Say hello to Ada please\"}", request.render());
    }

    @Test
    void collisionEscapingJsonSpecialCharactersSurviveVerbatimThroughASubstitution() {
        CompletionRequest request = CompletionRequest.builder()
                .prompt("%%greeting%%")
                .context(PromptContext.builder()
                        .entry(new ContextableObject<>(new Note("O'Brien & \"Sons\" <co>")))
                        .build())
                .promptParam("greeting", "Hi")
                .build();

        String rendered = request.render();

        // Gson's own default HTML-safe mode already unicode-escapes &/</>/' inside the JSON string value
        // (a pre-existing, separate concern from this test) -- what this test actually locks in is that
        // Handlebars adds no *further*, additional HTML-entity escaping (&amp;/&quot;/&lt;/&gt;) on top of
        // whatever Gson already produced, which is exactly what EscapingStrategy.NOOP guards against.
        String expectedJson = new com.google.gson.Gson().toJson(new Note("O'Brien & \"Sons\" <co>"));
        assertEquals("Hi\n\n" + expectedJson, rendered);
        assertFalse(rendered.contains("&amp;"), "Handlebars must not add HTML-entity escaping on top of Gson's own");
        assertFalse(rendered.contains("&quot;"), "Handlebars must not add HTML-entity escaping on top of Gson's own");
        assertFalse(rendered.contains("&#x27;"), "Handlebars must not add HTML-entity escaping on top of Gson's own");
    }

    @Test
    void collisionNestedJsonAdjacentClosingBracesRenderUnchanged() {
        CompletionRequest request = CompletionRequest.builder()
                .prompt("Data:")
                .context(PromptContext.builder()
                        .entry(new ContextableObject<>(new Outer(new Inner("x"))))
                        .build())
                .build();

        assertEquals("Data:\n\n{\"inner\":{\"value\":\"x\"}}", request.render());
    }

    @Test
    void collisionLiteralMustacheHandlebarsBracesAreCompletelyInert() {
        CompletionRequest request = CompletionRequest.builder()
                .prompt("This snippet shows the syntax: {{example}} and {{#each items}}...{{/each}}")
                .promptParam("example", "should not be substituted")
                .build();

        assertEquals(
                "This snippet shows the syntax: {{example}} and {{#each items}}...{{/each}}",
                request.render());
    }

    @Test
    void collisionUnresolvedPercentPlaceholderSilentlyRendersAsEmptyString() {
        CompletionRequest request = CompletionRequest.builder()
                .prompt("Before[%%unknownKey%%]After")
                .build();

        assertEquals("Before[]After", request.render());
    }

    @Test
    void collisionSubstitutedValueContainingLiteralTokensIsNotReprocessed() {
        CompletionRequest request = CompletionRequest.builder()
                .prompt("Value: %%tricky%%")
                .promptParam("tricky", "contains %%name%% and {{name}} literally")
                .build();

        assertEquals("Value: contains %%name%% and {{name}} literally", request.render());
    }

    @Test
    void collisionMalformedUnterminatedDelimiterThrows() {
        CompletionRequest request = CompletionRequest.builder()
                .prompt("broken %% unterminated")
                .build();

        // HandlebarsException is itself unchecked, thrown directly by compileInline (not the checked
        // IOException render() wraps) -- it propagates as-is rather than getting wrapped further.
        assertThrows(com.github.jknack.handlebars.HandlebarsException.class, request::render);
    }

    @Test
    void promptParamAndPromptParamsAccumulateLikeProviderOptions() {
        CompletionRequest request = CompletionRequest.builder()
                .prompt("%%a%%-%%b%%-%%c%%")
                .promptParam("a", "1")
                .promptParams(Map.of("b", "2", "c", "3"))
                .build();

        assertEquals("1-2-3", request.render());
    }

    @Test
    void builderWithNoPromptAndNoContextDoesNotThrowAndRendersEmpty() {
        CompletionRequest request = CompletionRequest.builder().build();

        assertEquals("", request.render());
    }
}
