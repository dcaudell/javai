package dev.xtrafe.javai.runtime;

/** Backs {@link PromptContext#of(String)}/{@link PromptContext#of(String, String)} -- wraps raw display
 *  text as a single entry with no GSON marshalling, since it's already the text meant for the prompt. */
record PlainTextEntry(String text) implements Contextable {

    @Override
    public String toContext(PromptContext prompt) {
        return text;
    }
}
