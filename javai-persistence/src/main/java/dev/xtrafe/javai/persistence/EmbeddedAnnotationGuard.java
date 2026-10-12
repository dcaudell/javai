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

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Refuses a JavAI annotation placed inside an {@code @Embedded} value, where nothing reads it (OMI-623).
 *
 * <p><b>Why this is a class of its own rather than a method on {@code EntityReflection}.</b> It reads
 * {@code @Taggregate}, and {@code javai-tagging}'s {@code OneContainmentImplementationTest} deliberately fails when
 * a reader appears outside {@code Containment} -- because a second derivation of containment that merely agrees
 * with the first passes every behavioural test right up until it drifts. This is not that: it derives no
 * containment and walks no object graph. It asks only whether an annotation sits where JavAI never looks, the same
 * kind of guard as {@code BulkWriteGuard}. Keeping it in a small, named file is what makes that distinction
 * checkable at a glance, instead of exempting a general-purpose reflection helper.
 */
final class EmbeddedAnnotationGuard {

    private EmbeddedAnnotationGuard() {
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
        for (Field field : EntityReflection.allFields(entityType)) {
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
        for (Field field : EntityReflection.allFields(embeddable)) {
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
}
