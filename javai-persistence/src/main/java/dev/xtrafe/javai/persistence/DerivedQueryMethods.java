package dev.xtrafe.javai.persistence;

import dev.xtrafe.javai.model.JavAIRuntime;
import dev.xtrafe.javai.vector.EmbeddingVector;
import dev.xtrafe.javai.vector.Ranked;
import org.springframework.data.core.PropertyReferenceException;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.query.parser.Part;
import org.springframework.data.repository.query.parser.PartTree;

import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Parses and validates the {@code findNearestBy…Vector} derived-query convention: the vector half of a
 * {@link JavAIRepository}'s method-name grammar, as {@link DerivedFinderQuery} is the relational half.
 *
 * <p>{@code <Field>} names the SAME accessor the weaver already synthesizes in-memory (e.g.
 * {@code bodyVector()} -> {@code findNearestByBodyVector}), not the bare field name -- deliberately
 * mirroring what a developer would already call directly on a woven object, per
 * doc/spec/persistence-bridge.md's own {@code findNearestByBodyVector} example.
 *
 * <h2>Since OMI-230: a predicate, a distance, and a page</h2>
 *
 * The convention used to be exactly {@code (EmbeddingVector, int): List<T>} and nothing else, which made
 * "the nearest N that <em>also</em> satisfy X" inexpressible -- the only recourse being to over-fetch and
 * discard, unboundedly, having thrown away the ranking information that would have said whether to fetch
 * more. Three things were added, all of them by reusing machinery that already existed rather than growing
 * a second grammar:
 *
 * <ul>
 *   <li><b>A predicate tail</b> -- {@code findNearestByCaptionVectorAndKindIn(reference, limit, kinds)}.
 *       Everything after {@code Vector} is handed to Spring Data's own {@link PartTree}, so the entire
 *       relational vocabulary {@code DerivedFinderQuery} already supports (operators, {@code And}/{@code Or},
 *       nested paths, {@code IgnoreCase}) is available with no new parser.</li>
 *   <li><b>A ranked return</b> -- declaring {@code List<Ranked<T>>} instead of {@code List<T>} keeps the
 *       similarity each hit was ranked on. See {@link Ranked}.</li>
 *   <li><b>Paging</b> -- a trailing {@link Pageable} supplies an offset (and its page size becomes the
 *       limit), or a trailing {@link Limit} overrides the limit; the same trailing-parameter convention
 *       {@code DerivedFinderQuery} already uses for relational finders.</li>
 * </ul>
 *
 * <p>Anything invalid fails at repository-creation time (see
 * {@link JavAIPI#repository(Class, JavAIPersistenceConfig)}), never on first call.
 */
final class DerivedQueryMethods {

    private static final String PREFIX = "findNearestBy";
    private static final String SUFFIX = "Vector";
    private static final String SUMMARY_MIDDLE = "Summary";
    private static final String CONCATENATED_TEXT_MIDDLE = "ConcatenatedText";
    private static final String PREDICATE_LEAD_IN = "And";

    enum Kind {
        FIELD,
        COMBINED,
        SUMMARY,
        CONCATENATED_TEXT
    }

    /**
     * A parsed vector query method: which vector to rank against, the optional relational narrowing, and
     * where in the argument list each piece comes from.
     *
     * @param predicate         the parsed narrowing tail, or {@code null} for an unnarrowed search
     * @param limitParamIndex   index of the {@code int limit} parameter, or {@code -1} when a
     *                          {@link Pageable} supplies the limit instead
     * @param firstBindable     index of the first predicate-binding argument
     * @param bindableCount     how many arguments bind into the predicate
     * @param pageableIndex     index of a trailing {@link Pageable}, or {@code -1}
     * @param limitObjectIndex  index of a trailing {@link Limit}, or {@code -1}
     * @param ranked            whether the method returns {@code List<Ranked<T>>} rather than {@code List<T>}
     */
    record ParsedQuery(Kind kind, String fieldName, PartTree predicate, int limitParamIndex, int firstBindable,
            int bindableCount, int pageableIndex, int limitObjectIndex, boolean ranked,
            boolean[] anyDiscriminatorFlags) {

        boolean isNarrowed() {
            return predicate != null;
        }
    }

    private DerivedQueryMethods() {
    }

    static boolean isDerivedQueryMethod(Method method) {
        return method.getName().startsWith(PREFIX);
    }

    /** Validates {@code fieldName} really is {@code @Vectorize}d on {@code entityType}, for the builder
     *  idiom's {@code nearestBy(String)} entry point -- the runtime counterpart of what {@link #parse}
     *  checks at repository-creation time, sharing the check so the two idioms cannot disagree. */
    static String requireVectorizeField(Class<?> entityType, String fieldName) {
        Set<String> searchable = fieldGrainVectorNames(entityType);
        if (fieldName == null || !searchable.contains(fieldName)) {
            throw new IllegalArgumentException("'" + fieldName + "' is not a @Vectorize field or"
                    + " @ExternalVector on " + entityType.getName() + " -- known: " + searchable);
        }
        return fieldName;
    }

    /**
     * Every name with a vector of its own at <b>field grain</b> -- {@code @Vectorize} fields and
     * {@code @ExternalVector}s alike (OMI-290).
     *
     * <p>They are one namespace to a query for the same reason they are one namespace to the cache: an
     * external vector is stored under its own name in exactly the per-field shape a {@code @Vectorize} field
     * uses, so {@code findNearestByPixelsVector} needs nothing from a backend that
     * {@code findNearestByCaptionVector} did not already need. What makes the two resolve to different
     * storage is the reference vector's own model, which every backend already keys on -- so the only thing
     * that had to change to make an external vector searchable was this check, which was refusing the name
     * before any of that machinery got a chance to work.
     */
    static Set<String> fieldGrainVectorNames(Class<?> entityType) {
        Set<String> names = new java.util.LinkedHashSet<>(EntityReflection.vectorizeFieldNames(entityType));
        names.addAll(JavAIRuntime.externalVectorNames(entityType));
        return names;
    }

    /** @see #requireVectorizeField -- the same shared check, for the concatenated-text kind. */
    static void requireConcatenationParticipant(Class<?> entityType) {
        if (!JavAIRuntime.participatesInConcatenation(entityType)) {
            throw new IllegalArgumentException(entityType.getName() + " does not participate in concatenated"
                    + " text vectoring. Add @Summary(concatenate = true) to the type (to embed its own"
                    + " @Vectorize fields) or to a field (to absorb that child's or collection's text)."
                    + " Without it nothing is ever stored for this query to search.");
        }
    }

    /** Validates {@code method}'s name and signature against {@code entityType}, throwing a clear
     *  {@code IllegalArgumentException} otherwise. */
    static ParsedQuery parse(Method method, Class<?> entityType) {
        String name = method.getName();
        if (!name.startsWith(PREFIX)) {
            throw unsupported(method, entityType);
        }
        Split split = splitAtVectorKeyword(method, entityType, name.substring(PREFIX.length()));
        Kind kind = split.kind();
        if (kind == Kind.CONCATENATED_TEXT) {
            // Rejected here, at repository-creation time, rather than returning nothing on first call
            // (OMI-191). An entity that never opted in has no stored text vector, so this query would
            // silently return an empty list forever -- indistinguishable from "nothing was similar".
            //
            // No ambiguity with a @Vectorize field literally named "concatenatedText": the weaver already
            // refuses that name, because its per-field accessor would collide with concatenatedTextVector().
            try {
                requireConcatenationParticipant(entityType);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(method + " needs " + e.getMessage(), e);
            }
        }
        // The OfType keyword (OMI-407) is stripped from the narrowing tail exactly as DerivedFinderQuery
        // strips it from a whole method name -- the two grammars share the machinery so an @Any discriminator
        // predicate cannot mean one thing in a findBy… and another in a findNearestBy…VectorAnd… .
        DerivedFinderQuery.AnyTypeRewrite rewrite =
                DerivedFinderQuery.stripAnyTypeKeyword(split.tail(), entityType);
        PartTree predicate = parsePredicateTail(method, entityType, rewrite.cleanName());
        boolean[] anyFlags = predicate == null
                ? new boolean[0]
                : DerivedFinderQuery.anyDiscriminatorFlags(predicate, rewrite, method);
        if (predicate == null && !rewrite.isEmpty()) {
            throw new IllegalArgumentException(method + " uses '" + DerivedFinderQuery.ANY_TYPE_KEYWORD
                    + "' outside a narrowing predicate -- it belongs after And, as in "
                    + "findNearestByCaptionVectorAndTargetOfType(reference, limit, MediaAsset.class).");
        }
        return resolveSignature(method, entityType, kind, split.fieldName(), predicate, anyFlags);
    }

    private record Split(Kind kind, String fieldName, String tail) {
    }

    /**
     * Finds which {@code Vector} in the method name is the convention's own keyword, and splits the name
     * there.
     *
     * <p>Scanned <b>right to left</b>, which matters in both directions. Right-to-left is what lets a
     * {@code @Vectorize} field whose own name ends in {@code Vector} keep working
     * ({@code findNearestBySubVectorVector} -> field {@code subVector}), preserving the behavior of the
     * original "everything between the prefix and the trailing {@code Vector}" rule. And requiring the tail
     * to be empty or begin with {@code And} is what stops a <em>predicate</em> property containing
     * {@code Vector} from stealing the split: in
     * {@code findNearestByTitleVectorAndVectorNameContaining}, the rightmost {@code Vector} leaves the tail
     * {@code NameContaining}, which has no lead-in, so the scan keeps going and lands on the right one.
     */
    private static Split splitAtVectorKeyword(Method method, Class<?> entityType, String afterPrefix) {
        Set<String> vectorizeFields = fieldGrainVectorNames(entityType);
        for (int at = afterPrefix.lastIndexOf(SUFFIX); at >= 0; at = afterPrefix.lastIndexOf(SUFFIX, at - 1)) {
            String middle = afterPrefix.substring(0, at);
            String tail = afterPrefix.substring(at + SUFFIX.length());
            if (!tail.isEmpty() && !tail.startsWith(PREDICATE_LEAD_IN)) {
                continue; // not the keyword: whatever follows isn't a predicate lead-in
            }
            if (middle.isEmpty()) {
                return new Split(Kind.COMBINED, RepositoryBackend.COMBINED_VECTOR_FIELD, tail);
            }
            if (middle.equals(SUMMARY_MIDDLE)) {
                return new Split(Kind.SUMMARY, null, tail);
            }
            if (middle.equals(CONCATENATED_TEXT_MIDDLE)) {
                return new Split(Kind.CONCATENATED_TEXT, null, tail);
            }
            String fieldName = Character.toLowerCase(middle.charAt(0)) + middle.substring(1);
            if (vectorizeFields.contains(fieldName)) {
                return new Split(Kind.FIELD, fieldName, tail);
            }
        }
        if (afterPrefix.contains(SUFFIX)) {
            throw new IllegalArgumentException(method + " does not match a @Vectorize field or"
                    + " @ExternalVector on " + entityType.getName() + " -- known: " + vectorizeFields
                    + ". The name must be findNearestBy<Field>Vector, optionally followed by And<Predicate>.");
        }
        throw unsupported(method, entityType);
    }

    /** Hands the narrowing tail to {@link PartTree} by re-spelling it as the {@code findBy…} it is. */
    private static PartTree parsePredicateTail(Method method, Class<?> entityType, String tail) {
        if (tail.isEmpty()) {
            return null;
        }
        String predicate = tail.substring(PREDICATE_LEAD_IN.length());
        if (predicate.isEmpty()) {
            throw new IllegalArgumentException(method + " ends with '" + PREDICATE_LEAD_IN + "' but names no "
                    + "property to narrow by -- e.g. findNearestByCaptionVectorAndKindIn(reference, limit, kinds).");
        }
        try {
            return new PartTree("findBy" + predicate, entityType);
        } catch (PropertyReferenceException e) {
            throw new IllegalArgumentException(method + " narrows by a property that does not exist on "
                    + entityType.getName() + " -- " + e.getMessage(), e);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Cannot parse the narrowing predicate of " + method
                    + " on repository for " + entityType.getName() + " -- " + e.getMessage(), e);
        }
    }

    /**
     * Resolves which parameter is which, and validates the whole signature.
     *
     * <p>The shape is {@code (EmbeddingVector reference, [int limit,] <bindables…> [, Pageable|Limit])}. The
     * {@code int limit} is optional only when a trailing {@link Pageable} supplies one instead, because a
     * search with no bound at all is never what a caller means.
     */
    private static ParsedQuery resolveSignature(Method method, Class<?> entityType, Kind kind, String fieldName,
            PartTree predicate, boolean[] anyDiscriminatorFlags) {
        Class<?>[] params = method.getParameterTypes();
        if (params.length == 0 || params[0] != EmbeddingVector.class) {
            throw badShape(method, entityType);
        }
        int pageableIndex = -1;
        int limitObjectIndex = -1;
        for (int i = 1; i < params.length; i++) {
            if (Pageable.class.isAssignableFrom(params[i])) {
                pageableIndex = i;
            } else if (Limit.class.isAssignableFrom(params[i])) {
                limitObjectIndex = i;
            }
        }
        int trailingStart = params.length;
        for (int index : new int[] {pageableIndex, limitObjectIndex}) {
            if (index >= 0) {
                trailingStart = Math.min(trailingStart, index);
            }
        }
        int specialCount = (pageableIndex >= 0 ? 1 : 0) + (limitObjectIndex >= 0 ? 1 : 0);
        if (trailingStart + specialCount != params.length) {
            throw new IllegalArgumentException(method + " must declare any Pageable/Limit parameter after all "
                    + "other parameters.");
        }

        int limitParamIndex = -1;
        int firstBindable = 1;
        if (params.length > 1 && (params[1] == int.class || params[1] == Integer.class)) {
            limitParamIndex = 1;
            firstBindable = 2;
        } else if (pageableIndex < 0 && limitObjectIndex < 0) {
            // No bound of any kind. Refused rather than defaulted: an unbounded similarity search over a
            // whole store ranks everything in it, which is never what a caller meant to ask for.
            throw badShape(method, entityType);
        }
        int bindableCount = trailingStart - firstBindable;
        if (bindableCount < 0) {
            throw badShape(method, entityType);
        }

        int demanded = 0;
        if (predicate != null) {
            for (PartTree.OrPart orPart : predicate) {
                for (Part part : orPart) {
                    demanded += part.getNumberOfArguments();
                }
            }
        }
        if (bindableCount != demanded) {
            throw new IllegalArgumentException(method + " on repository for " + entityType.getName()
                    + " declares " + bindableCount + " argument(s) to bind into its narrowing predicate but the "
                    + "predicate needs " + demanded + " -- check the property/keyword count against the "
                    + "argument list, remembering the reference vector and limit come first.");
        }
        if (!List.class.isAssignableFrom(method.getReturnType())) {
            throw badShape(method, entityType);
        }
        // Checked against the parameters the atoms will actually bind, which start after the reference vector
        // and the optional int limit rather than at zero -- the one way this differs from the relational half.
        int ordinal = 0;
        int cursor = firstBindable;
        if (predicate != null) {
            for (PartTree.OrPart orPart : predicate) {
                for (Part part : orPart) {
                    int arity = part.getNumberOfArguments();
                    if (anyDiscriminatorFlags[ordinal]) {
                        DerivedFinderQuery.validateAnyDiscriminatorPart(part, entityType,
                                java.util.Arrays.copyOfRange(params, cursor, cursor + arity), method);
                    }
                    cursor += arity;
                    ordinal++;
                }
            }
        }
        return new ParsedQuery(kind, fieldName, predicate, limitParamIndex, firstBindable, bindableCount,
                pageableIndex, limitObjectIndex, returnsRanked(method), anyDiscriminatorFlags);
    }

    /** Whether the declared return type is {@code List<Ranked<…>>} rather than {@code List<T>}. */
    private static boolean returnsRanked(Method method) {
        if (method.getGenericReturnType() instanceof ParameterizedType listType) {
            Type[] arguments = listType.getActualTypeArguments();
            if (arguments.length == 1) {
                Type element = arguments[0] instanceof ParameterizedType parameterized
                        ? parameterized.getRawType() : arguments[0];
                return element == Ranked.class;
            }
        }
        return false;
    }

    /** Binds one call's arguments into the query this parse describes. */
    static NearestSpec toSpec(ParsedQuery parsed, Object[] args) {
        EmbeddingVector reference = (EmbeddingVector) args[0];
        int limit = parsed.limitParamIndex() >= 0 ? (Integer) args[parsed.limitParamIndex()] : Integer.MAX_VALUE;
        int offset = 0;
        if (parsed.pageableIndex() >= 0 && args[parsed.pageableIndex()] instanceof Pageable pageable
                && pageable.isPaged()) {
            offset = Math.toIntExact(pageable.getOffset());
            limit = pageable.getPageSize();
        }
        if (parsed.limitObjectIndex() >= 0 && args[parsed.limitObjectIndex()] instanceof Limit explicit
                && explicit.isLimited()) {
            limit = explicit.max();
        }
        // No model named: the findNearestBy...Vector convention has no room for one, so it means the
        // reference's own -- which is what it has always meant (OMI-458).
        return new NearestSpec(parsed.kind(), parsed.fieldName(), reference, limit, offset,
                bindPredicate(parsed, args), null);
    }

    /**
     * The query as it will be shaped, before any call supplies arguments -- what
     * {@link JavAIPI#repository(Class, JavAIPersistenceConfig)} hands a backend so it can refuse an
     * unservable query at creation time rather than on first use.
     *
     * <p>The predicate atoms are real: their property paths and operators come straight from the parsed
     * method name, and only the bound <em>values</em> are missing, because no call has happened yet. That is
     * exactly the part a feasibility check does not need -- a backend refuses on the shape of a query
     * (whether it narrows at all, which properties it reaches through), never on which values were passed.
     */
    static NearestSpec shapeOnly(ParsedQuery parsed) {
        List<List<DerivedFinderQuery.BoundPart>> groups = new ArrayList<>();
        int ordinal = 0;
        if (parsed.isNarrowed()) {
            for (PartTree.OrPart orPart : parsed.predicate()) {
                List<DerivedFinderQuery.BoundPart> group = new ArrayList<>();
                for (Part part : orPart) {
                    group.add(new DerivedFinderQuery.BoundPart(part.getProperty(), part.getType(), false,
                            List.of(), parsed.anyDiscriminatorFlags()[ordinal++]));
                }
                groups.add(List.copyOf(group));
            }
        }
        return new NearestSpec(parsed.kind(), parsed.fieldName(), null, 1, 0, List.copyOf(groups), null);
    }

    /** The narrowing tail's atoms with this call's arguments sliced in, in method-name order -- the same
     *  OR-of-ANDs shape {@link DerivedFinderQuery#boundOrGroups} produces, so backends translate one thing. */
    private static List<List<DerivedFinderQuery.BoundPart>> bindPredicate(ParsedQuery parsed, Object[] args) {
        if (!parsed.isNarrowed()) {
            return List.of();
        }
        List<List<DerivedFinderQuery.BoundPart>> groups = new ArrayList<>();
        int cursor = parsed.firstBindable();
        int ordinal = 0;
        for (PartTree.OrPart orPart : parsed.predicate()) {
            List<DerivedFinderQuery.BoundPart> group = new ArrayList<>();
            for (Part part : orPart) {
                int n = part.getNumberOfArguments();
                List<Object> partArgs = new ArrayList<>(n);
                for (int i = 0; i < n; i++) {
                    partArgs.add(args[cursor++]);
                }
                boolean ignoreCase = part.shouldIgnoreCase() != Part.IgnoreCaseType.NEVER;
                group.add(new DerivedFinderQuery.BoundPart(part.getProperty(), part.getType(), ignoreCase,
                        partArgs, parsed.anyDiscriminatorFlags()[ordinal++]));
            }
            groups.add(List.copyOf(group));
        }
        return List.copyOf(groups);
    }

    private static IllegalArgumentException badShape(Method method, Class<?> entityType) {
        return new IllegalArgumentException(method + " on repository for " + entityType.getName()
                + " must have the shape findNearestBy<Field>Vector(EmbeddingVector reference, int limit)"
                + ": List<T> -- optionally narrowed (…VectorAnd<Predicate>(reference, limit, args…)),"
                + " returning List<Ranked<T>> to keep each hit's similarity, and/or taking a trailing"
                + " Pageable/Limit to page. The reference vector is always the first parameter, and the"
                + " int limit may only be omitted when a Pageable supplies one.");
    }

    private static IllegalArgumentException unsupported(Method method, Class<?> entityType) {
        return new IllegalArgumentException("Unsupported repository method " + method + " on repository for "
                + entityType.getName() + " -- JavAIRepository only supports the base CRUD contract plus "
                + "findNearestBy<Field>Vector/findNearestByVector/findNearestBySummaryVector/"
                + "findNearestByConcatenatedTextVector(EmbeddingVector, int); "
                + "arbitrary derived queries aren't part of Persistence Bridge's contract.");
    }
}
