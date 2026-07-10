package dev.xtrafe.javai.completion;

import com.github.jknack.handlebars.EscapingStrategy;
import com.github.jknack.handlebars.Handlebars;
import dev.xtrafe.javai.model.PromptContext;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * An instruction plus optional informing material and generation parameters -- prompt-and-context
 * oriented, not conversation-oriented (see {@code doc/spec/completion-fabric.md}'s design goals): no
 * message history, no system/user/assistant turns to manage.
 *
 * <p>{@code providerOptions} is the open-ended escape hatch for tuning parameters specific to one provider
 * or model -- Anthropic's extended-thinking token budget, Qwen/Ollama's {@code enable_thinking} toggle,
 * OpenAI's {@code reasoning_effort}, Groq's service tier, and so on. Every {@link Cortex} implementation
 * documents which keys it actually reads; an unrecognized key is silently ignored, not an error (this bag
 * is inherently plural-provider -- a caller running the same request against several Cortices at once
 * shouldn't have to strip out keys the other providers don't understand).
 *
 * <p><b>{@link #prompt()} is a {@code List<String>}, not a single string</b> -- {@link Builder#prompt(String)}
 * appends one at a time (source-compatible with a single call, the common case), {@link Builder#prompt(List)}
 * merges a whole list at once. {@link #render()} joins them (in order, {@code "\n\n"}-separated) before
 * anything else happens.
 *
 * <p><b>{@link #render()} is the one place this whole pipeline comes together</b>, and every {@link Cortex}
 * implementation calls it rather than reading {@link #prompt()}/{@link #context()} separately: join the
 * prompt strings, append the assembled {@link #context()} (if any), then run the *entire* combined text
 * through a Handlebars template with {@link #promptParams()} as the model object -- so a {@code %%key%%}
 * placeholder resolves from {@code promptParams} no matter whether it originated in a prompt string or
 * inside a context entry's own marshalled JSON (e.g. from {@link PromptContext#defaultMarshall(Object)}).
 *
 * <p><b>Two deliberate deviations from Handlebars' own defaults, both confirmed empirically before landing
 * on them</b> (see {@code CompletionRequestTest}'s collision tests):
 * <ul>
 *   <li><b>{@link EscapingStrategy#NOOP}, not the library default.</b> Handlebars' default escaping
 *       strategy HTML-entity-escapes substituted values (a {@code "} becomes {@code &quot;}, {@code &}
 *       becomes {@code &amp;}). Since {@link PromptContext#defaultMarshall(Object)} produces real JSON --
 *       full of {@code "} characters -- rendering under the default strategy would corrupt that JSON on
 *       every request that combines context with promptParams. NOOP leaves every substitution byte-for-byte
 *       verbatim.</li>
 *   <li><b>{@code %%...%%}, not Handlebars/Mustache's own {@code {{...}}}.</b> {@code {{`/`}}} shows up
 *       constantly in real prompt/context content -- code samples, Go/Jinja/Mustache documentation, JSX --
 *       so leaving the default delimiter active would make ordinary, unintended text collide with real
 *       templating syntax (an unresolved {@code {{key}}} silently renders as empty string; see below).
 *       {@code %} essentially never appears in JSON or English prose, so switching the delimiter is a much
 *       stronger fix than escaping alone -- confirmed empirically that once the delimiter is {@code %%},
 *       literal {@code {{curly}}} text passes through {@link #render()} completely untouched.</li>
 * </ul>
 *
 * <p><b>A {@code %%key%%}-shaped substring with no matching {@link #promptParams()} entry still silently
 * renders as empty string</b>, not an error -- this is Handlebars' own missing-variable behavior, and
 * applies now only to text that actually uses the {@code %%...%%} token, not to arbitrary curly-brace text.
 * A genuinely malformed/unterminated {@code %%} throws {@code HandlebarsException} (unchecked) instead.
 */
public record CompletionRequest(
        List<String> prompt,
        PromptContext context,
        Map<String, Object> promptParams,
        Integer maxTokens,
        Double temperature,
        Map<String, Object> providerOptions) {

    private static final String DELIMITER = "%%";
    private static final Handlebars HANDLEBARS = new Handlebars()
            .with(EscapingStrategy.NOOP)
            .startDelimiter(DELIMITER)
            .endDelimiter(DELIMITER);

    public CompletionRequest {
        prompt = prompt == null ? List.of() : List.copyOf(prompt);
        promptParams = promptParams == null ? Map.of() : Map.copyOf(promptParams);
        providerOptions = providerOptions == null
                ? Map.of()
                : Map.copyOf(providerOptions);
    }

    public static Builder builder() {
        return new Builder();
    }

    /** The final outbound text: prompt strings concatenated, {@link #context()} appended (if any), then
     *  the whole result rendered as a Handlebars template against {@link #promptParams()}. See this
     *  record's own javadoc for the delimiter/escaping-strategy rationale. */
    public String render() {
        String promptText = String.join("\n\n", prompt);
        String combined = context == null ? promptText : promptText + "\n\n" + context.toString();
        try {
            return HANDLEBARS.compileInline(combined).apply(promptParams);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to render CompletionRequest as a Handlebars template", e);
        }
    }

    public static final class Builder {
        private final List<String> prompt = new ArrayList<>();
        private PromptContext context;
        private final Map<String, Object> promptParams = new LinkedHashMap<>();
        private Integer maxTokens;
        private Double temperature;
        private final Map<String, Object> providerOptions = new LinkedHashMap<>();

        private Builder() {
        }

        /** Appends one prompt string. Call repeatedly, or use {@link #prompt(List)}, to build up multiple
         *  prompt strings -- {@link #render()} joins them in order. */
        public Builder prompt(String prompt) {
            this.prompt.add(prompt);
            return this;
        }

        /** Appends/merges a whole list of prompt strings onto whatever's already been added. */
        public Builder prompt(List<String> prompts) {
            this.prompt.addAll(prompts);
            return this;
        }

        public Builder context(PromptContext context) {
            this.context = context;
            return this;
        }

        /** The model object {@link #render()} passes to Handlebars -- a {@code %%key%%} placeholder
         *  anywhere in the prompt or context resolves from this map. */
        public Builder promptParam(String key, Object value) {
            this.promptParams.put(key, value);
            return this;
        }

        public Builder promptParams(Map<String, Object> params) {
            this.promptParams.putAll(params);
            return this;
        }

        public Builder maxTokens(int maxTokens) {
            this.maxTokens = maxTokens;
            return this;
        }

        public Builder temperature(double temperature) {
            this.temperature = temperature;
            return this;
        }

        public Builder providerOption(String key, Object value) {
            this.providerOptions.put(key, value);
            return this;
        }

        public Builder providerOptions(Map<String, Object> options) {
            this.providerOptions.putAll(options);
            return this;
        }

        public CompletionRequest build() {
            return new CompletionRequest(prompt, context, promptParams, maxTokens, temperature, providerOptions);
        }
    }
}
