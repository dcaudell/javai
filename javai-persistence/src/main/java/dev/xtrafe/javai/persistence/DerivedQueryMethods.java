package dev.xtrafe.javai.persistence;

import dev.xtrafe.javai.model.JavAIRuntime;
import dev.xtrafe.javai.vector.EmbeddingVector;

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
 * {@code findNearestByBodyVector} example. This class handles ONLY the vector convention; ordinary
 * Spring-Data-style relational finders ({@code findBy…}/{@code countBy…}/... , OMI-138) are a separate
 * concern handled by {@link DerivedFinderQuery}, and {@link JavAIPI} routes each method name to whichever
 * of the two applies. A name matching neither fails fast, at repository-creation time (see
 * {@link JavAIPI#repository(Class, JavAIPersistenceConfig)}), not on first call.
 */
final class DerivedQueryMethods {

    private static final String PREFIX = "findNearestBy";
    private static final String SUFFIX = "Vector";
    private static final String SUMMARY_MIDDLE = "Summary";
    private static final String CONCATENATED_TEXT_MIDDLE = "ConcatenatedText";

    enum Kind {
        FIELD,
        COMBINED,
        SUMMARY,
        CONCATENATED_TEXT
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
        if (middle.equals(CONCATENATED_TEXT_MIDDLE)) {
            // Rejected here, at repository-creation time, rather than returning nothing on first call
            // (OMI-191). An entity that never opted in has no stored text vector, so this query would
            // silently return an empty list forever -- indistinguishable from "nothing was similar". Naming
            // the missing annotation is the whole point; this is the same convention OMI-212/OMI-214
            // reinforced, and the same one validateSignature above already follows.
            //
            // No ambiguity with a @Vectorize field literally named "concatenatedText": the weaver already
            // refuses that name, because its per-field accessor would collide with concatenatedTextVector().
            if (!JavAIRuntime.participatesInConcatenation(entityType)) {
                throw new IllegalArgumentException(method + " needs " + entityType.getName()
                        + " to participate in concatenated text vectoring, but it does not. Add"
                        + " @Summary(concatenate = true) to the type (to embed its own @Vectorize fields)"
                        + " or to a field (to absorb that child's or collection's text). Without it nothing"
                        + " is ever stored for this query to search.");
            }
            return new ParsedQuery(Kind.CONCATENATED_TEXT, null);
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
                + "findNearestBy<Field>Vector/findNearestByVector/findNearestBySummaryVector/"
                + "findNearestByConcatenatedTextVector(EmbeddingVector, int); "
                + "arbitrary derived queries aren't part of Persistence Bridge's contract.");
    }
}
