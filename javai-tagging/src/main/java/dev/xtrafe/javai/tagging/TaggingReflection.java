package dev.xtrafe.javai.tagging;

import jakarta.persistence.Id;

import java.lang.reflect.Field;
import java.util.UUID;

/**
 * The one reflective need {@code javai-persistence}'s own (package-private, unreachable from here)
 * {@code EntityReflection} would otherwise cover: reading an arbitrary {@code @Taggable} instance's
 * {@code @jakarta.persistence.Id} field, matching {@code JavAIRepository}'s own fixed {@code UUID} identity
 * convention so a single {@link TaggableRef} shape works uniformly across every taggable type.
 */
final class TaggingReflection {

    private TaggingReflection() {
    }

    static UUID idOf(Object instance) {
        Field idField = idField(instance.getClass());
        idField.setAccessible(true);
        UUID id;
        try {
            id = (UUID) idField.get(instance);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Cannot read @Id field on " + instance.getClass(), e);
        }
        if (id == null) {
            // Every taggable instance is identified by this value, and a null one cannot be stored, queried
            // or removed -- so it fails here, naming the two situations that actually produce it, rather
            // than surfacing as a NOT NULL violation from whichever backend happens to write first.
            throw new IllegalArgumentException("Cannot tag " + instance.getClass().getName()
                    + ": its @Id is null. Either the instance was never persisted (JavAI assigns ids in the"
                    + " constructor, so this usually means a hand-written no-arg constructor left it unset),"
                    + " or it is an unresolved persistence proxy, whose @Id field is never populated"
                    + " regardless of initialization state.");
        }
        return id;
    }

    private static Field idField(Class<?> type) {
        for (Class<?> current = type; current != null && current != Object.class; current = current.getSuperclass()) {
            for (Field field : current.getDeclaredFields()) {
                if (field.isAnnotationPresent(Id.class)) {
                    return field;
                }
            }
        }
        throw new IllegalArgumentException(type + " has no @jakarta.persistence.Id field -- JavAITagRepository requires "
                + "every taggable instance to have one, matching JavAIRepository's own identity convention.");
    }
}
