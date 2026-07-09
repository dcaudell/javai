package dev.xtrafe.javai.runtime;

/**
 * Wraps an arbitrary object as a {@link Contextable} using the default GSON marshalling, for objects that
 * don't (or can't) implement {@link Contextable} themselves -- e.g. a third-party class you don't own.
 */
public record ContextableObject<T>(T value) implements Contextable {

    @Override
    public String toContext(PromptContext prompt) {
        return prompt.defaultMarshall(value);
    }
}
