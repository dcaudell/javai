package dev.xtrafe.javai.persistence;

import dev.xtrafe.javai.annotations.Vectorize;
import jakarta.persistence.Id;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Small, hierarchy-aware reflection helpers shared by both backends -- mirrors the hierarchy-walking style
 * {@code JavAIRuntime}'s {@code allFields()}/{@code findField()} already use in {@code javai-model}, but
 * reimplemented here rather than exposed cross-module: this module only needs field *discovery* (annotated
 * names, the {@code @Id} value), never the dirty-tracking/lazy-recompute machinery those live alongside.
 */
final class EntityReflection {

    private EntityReflection() {
    }

    /** Every field declared anywhere in {@code type}'s hierarchy annotated with {@code annotationType}. */
    static Set<String> fieldNamesAnnotatedWith(Class<?> type, Class<? extends Annotation> annotationType) {
        Set<String> names = new LinkedHashSet<>();
        for (Field field : allFields(type)) {
            if (field.isAnnotationPresent(annotationType)) {
                names.add(field.getName());
            }
        }
        return names;
    }

    /** Every {@code @Vectorize} field name -- what {@code findNearestBy<Field>Vector} validates against. */
    static Set<String> vectorizeFieldNames(Class<?> type) {
        return fieldNamesAnnotatedWith(type, Vectorize.class);
    }

    static Field idField(Class<?> type) {
        for (Field field : allFields(type)) {
            if (field.isAnnotationPresent(Id.class)) {
                if (field.getType() != UUID.class) {
                    throw new IllegalArgumentException(
                            "@Id field " + field + " must be of type UUID -- Persistence Bridge fixes the "
                                    + "identity type to UUID across both backends (see JavAIRepository's javadoc)");
                }
                return field;
            }
        }
        throw new IllegalArgumentException(
                type + " has no @Id (jakarta.persistence.Id) field -- required to be persistable");
    }

    static UUID readId(Object entity) {
        Field field = idField(entity.getClass());
        try {
            field.setAccessible(true);
            return (UUID) field.get(entity);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Cannot read @Id field " + field + " on " + entity.getClass(), e);
        }
    }

    static void writeId(Object entity, UUID id) {
        Field field = idField(entity.getClass());
        try {
            field.setAccessible(true);
            field.set(entity, id);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Cannot write @Id field " + field + " on " + entity.getClass(), e);
        }
    }

    static Object readField(Object entity, String fieldName) {
        Field field = findField(entity.getClass(), fieldName);
        try {
            field.setAccessible(true);
            return field.get(entity);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Cannot read field " + fieldName + " on " + entity.getClass(), e);
        }
    }

    static Field findField(Class<?> type, String fieldName) {
        for (Class<?> current = type; current != null && current != Object.class; current = current.getSuperclass()) {
            try {
                return current.getDeclaredField(fieldName);
            } catch (NoSuchFieldException ignored) {
                // keep searching up the hierarchy
            }
        }
        throw new IllegalStateException("Expected field " + fieldName + " on " + type + " or one of its superclasses");
    }

    static List<Field> allFields(Class<?> type) {
        List<Field> fields = new ArrayList<>();
        for (Class<?> current = type; current != null && current != Object.class; current = current.getSuperclass()) {
            for (Field field : current.getDeclaredFields()) {
                if (!field.isSynthetic() && !field.getName().startsWith("$javai$")) {
                    fields.add(field);
                }
            }
        }
        return fields;
    }
}
