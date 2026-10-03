package dev.xtrafe.javai.model;

/**
 * Text that is data, never template (OMI-604): a chat message, a stored memory, anything a person wrote. It is
 * rendered verbatim -- a {@code %%} in it neither breaks the completion nor substitutes a prompt parameter. Use
 * {@link PromptContext#of(String)} instead for text that is meant to carry {@code %%key%%} placeholders.
 */
public record LiteralTextEntry(String text) implements Contextable {

    public LiteralTextEntry {
        if (text == null) {
            throw new IllegalArgumentException("text must not be null");
        }
    }

    @Override
    public String toContext(PromptContext prompt) {
        return TemplateLiteral.protect(text);
    }
}
