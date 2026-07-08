package dev.xtrafe.javai.persistence;

import dev.xtrafe.javai.runtime.EmbeddingVector;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;

/**
 * Parses and validates the ONE derived-query convention {@link JavAIRepository} supports:
 * {@code findNearestBy<Field>Vector(EmbeddingVector reference, int limit)}, plus the whole-object
 * {@code findNearestByVector}/{@code findNearestBySummaryVector} variants. {@code <Field>} names the SAME
 * accessor the weaver already synthesizes in-memory (e.g. {@code bodyVector()} ->
 * {@code findNearestByBodyVector}), not the bare field name -- deliberately mirroring what a developer
 * would already call directly on a woven object, per doc/spec/persistence-bridge.md's own
 * {@code findNearestByBodyVector} example. Anything else fails fast, at repository-creation time (see
 * {@link JavAIPI#repository(Class)}), not on first call -- Persistence Bridge is not a general
 * derived-query framework.
 */
final class DerivedQueryMethods {

    private static final String PREFIX = "findNearestBy";
    private static final String SUFFIX = "Vector";
    private static final String SUMMARY_MIDDLE = "Summary";

    enum Kind {
        FIELD,
        COMBINED,
        SUMMARY
    }

    record ParsedQuery(Kind kind, String fieldName) {
    }

    private DerivedQueryMethods() {
    }

    static boolean isDerivedQueryMethod(Method method) {
        return method.getName().startsWith(PREFIX);
    }

    /** Validates {@code method}'s shape and, for {@link Kind#FIELD}, that the field is really
     *  {@code @Vectorize}d on {@code entityType}. Throws with a clear message otherwise. */
    static ParsedQuery parse(Method method, Class<?> entityType) {
        String name = method.getName();
        if (!name.startsWith(PREFIX) || !name.endsWith(SUFFIX) || name.length() < PREFIX.length() + SUFFIX.length()) {
            throw unsupported(method, entityType);
        }
        validateSignature(method, entityType);

        String middle = name.substring(PREFIX.length(), name.length() - SUFFIX.length());
        if (middle.isEmpty()) {
            return new ParsedQuery(Kind.COMBINED, RepositoryBackend.COMBINED_VECTOR_FIELD);
        }
        if (middle.equals(SUMMARY_MIDDLE)) {
            return new ParsedQuery(Kind.SUMMARY, null);
        }
        String fieldName = Character.toLowerCase(middle.charAt(0)) + middle.substring(1);
        Set<String> vectorizeFields = EntityReflection.vectorizeFieldNames(entityType);
        if (!vectorizeFields.contains(fieldName)) {
            throw new IllegalArgumentException(method + " does not match a @Vectorize field on "
                    + entityType.getName() + " -- known @Vectorize fields: " + vectorizeFields);
        }
        return new ParsedQuery(Kind.FIELD, fieldName);
    }

    private static void validateSignature(Method method, Class<?> entityType) {
        Class<?>[] params = method.getParameterTypes();
        boolean shapeOk = params.length == 2
                && params[0] == EmbeddingVector.class
                && (params[1] == int.class || params[1] == Integer.class)
                && List.class.isAssignableFrom(method.getReturnType());
        if (!shapeOk) {
            throw new IllegalArgumentException(method + " on repository for " + entityType.getName()
                    + " must have the shape findNearestBy<Field>Vector(EmbeddingVector reference, int limit): List<T>");
        }
    }

    private static IllegalArgumentException unsupported(Method method, Class<?> entityType) {
        return new IllegalArgumentException("Unsupported repository method " + method + " on repository for "
                + entityType.getName() + " -- JavAIRepository only supports the base CRUD contract plus "
                + "findNearestBy<Field>Vector/findNearestByVector/findNearestBySummaryVector(EmbeddingVector, int); "
                + "arbitrary derived queries aren't part of Persistence Bridge's contract.");
    }
}
