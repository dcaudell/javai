package dev.xtrafe.javai.runtime;

/**
 * Contract for anything that can render itself as informing material for a {@link PromptContext}. The
 * default implementation delegates to {@code prompt.defaultMarshall(this)} (GSON reflection) -- override
 * when an object has a better textual representation than its raw JSON shape, or when GSON's reflection
 * can't handle it at all (custom types, cyclic structures -- see {@link PromptContext}'s own javadoc for
 * the cycle-safety caveat).
 *
 * <p>Lives in {@code javai-runtime}, not {@code javai-completion}, for the same reason {@link JavAIList}/
 * {@link JavAISet}/{@link JavAIMap} do (see this package's {@code package-info.java}): this interface's
 * method signature references {@link PromptContext}, so anything implementing it -- including those three
 * collection types -- must live in the same module {@code PromptContext} does, or {@code javai-runtime}
 * would need an illegal reverse dependency on {@code javai-completion}.
 */
public interface Contextable {

    /**
     * Renders this object as text suitable for inclusion in a prompt. {@code prompt} is the calling
     * {@link PromptContext} -- typically used only for its {@link PromptContext#defaultMarshall(Object)}
     * helper, not inspected further (an implementor doesn't need its own budget/label; that's the
     * containing {@code PromptContext}'s job).
     */
    default String toContext(PromptContext prompt) {
        return prompt.defaultMarshall(this);
    }
}
