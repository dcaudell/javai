package dev.xtrafe.javai.completion;

import com.google.gson.Gson;
import com.google.gson.JsonParser;

/**
 * One call a model made to a {@link CompletionTool} while it answered (OMI-643), as {@link CompletionResult#toolCalls()}
 * reports it: the tool, the arguments the model sent, what the executor returned (or why it failed), and how long the
 * executor took.
 *
 * @param name      the tool called
 * @param arguments the arguments, as the JSON the model sent
 * @param result    what the executor returned, or {@code null} when it threw
 * @param error     why the executor failed, or {@code null} when it returned
 * @param millis    how long the executor took
 */
public record CompletionToolCall(String name, String arguments, String result, String error, long millis) {

    private static final Gson GSON = new Gson();

    /** The arguments read as {@code type}, held to it as a typed response is: every field present, of its type. */
    public <T> T arguments(Class<T> type) {
        ResponseSchemas.validate(JsonParser.parseString(arguments), type, "arguments");
        return GSON.fromJson(arguments, type);
    }
}
