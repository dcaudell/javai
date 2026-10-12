package dev.xtrafe.javai.completion;

import java.util.regex.Pattern;

/**
 * A tool a model may call while it answers a {@link CompletionRequest} (OMI-643): its name, what it does, and the record
 * its arguments arrive as. The input schema is derived from that record exactly as a typed response's is
 * ({@link CompletionRequest.Builder#responseType}), so the arguments a model sends read back with
 * {@link CompletionToolCall#arguments(Class)}.
 *
 * <p>The description is the caller's: it is prompt text, and this library writes none of a caller's prompts.
 *
 * @param name        what the model calls it: letters, digits, {@code _} and {@code -}, at most 64
 * @param description what it does and when to use it, for the model
 * @param inputType   the record (or class) its arguments arrive as
 */
public record CompletionTool(String name, String description, Class<?> inputType) {

    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    public CompletionTool {
        if (name == null || !NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("a tool's name is 1-64 letters, digits, _ or - -- got " + name);
        }
        if (description == null || description.isBlank()) {
            throw new IllegalArgumentException("tool '" + name + "' needs a description: it is how the model knows"
                    + " when to call it");
        }
        inputSchema(inputType); // refuses an input type that has no object schema now, not on the first call
    }

    /** The JSON schema of the tool's arguments, as the provider is sent it. */
    public String inputSchema() {
        return inputSchema(inputType);
    }

    private static String inputSchema(Class<?> inputType) {
        if (inputType == null) {
            throw new IllegalArgumentException("a tool needs an input type -- a record of its arguments");
        }
        return ResponseSchemas.inputSchema(inputType);
    }
}
