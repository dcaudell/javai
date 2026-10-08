package dev.xtrafe.javai.model;

import java.util.List;

/**
 * How one {@link PromptContext} rendered (OMI-619, prompt inspection): the budget it was given, the text it
 * produced, whether that text made it into its parent, and the same for each nested context in its entries.
 *
 * <p>⚠️ {@link #context()} is the very instance that was nested, not the budget-sized copy assembly rendered:
 * match it by identity ({@link java.util.IdentityHashMap}), since {@link PromptContext#equals(Object)} compares
 * entries, so two contexts with the same entries are equal.
 *
 * @param budget   the characters it could fill; {@code null} when unbounded
 * @param text     what it rendered, still protected ({@link TemplateLiteral}) and untemplated, unless a
 *                 {@code CompletionRequest} trace says otherwise
 * @param included whether its parent kept the text; {@code false} when it would have overflowed the parent's budget
 * @param nested   each nested context's trace, in entry order, up to the first one that did not fit
 */
public record PromptContextTrace(PromptContext context, Integer budget, String text, boolean included,
        List<PromptContextTrace> nested) {

    public PromptContextTrace {
        nested = List.copyOf(nested);
    }

    /** This trace with {@code text} in place of each node's text, nested traces included. */
    public PromptContextTrace mapText(java.util.function.UnaryOperator<String> mapping) {
        return new PromptContextTrace(context, budget, mapping.apply(text), included,
                nested.stream().map(n -> n.mapText(mapping)).toList());
    }
}
