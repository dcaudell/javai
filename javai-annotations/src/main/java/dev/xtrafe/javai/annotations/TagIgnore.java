package dev.xtrafe.javai.annotations;

import java.lang.annotation.*;

/**
 * Excludes an otherwise-{@link PromptContext} field from the text a tag classification prompt sees, without
 * affecting what that field renders as for ordinary completions -- a tagging-specific filter layered on top
 * of {@code javai-completion}'s existing allowlist, not a parallel one. A field with no {@link PromptContext}
 * to begin with needs no {@code @TagIgnore}, since it was never going to be classifier-visible either way.
 * See doc/spec/tagging.md's "What the classifier sees".
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface TagIgnore {
}
