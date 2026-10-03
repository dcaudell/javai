package dev.xtrafe.javai.model;

/**
 * The prompt-template delimiter, and the one way to put text in front of it that must never be read as a
 * template (OMI-604).
 *
 * <p>{@code CompletionRequest.render()} runs the whole assembled text -- prompt and context alike -- through
 * Handlebars with {@link #DELIMITER} as both start and end token. Text that is data rather than template (a chat
 * message, a stored memory) can contain that token by accident: a lone {@code %%} makes Handlebars throw and fail
 * the whole completion, and {@code %%key%%} gets substituted. Neither backslash escaping nor a raw block survives
 * the custom delimiter.
 *
 * <p>{@link #protect(String)} rewrites every {@code %%} into a sentinel Handlebars cannot see -- {@code %} followed
 * by U+E000, from the Unicode Private Use Area -- and {@link #restore(String)} turns it back after templating.
 * ⚠️ <b>The sentinel is the same length as the delimiter</b>, so a {@link PromptContext} budget measured over
 * protected text is exactly the budget of the text the model finally sees.
 */
public final class TemplateLiteral {

    /** The start and end token of every prompt template. */
    public static final String DELIMITER = "%%";

    private static final String SENTINEL = "%";

    private TemplateLiteral() {
    }

    /** {@code text} with every delimiter neutralised, so templating passes it through untouched. */
    public static String protect(String text) {
        return text == null ? null : text.replace(DELIMITER, SENTINEL);
    }

    /** Undoes {@link #protect(String)} on already-templated output. */
    public static String restore(String text) {
        return text == null ? null : text.replace(SENTINEL, DELIMITER);
    }
}
