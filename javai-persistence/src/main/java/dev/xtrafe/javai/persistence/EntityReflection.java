package dev.xtrafe.javai.persistence;

import dev.xtrafe.javai.annotations.ExternalVector;
import dev.xtrafe.javai.annotations.JavAIVectorizable;
import dev.xtrafe.javai.annotations.Summary;
import dev.xtrafe.javai.annotations.Taggable;
import dev.xtrafe.javai.annotations.Taggregate;
import dev.xtrafe.javai.annotations.Vectorize;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Embeddable;
import jakarta.persistence.Embedded;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Id;
import jakarta.persistence.Version;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.util.ArrayList;
import java.util.HashSet;
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

    /** Every {@code @Any} field anywhere in {@code type}'s hierarchy -- a polymorphic to-one whose target may
     *  be any of several unrelated entities, resolved by a discriminator rather than a foreign key. Only the
     *  Postgres backend maps one at all; the other two refuse it at registration. */
    static List<Field> anyFields(Class<?> type) {
        List<Field> fields = new ArrayList<>();
        for (Field field : allFields(type)) {
            if (isAny(field)) {
                fields.add(field);
            }
        }
        return fields;
    }

    static boolean isAny(Field field) {
        return field.isAnnotationPresent(org.hibernate.annotations.Any.class);
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

    /** The {@code @Version} field anywhere in {@code type}'s hierarchy, or {@code null} -- optimistic locking
     *  is opt-in, so having none is the ordinary case and not an error (OMI-254). */
    static Field versionField(Class<?> type) {
        for (Field field : allFields(type)) {
            if (field.isAnnotationPresent(Version.class)) {
                field.setAccessible(true);
                return field;
            }
        }
        return null;
    }

    /**
     * A new instance through {@code type}'s declared constructor taking {@code parameterTypes}, whatever its
     * visibility: JPA allows a {@code protected} no-arg constructor, and a hydrated type rarely shares this
     * package, so the constructor is made accessible first (OMI-607).
     */
    static <T> T instantiate(Class<T> type, Class<?>[] parameterTypes, Object... args)
            throws ReflectiveOperationException {
        java.lang.reflect.Constructor<T> constructor = type.getDeclaredConstructor(parameterTypes);
        constructor.setAccessible(true);
        return constructor.newInstance(args);
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

    /**
     * Every instance field declared anywhere in {@code type}'s hierarchy.
     *
     * <p>⚠️ <b>{@code static} fields are excluded, and that is a correctness requirement rather than tidiness</b>
     * (found by OMI-290). A static is class state, so it has no per-instance value to store and no per-instance
     * slot to restore into -- but both reflective backends map a field by walking this list, so a constant as
     * ordinary as {@code static final String MODEL = "..."} on an entity was written as a document/node
     * property on save and then <em>written back</em> on load, which fails outright:
     * {@code Cannot write field static final java.lang.String ...}. The Postgres backend never saw it because
     * Hibernate does its own mapping and ignores statics; nothing else in this repository declared one on an
     * entity, so the whole class of failure sat one ordinary constant away the entire time.
     */
    /**
     * Whether {@code value} is a leaf a reflective walk must not enter: a JDK value ({@code String} in an
     * {@code @ElementCollection}, OMI-275) or an enum, wherever it is declared, whose fields are
     * {@code java.lang.Enum}'s. Entering either throws: the module system will not open {@code java.lang}
     * (OMI-613).
     */
    static boolean isLeafValue(Object value) {
        return value instanceof Enum<?> || value.getClass().getName().startsWith("java.");
    }

    /** Field annotations read off the entity's own fields only, so ignored on a field of an embedded value. */
    private static final List<Class<? extends Annotation>> FIELD_ANNOTATIONS_NOT_READ_WHEN_EMBEDDED =
            List.of(Vectorize.class, Summary.class, Taggregate.class);

    /** Type annotations read off the entity's own class only, so ignored on an embeddable's class. */
    private static final List<Class<? extends Annotation>> TYPE_ANNOTATIONS_NOT_READ_WHEN_EMBEDDED =
            List.of(JavAIVectorizable.class, Taggable.class, ExternalVector.class, Summary.class, Taggregate.class);

    /**
     * Refuses a JavAI annotation inside an {@code @Embedded} value of {@code entityType}, at any depth (OMI-623).
     *
     * <p>Refused rather than ignored: the weaver, {@code JavAIRuntime} and this module all walk the entity's own
     * class hierarchy and never into an embedded value, so such an annotation does nothing -- no vector, no text in
     * the summary, no dirty tracking, no containment edge -- and nothing says so. Until an embedded value's
     * annotations are read as its owner's, the adopter has to know.
     */
    static void refuseJavAIAnnotationsInsideEmbeddedValues(Class<?> entityType) {
        List<String> found = new ArrayList<>();
        for (Field field : allFields(entityType)) {
            Class<?> embedded = embeddedType(field);
            if (embedded != null) {
                collectUnreadAnnotations(embedded, entityType.getSimpleName() + "." + field.getName(), found,
                        new HashSet<>());
            }
        }
        if (!found.isEmpty()) {
            throw new IllegalArgumentException("JavAI annotations inside an @Embedded value of " + entityType.getName()
                    + " would be silently ignored, so they are refused: " + String.join(", ", found)
                    + ". JavAI reads its annotations off the entity's own fields and class, never off an embedded "
                    + "value's. Declare those fields on " + entityType.getSimpleName() + " itself, or make the "
                    + "value an @Entity of its own (OMI-623).");
        }
    }

    /** The embeddable a field holds -- as an {@code @Embedded}/{@code @EmbeddedId} value or an element collection of
     *  one -- or null. */
    private static Class<?> embeddedType(Field field) {
        if (field.isAnnotationPresent(Embedded.class) || field.isAnnotationPresent(EmbeddedId.class)
                || field.getType().isAnnotationPresent(Embeddable.class)) {
            return field.getType();
        }
        if (field.isAnnotationPresent(ElementCollection.class)
                && field.getGenericType() instanceof ParameterizedType parameterized) {
            for (java.lang.reflect.Type argument : parameterized.getActualTypeArguments()) {
                if (argument instanceof Class<?> element && element.isAnnotationPresent(Embeddable.class)) {
                    return element;
                }
            }
        }
        return null;
    }

    private static void collectUnreadAnnotations(Class<?> embeddable, String path, List<String> found,
                                                 Set<Class<?>> visiting) {
        if (!visiting.add(embeddable)) {
            return;
        }
        for (Class<? extends Annotation> annotation : TYPE_ANNOTATIONS_NOT_READ_WHEN_EMBEDDED) {
            if (embeddable.isAnnotationPresent(annotation)) {
                found.add(path + " (@" + annotation.getSimpleName() + " on " + embeddable.getSimpleName() + ")");
            }
        }
        for (Field field : allFields(embeddable)) {
            String fieldPath = path + "." + field.getName();
            for (Class<? extends Annotation> annotation : FIELD_ANNOTATIONS_NOT_READ_WHEN_EMBEDDED) {
                if (field.isAnnotationPresent(annotation)) {
                    found.add(fieldPath + " (@" + annotation.getSimpleName() + ")");
                }
            }
            Class<?> nested = embeddedType(field);
            if (nested != null) {
                collectUnreadAnnotations(nested, fieldPath, found, visiting);
            }
        }
        visiting.remove(embeddable);
    }

    static List<Field> allFields(Class<?> type) {
        List<Field> fields = new ArrayList<>();
        for (Class<?> current = type; current != null && current != Object.class; current = current.getSuperclass()) {
            for (Field field : current.getDeclaredFields()) {
                if (!field.isSynthetic()
                        && !java.lang.reflect.Modifier.isStatic(field.getModifiers())
                        && !field.getName().startsWith("$javai$")) {
                    fields.add(field);
                }
            }
        }
        return fields;
    }
}
