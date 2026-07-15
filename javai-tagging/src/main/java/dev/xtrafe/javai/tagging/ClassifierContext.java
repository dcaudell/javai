package dev.xtrafe.javai.tagging;

import dev.xtrafe.javai.annotations.PromptContext;
import dev.xtrafe.javai.annotations.TagIgnore;

import java.lang.reflect.Field;

/**
 * This class's own {@code @PromptContext} fields, minus any {@code @TagIgnore}'d ones, rendered as
 * {@code label: value} -- what a classification prompt sees. Deliberately a small, separate reflection
 * utility, not a change to {@code javai-completion}'s own {@code PromptContext}/{@code ContextableObject}
 * marshalling: {@code @TagIgnore} is a tagging-specific filter with no meaning to general RAG completions,
 * so {@code javai-tagging} is a pure consumer of the {@code @PromptContext} annotation (already read
 * directly by GSON's own {@code ExclusionStrategy} elsewhere), not of any shared marshalling code. See
 * doc/spec/tagging.md's "What the classifier sees".
 */
final class ClassifierContext {

    private ClassifierContext() {
    }

    static String marshal(Object instance) {
        StringBuilder text = new StringBuilder();
        for (Class<?> type = instance.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (!field.isAnnotationPresent(PromptContext.class) || field.isAnnotationPresent(TagIgnore.class)) {
                    continue;
                }
                field.setAccessible(true);
                Object value;
                try {
                    value = field.get(instance);
                } catch (IllegalAccessException e) {
                    throw new IllegalStateException("Cannot read @PromptContext field " + field + " on "
                            + instance.getClass(), e);
                }
                if (value != null) {
                    text.append(field.getName()).append(": ").append(value).append('\n');
                }
            }
        }
        return text.toString();
    }
}
