package dev.xtrafe.javai.persistence;

import org.springframework.data.core.PropertyPath;
import org.springframework.data.core.PropertyReferenceException;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.domain.SliceImpl;
import org.springframework.data.domain.Sort;
import org.springframework.data.geo.Circle;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.Metrics;
import org.springframework.data.geo.Point;
import org.springframework.data.repository.query.parser.Part;
import org.springframework.data.repository.query.parser.PartTree;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * A parsed <em>ordinary</em> Spring-Data-style derived finder on a {@link JavAIRepository} -- the OMI-138
 * counterpart to {@link DerivedQueryMethods}, which handles the vector-specific {@code findNearestBy*}
 * convention. Where {@code DerivedQueryMethods} recognizes exactly one hand-written grammar,
 * {@code DerivedFinderQuery} delegates the whole name grammar to Spring Data's own
 * {@link PartTree}: {@code findBy}/{@code readBy}/{@code getBy}/{@code queryBy}/{@code countBy}/
 * {@code existsBy}/{@code deleteBy} with {@code And}/{@code Or}, the full operator vocabulary
 * ({@code GreaterThan}/{@code Like}/{@code In}/{@code Between}/{@code IsNull}/{@code IgnoreCase}/...),
 * static {@code OrderBy}, {@code Top}/{@code First} limiting, {@code Distinct}, and nested property
 * traversal ({@code findByProfileDisplayName}). {@code PartTree} resolves and validates every referenced
 * property against the entity type -- <b>by field, not requiring JavaBean accessors</b> (verified: a
 * getter-less {@code @Id}-only entity resolves fine) -- so an unknown property fails fast, at
 * repository-creation time, exactly like an invalid {@code findNearestBy*} does.
 *
 * <p>This class is deliberately backend-agnostic: it owns the parse, the method-signature analysis
 * (which trailing parameters are {@link Sort}/{@link Pageable}/{@link Limit} vs. bindable predicate
 * values), the return-type adaptation (list/optional/stream/page/slice/single/count/exists/delete), and
 * the argument-to-{@link Part} binding. Each {@link RepositoryBackend} only has to translate a flat
 * {@link BoundPart} predicate tree into its own query language (JPA Criteria / Cypher / Mongo filter) and
 * apply the resolved {@link Constraints}; it never has to understand method names, return types, or
 * {@code Pageable} at all. See {@link #execute} for the single entry point the invocation handler calls.
 */
final class DerivedFinderQuery {

    /** What the method's declared return type asks us to produce from the raw backend result. */
    enum ReturnKind { LIST, OPTIONAL, STREAM, PAGE, SLICE, SINGLE, COUNT, EXISTS, DELETE }

    /** One predicate atom with its arguments already bound from the call's actual parameters -- what a
     *  backend translates into a single native condition. {@code property} may be nested (walk it with
     *  {@link PropertyPath#getSegment()}/{@link PropertyPath#next()}); {@code arguments} has exactly
     *  {@link Part#getNumberOfArguments()} entries (0 for {@code IsNull}/{@code True}, 1 for most, 2 for
     *  {@code Between}).
     *
     *  <p>{@code anyDiscriminator} is set only by the {@code OfType} keyword (OMI-407): the property names an
     *  {@code @Any} field and the bound argument is a {@code Class}, so the condition is over the target's
     *  <em>discriminator</em> rather than over the target itself. Every other atom leaves it false, which is
     *  why the four-argument constructor below exists. */
    record BoundPart(PropertyPath property, Part.Type type, boolean ignoreCase, List<Object> arguments,
            boolean anyDiscriminator) {

        BoundPart(PropertyPath property, Part.Type type, boolean ignoreCase, List<Object> arguments) {
            this(property, type, ignoreCase, arguments, false);
        }
    }

    /** Ordering + windowing resolved for one call, merging the method name's static {@code OrderBy}/
     *  {@code Top}N with any dynamic {@link Sort}/{@link Pageable}/{@link Limit} argument. {@code skip}
     *  and {@code maxResults} are {@code null} when unbounded. */
    record Constraints(Sort sort, Integer skip, Integer maxResults) {
    }

    private final Method method;
    private final Class<?> entityType;
    private final PartTree partTree;
    private final ReturnKind returnKind;
    private final int bindableCount;
    private final int sortParamIndex;
    private final int pageableParamIndex;
    private final int limitParamIndex;
    private final boolean[] anyDiscriminatorFlags;

    private DerivedFinderQuery(Method method, Class<?> entityType, PartTree partTree, ReturnKind returnKind,
            int bindableCount, int sortParamIndex, int pageableParamIndex, int limitParamIndex,
            boolean[] anyDiscriminatorFlags) {
        this.method = method;
        this.entityType = entityType;
        this.partTree = partTree;
        this.returnKind = returnKind;
        this.bindableCount = bindableCount;
        this.sortParamIndex = sortParamIndex;
        this.pageableParamIndex = pageableParamIndex;
        this.limitParamIndex = limitParamIndex;
        this.anyDiscriminatorFlags = anyDiscriminatorFlags;
    }

    /** True for any method name Spring Data's {@link PartTree} recognizes as a derived query -- i.e.
     *  starting with one of the subject keywords ({@code find/read/get/query/count/exists/delete/...}). Used
     *  as the cheap pre-check before the fuller {@link #parse}; deliberately does not itself validate
     *  properties or signature (that's {@link #parse}'s job, so the failure is a clear
     *  {@code IllegalArgumentException} rather than this returning a bare {@code false} that would surface as
     *  the generic "unsupported method" error). */
    static boolean looksLikeDerivedFinder(Method method) {
        String name = method.getName();
        for (String prefix : SUBJECT_PREFIXES) {
            if (name.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private static final String[] SUBJECT_PREFIXES = {
            "findBy", "findDistinctBy", "findFirst", "findTop", "findAllBy",
            "readBy", "readDistinctBy", "readFirst", "readTop", "readAllBy",
            "getBy", "getDistinctBy", "getFirst", "getTop", "getAllBy",
            "queryBy", "queryDistinctBy", "queryFirst", "queryTop", "queryAllBy",
            "countBy", "countDistinctBy",
            "existsBy",
            "deleteBy", "removeBy"
    };

    // ---- the OfType keyword: a predicate over an @Any's discriminator (OMI-407) --------------------

    /**
     * The one keyword this grammar adds to {@link PartTree}'s own vocabulary.
     *
     * <p>{@code findByTargetOfType(MediaAsset.class)} asks "every row whose polymorphic {@code target} points
     * at a {@code MediaAsset}", regardless of <em>which</em> one -- the question that previously needed the
     * {@code @Any}'s discriminator column mapped a second time as a plain read-only property. {@code PartTree}
     * cannot be taught a keyword (its operator set is a closed enum), so the token is stripped before the tree
     * is built and re-attached to the resulting part afterwards.
     */
    static final String ANY_TYPE_KEYWORD = "OfType";

    /** A method name with every {@code <AnyField>OfType} reduced to {@code <AnyField>}, plus how many times
     *  each {@code @Any} property was stripped -- which is what {@link #anyDiscriminatorFlags} matches against
     *  the parsed parts. */
    record AnyTypeRewrite(String cleanName, java.util.Map<String, Integer> strippedCounts) {

        boolean isEmpty() {
            return strippedCounts.isEmpty();
        }
    }

    /**
     * Strips {@link #ANY_TYPE_KEYWORD} from {@code name} wherever it directly follows the name of an
     * {@code @Any} field on {@code entityType}.
     *
     * <p>Only an {@code @Any} field's own token is ever rewritten, and a real property literally named
     * {@code <field>OfType} wins over the keyword -- so the rewrite can never eat a name that means something
     * else. A residual {@code OfType} is left alone rather than diagnosed here: {@code PartTree} will fail to
     * resolve it and name the offending property itself, which is the better message.
     */
    static AnyTypeRewrite stripAnyTypeKeyword(String name, Class<?> entityType) {
        if (!name.contains(ANY_TYPE_KEYWORD)) {
            return new AnyTypeRewrite(name, java.util.Map.of());
        }
        List<Field> anyFields = EntityReflection.anyFields(entityType);
        StringBuilder clean = new StringBuilder();
        java.util.Map<String, Integer> stripped = new java.util.LinkedHashMap<>();
        int cursor = 0;
        for (int at = name.indexOf(ANY_TYPE_KEYWORD); at >= 0;
                at = name.indexOf(ANY_TYPE_KEYWORD, at + ANY_TYPE_KEYWORD.length())) {
            Field owner = longestAnyFieldEndingAt(anyFields, entityType, name, at);
            if (owner == null) {
                continue; // not our keyword here; PartTree will name the property it cannot resolve
            }
            clean.append(name, cursor, at);
            cursor = at + ANY_TYPE_KEYWORD.length();
            stripped.merge(owner.getName(), 1, Integer::sum);
        }
        clean.append(name.substring(cursor));
        return new AnyTypeRewrite(clean.toString(), stripped);
    }

    /**
     * Which {@code @Any} field an {@code OfType} at {@code at} belongs to -- the <b>longest</b> one whose
     * capitalized name ends exactly there.
     *
     * <p>Longest, not first, and this is the whole correctness of the rewrite. One {@code @Any} field's name
     * is very often a suffix of another's: an owner with both {@code lazyAny} and {@code summaryLazyAny} makes
     * {@code findBySummaryLazyAnyOfType} end in {@code LazyAnyOfType} too, so a scan that accepted any match
     * would strip on behalf of the wrong field, flag a part that does not exist, and report the whole method
     * as ambiguous. Looking at the character before the token cannot separate the two either -- it is a
     * lowercase letter in both {@code findBy|LazyAny} and {@code Summary|LazyAny}. Only the longest match is
     * right, and it is always unique, because two distinct field names cannot both end at the same index and
     * have the same length.
     */
    private static Field longestAnyFieldEndingAt(
            List<Field> anyFields, Class<?> entityType, String name, int at) {
        Field longest = null;
        for (Field candidate : anyFields) {
            String token = capitalize(candidate.getName());
            int start = at - token.length();
            if (start < 0 || !name.startsWith(token, start)) {
                continue;
            }
            // A real property literally named `<field>OfType` beats the keyword, so the rewrite can never eat
            // a name that means something else.
            if (hasFieldNamed(entityType, candidate.getName() + ANY_TYPE_KEYWORD)) {
                continue;
            }
            if (longest == null || candidate.getName().length() > longest.getName().length()) {
                longest = candidate;
            }
        }
        return longest;
    }

    /**
     * Which parsed parts, in {@link PartTree} iteration order, carry the stripped keyword.
     *
     * <p>Matched by <em>counting parts</em> rather than by re-tokenizing the method name, which is what makes
     * this exact instead of approximate: a property whose name merely contains another's cannot be miscounted,
     * because the count comes from the tree the parser itself produced. The one case it cannot resolve is a
     * single {@code @Any} property appearing more than once with the keyword on only some of them -- there is
     * genuinely nothing in the name saying which, so it is refused rather than guessed.
     */
    static boolean[] anyDiscriminatorFlags(PartTree partTree, AnyTypeRewrite rewrite, Object owner) {
        List<Part> parts = new ArrayList<>();
        for (PartTree.OrPart orPart : partTree) {
            for (Part part : orPart) {
                parts.add(part);
            }
        }
        boolean[] flags = new boolean[parts.size()];
        for (var stripped : rewrite.strippedCounts().entrySet()) {
            List<Integer> matching = new ArrayList<>();
            for (int i = 0; i < parts.size(); i++) {
                if (parts.get(i).getProperty().toDotPath().equals(stripped.getKey())) {
                    matching.add(i);
                }
            }
            if (matching.size() != stripped.getValue()) {
                throw new IllegalArgumentException(owner + " uses '" + stripped.getKey() + ANY_TYPE_KEYWORD
                        + "' but also names '" + stripped.getKey() + "' without it, and nothing in the method "
                        + "name says which predicate is which. Split it into two methods, or express it with "
                        + "@Query.");
            }
            for (int index : matching) {
                flags[index] = true;
            }
        }
        return flags;
    }

    /** Validates one {@code OfType} atom: it must sit on an {@code @Any} field at the root, use an operator a
     *  discriminator comparison has meaning for, and bind a {@code Class} (or a collection of them). */
    static void validateAnyDiscriminatorPart(Part part, Class<?> entityType, Class<?>[] boundTypes, Object owner) {
        String dotPath = part.getProperty().toDotPath();
        if (dotPath.contains(".") || !EntityReflection.isAny(EntityReflection.findField(entityType, dotPath))) {
            throw new IllegalArgumentException(owner + ": '" + ANY_TYPE_KEYWORD + "' applies to an @Any field on "
                    + entityType.getName() + " itself, but '" + dotPath + "' is not one. Known @Any fields: "
                    + EntityReflection.anyFields(entityType).stream().map(Field::getName).toList() + ".");
        }
        boolean collectionOperator = part.getType() == Part.Type.IN || part.getType() == Part.Type.NOT_IN;
        if (part.getType() != Part.Type.SIMPLE_PROPERTY && part.getType() != Part.Type.NEGATING_SIMPLE_PROPERTY
                && !collectionOperator) {
            throw new IllegalArgumentException(owner + ": '" + dotPath + ANY_TYPE_KEYWORD + "' compares a target's"
                    + " type, so " + part.getType() + " has no meaning for it -- use it bare (equality),"
                    + " with Not, or with In.");
        }
        for (Class<?> bound : boundTypes) {
            boolean acceptable = collectionOperator
                    ? java.util.Collection.class.isAssignableFrom(bound)
                    : Class.class.isAssignableFrom(bound);
            if (!acceptable) {
                throw new IllegalArgumentException(owner + ": '" + dotPath + ANY_TYPE_KEYWORD + "' binds "
                        + (collectionOperator ? "a Collection<Class<?>>" : "a Class<?>") + ", but the declared "
                        + "parameter is " + bound.getName() + ".");
            }
        }
    }

    /** Said only when the name still carries an unstripped {@code OfType} -- otherwise a plain unknown-property
     *  error would leave a caller who used the keyword on the wrong field with nothing to go on. */
    private static String anyTypeKeywordHint(AnyTypeRewrite rewrite, Class<?> entityType) {
        if (!rewrite.cleanName().contains(ANY_TYPE_KEYWORD)) {
            return "";
        }
        List<String> anyFields = EntityReflection.anyFields(entityType).stream().map(Field::getName).toList();
        return ". Note '" + ANY_TYPE_KEYWORD + "' is a keyword only directly after an @Any field's name"
                + (anyFields.isEmpty()
                        ? ", and " + entityType.getSimpleName() + " declares no @Any field at all."
                        : "; the @Any fields here are " + anyFields + ".");
    }

    private static boolean hasFieldNamed(Class<?> type, String fieldName) {
        try {
            EntityReflection.findField(type, fieldName);
            return true;
        } catch (IllegalStateException absent) {
            return false;
        }
    }

    private static String capitalize(String value) {
        return Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }

    /** Parses and fully validates {@code method} against {@code entityType}, throwing a clear
     *  {@code IllegalArgumentException} for an unknown property or a signature whose bindable-parameter
     *  count doesn't match the predicate's argument demand. Backend feasibility (e.g. whether a nested path
     *  is reachable on that specific store) is a separate check -- see
     *  {@link RepositoryBackend#validateDerivedQuery}. */
    static DerivedFinderQuery parse(Method method, Class<?> entityType) {
        AnyTypeRewrite rewrite = stripAnyTypeKeyword(method.getName(), entityType);
        PartTree partTree;
        try {
            partTree = new PartTree(rewrite.cleanName(), entityType);
        } catch (PropertyReferenceException e) {
            throw new IllegalArgumentException("Derived query method " + method + " references a property that "
                    + "does not exist on " + entityType.getName() + " -- " + e.getMessage()
                    + anyTypeKeywordHint(rewrite, entityType), e);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Cannot parse derived query method " + method + " on repository for "
                    + entityType.getName() + " -- " + e.getMessage(), e);
        }
        boolean[] anyDiscriminatorFlags = anyDiscriminatorFlags(partTree, rewrite, method);

        Class<?>[] paramTypes = method.getParameterTypes();
        int sortIndex = -1;
        int pageableIndex = -1;
        int limitIndex = -1;
        for (int i = 0; i < paramTypes.length; i++) {
            if (Pageable.class.isAssignableFrom(paramTypes[i])) {
                pageableIndex = i;
            } else if (Sort.class.isAssignableFrom(paramTypes[i])) {
                sortIndex = i;
            } else if (Limit.class.isAssignableFrom(paramTypes[i])) {
                limitIndex = i;
            }
        }
        int specialCount = (sortIndex >= 0 ? 1 : 0) + (pageableIndex >= 0 ? 1 : 0) + (limitIndex >= 0 ? 1 : 0);
        int bindableCount = paramTypes.length - specialCount;

        int demanded = 0;
        for (PartTree.OrPart orPart : partTree) {
            for (Part part : orPart) {
                demanded += effectiveArgumentCount(part);
            }
        }
        if (bindableCount != demanded) {
            throw new IllegalArgumentException("Derived query method " + method + " on repository for "
                    + entityType.getName() + " declares " + bindableCount + " bindable parameter(s) but its "
                    + "predicate needs " + demanded + " -- check the property/keyword count against the argument list.");
        }
        for (int index : new int[] {sortIndex, pageableIndex, limitIndex}) {
            if (index >= 0 && index < bindableCount) {
                throw new IllegalArgumentException("Derived query method " + method + " must declare any "
                        + "Sort/Pageable/Limit parameter after all bindable predicate parameters.");
            }
        }
        validateAnyDiscriminatorParts(method, entityType, partTree, anyDiscriminatorFlags, paramTypes);

        ReturnKind returnKind = resolveReturnKind(method, partTree, entityType);
        return new DerivedFinderQuery(method, entityType, partTree, returnKind, bindableCount, sortIndex,
                pageableIndex, limitIndex, anyDiscriminatorFlags);
    }

    /** Walks the parts alongside the declared parameter list so an {@code OfType} atom is checked against the
     *  parameter it will actually bind, at repository-creation time like every other signature check. */
    private static void validateAnyDiscriminatorParts(Method method, Class<?> entityType, PartTree partTree,
            boolean[] flags, Class<?>[] paramTypes) {
        int ordinal = 0;
        int cursor = 0;
        for (PartTree.OrPart orPart : partTree) {
            for (Part part : orPart) {
                int arity = effectiveArgumentCount(part);
                if (flags[ordinal]) {
                    validateAnyDiscriminatorPart(part, entityType,
                            Arrays.copyOfRange(paramTypes, cursor, cursor + arity), method);
                }
                cursor += arity;
                ordinal++;
            }
        }
    }

    private static ReturnKind resolveReturnKind(Method method, PartTree partTree, Class<?> entityType) {
        Class<?> returnType = method.getReturnType();
        if (partTree.isCountProjection()) {
            requireNumericReturn(method, returnType, "count");
            return ReturnKind.COUNT;
        }
        if (partTree.isExistsProjection()) {
            if (returnType != boolean.class && returnType != Boolean.class) {
                throw new IllegalArgumentException("exists-projection method " + method + " must return boolean.");
            }
            return ReturnKind.EXISTS;
        }
        if (partTree.isDelete()) {
            if (returnType != void.class && returnType != Void.class && !isNumeric(returnType)) {
                throw new IllegalArgumentException("delete method " + method + " must return void or a numeric "
                        + "deleted-count; returning the deleted entities is not supported in this phase.");
            }
            return ReturnKind.DELETE;
        }
        if (Optional.class.isAssignableFrom(returnType)) {
            return ReturnKind.OPTIONAL;
        }
        if (Stream.class.isAssignableFrom(returnType)) {
            return ReturnKind.STREAM;
        }
        if (Page.class.isAssignableFrom(returnType)) {
            return ReturnKind.PAGE;
        }
        if (Slice.class.isAssignableFrom(returnType)) {
            return ReturnKind.SLICE;
        }
        if (Iterable.class.isAssignableFrom(returnType)) {
            return ReturnKind.LIST;
        }
        // A bare entity type (nullable single result). Guard the obviously-wrong primitive returns.
        if (returnType.isPrimitive()) {
            throw new IllegalArgumentException("Derived finder " + method + " has an unsupported primitive return "
                    + "type for a single-result query on " + entityType.getName() + ".");
        }
        return ReturnKind.SINGLE;
    }

    private static void requireNumericReturn(Method method, Class<?> returnType, String kind) {
        if (!isNumeric(returnType)) {
            throw new IllegalArgumentException(kind + "-projection method " + method + " must return a numeric type "
                    + "(long/int/Long/Integer).");
        }
    }

    private static boolean isNumeric(Class<?> type) {
        return type == long.class || type == Long.class || type == int.class || type == Integer.class;
    }

    PartTree partTree() {
        return partTree;
    }

    ReturnKind returnKind() {
        return returnKind;
    }

    /** The bound OR-of-ANDs predicate tree, with each atom's arguments sliced out of {@code args} in
     *  method-name order. The outer list is OR-ed; each inner list is AND-ed. Empty (a single empty group,
     *  or no groups) for a predicate-less method like {@code findAllByOrderByCreatedAtDesc}. */
    List<List<BoundPart>> boundOrGroups(Object[] args) {
        Object[] bindables = bindableArguments(args);
        int cursor = 0;
        int ordinal = 0;
        List<List<BoundPart>> groups = new ArrayList<>();
        for (PartTree.OrPart orPart : partTree) {
            List<BoundPart> group = new ArrayList<>();
            for (Part part : orPart) {
                int n = effectiveArgumentCount(part);
                List<Object> partArgs = new ArrayList<>(n);
                for (int i = 0; i < n; i++) {
                    partArgs.add(bindables[cursor++]);
                }
                boolean ignoreCase = part.shouldIgnoreCase() != Part.IgnoreCaseType.NEVER;
                group.add(new BoundPart(part.getProperty(), part.getType(), ignoreCase, partArgs,
                        anyDiscriminatorFlags[ordinal++]));
            }
            groups.add(group);
        }
        return groups;
    }

    /** The parsed parts' {@code OfType} flags, in the same iteration order {@link #boundOrGroups} uses -- what
     *  a backend's creation-time feasibility check reads, since it sees the tree rather than a bound call. */
    boolean[] anyDiscriminatorFlags() {
        return anyDiscriminatorFlags;
    }

    /** How many method parameters a part actually binds. Matches {@link Part#getNumberOfArguments()} for
     *  everything except geo {@code Near}: PartTree counts only the {@code Point} (1), but the supported
     *  {@code findByLocationNear(Point, Distance)} convention also binds a trailing {@code Distance}, so it
     *  consumes 2. {@code Within} takes a single {@code Circle} (center + radius), so it stays at 1. */
    private static int effectiveArgumentCount(Part part) {
        return part.getType() == Part.Type.NEAR ? 2 : part.getNumberOfArguments();
    }

    /** A geo predicate reduced to a center + radius the backends translate uniformly: {@code Near} binds a
     *  {@code Point} + {@code Distance}, {@code Within} a {@code Circle} (its own center + radius). Longitude
     *  is {@code Point.getX()}, latitude {@code Point.getY()} (Spring's convention); radius is normalized to
     *  meters. */
    record GeoCircle(double longitude, double latitude, double radiusMeters) {
    }

    static GeoCircle geoCircle(BoundPart part) {
        if (part.type() == Part.Type.NEAR) {
            Point center = (Point) part.arguments().get(0);
            Distance distance = (Distance) part.arguments().get(1);
            return new GeoCircle(center.getX(), center.getY(), toMeters(distance));
        }
        if (part.type() == Part.Type.WITHIN) {
            Circle circle = (Circle) part.arguments().get(0);
            Point center = circle.getCenter();
            return new GeoCircle(center.getX(), center.getY(), toMeters(circle.getRadius()));
        }
        throw new IllegalArgumentException("Not a geo part: " + part.type());
    }

    private static double toMeters(Distance distance) {
        double value = distance.getValue();
        if (distance.getMetric() == Metrics.KILOMETERS) {
            return value * 1000.0;
        }
        if (distance.getMetric() == Metrics.MILES) {
            return value * 1609.344;
        }
        return value; // NEUTRAL or any custom metric: treat the value as already in meters.
    }

    /** Decomposition of a (possibly nested) property path at its first <em>to-many</em> segment -- the pivot
     *  the id-set-resolving backends (Postgres, Mongo) split on. {@code singularPrefix} is the dot-path of
     *  singular hops from the root down to {@code ownerType} (empty when the to-many is on the root itself);
     *  {@code collectionField} is the to-many field on {@code ownerType}; {@code memberType} its element/value
     *  type; {@code suffix} the remaining dot-path within a member (empty when the to-many field is the leaf,
     *  e.g. a {@code Collection}-typed {@code IsEmpty}). */
    record ToManySplit(String singularPrefix, Class<?> ownerType, String collectionField, Class<?> memberType,
            String suffix) {
    }

    /** Finds the first to-many segment in {@code dotPath} (walking singular hops from {@code rootType}), or
     *  empty if the whole path is singular -- letting a backend choose native navigation for a pure-singular
     *  path and id-set resolution for a to-many one. */
    static Optional<ToManySplit> firstToManySplit(Class<?> rootType, String dotPath) {
        String[] segments = dotPath.split("\\.");
        Class<?> owner = rootType;
        StringBuilder prefix = new StringBuilder();
        for (int i = 0; i < segments.length; i++) {
            Field field = EntityReflection.findField(owner, segments[i]);
            if (isToMany(field)) {
                String suffix = String.join(".", java.util.Arrays.asList(segments).subList(i + 1, segments.length));
                return Optional.of(new ToManySplit(
                        prefix.toString(), owner, segments[i], collectionMemberType(field), suffix));
            }
            prefix.append(prefix.length() == 0 ? "" : ".").append(segments[i]);
            owner = field.getType();
        }
        return Optional.empty();
    }

    static boolean isToMany(Field field) {
        Class<?> type = field.getType();
        return java.util.Collection.class.isAssignableFrom(type) || java.util.Map.class.isAssignableFrom(type);
    }

    /** The element type of a {@code Collection} field, or the value type of a {@code Map} field. */
    static Class<?> collectionMemberType(Field field) {
        Class<?> type = field.getType();
        int index = java.util.Map.class.isAssignableFrom(type) ? 1 : 0;
        if (field.getGenericType() instanceof java.lang.reflect.ParameterizedType parameterized) {
            java.lang.reflect.Type[] args = parameterized.getActualTypeArguments();
            if (index < args.length && args[index] instanceof Class<?> clazz) {
                return clazz;
            }
        }
        throw new IllegalArgumentException("Cannot resolve the element/value type of collection field " + field);
    }

    private Object[] bindableArguments(Object[] args) {
        Object[] out = new Object[bindableCount];
        if (args != null && bindableCount > 0) {
            System.arraycopy(args, 0, out, 0, bindableCount);
        }
        return out;
    }

    /** Merges the method name's static ordering/limit ({@code OrderBy}, {@code Top}N) with any dynamic
     *  {@link Sort}/{@link Pageable}/{@link Limit} argument for this call. A {@code Pageable} contributes
     *  both a window (offset+size) and, if present, a sort that augments the static one. */
    Constraints resolveConstraints(Object[] args) {
        Sort sort = partTree.getSort();
        Integer skip = null;
        Integer maxResults = partTree.getMaxResults();

        Pageable pageable = pageable(args);
        if (pageable != null && pageable.isPaged()) {
            skip = Math.toIntExact(pageable.getOffset());
            maxResults = pageable.getPageSize();
            if (pageable.getSort().isSorted()) {
                sort = sort.and(pageable.getSort());
            }
        }
        if (sortParamIndex >= 0 && args[sortParamIndex] instanceof Sort dynamic && dynamic.isSorted()) {
            sort = sort.and(dynamic);
        }
        if (limitParamIndex >= 0 && args[limitParamIndex] instanceof Limit limit && limit.isLimited()) {
            maxResults = limit.max();
        }
        return new Constraints(sort, skip, maxResults);
    }

    private Pageable pageable(Object[] args) {
        return pageableParamIndex >= 0 && args[pageableParamIndex] instanceof Pageable p ? p : null;
    }

    /** The single entry point {@link RepositoryInvocationHandler} calls: dispatches to the right
     *  {@link RepositoryBackend} primitive(s) for this query's {@link ReturnKind} and adapts the raw result
     *  to the method's declared return type. All return-type/paging knowledge lives here, never in a backend. */
    Object execute(RepositoryBackend backend, Class<?> entityTypeArg, Object[] args) {
        switch (returnKind) {
            case COUNT:
                return adaptCount(backend.countByDerivedQuery(entityTypeArg, this, args));
            case EXISTS:
                return backend.existsByDerivedQuery(entityTypeArg, this, args);
            case DELETE:
                long deleted = backend.deleteByDerivedQuery(entityTypeArg, this, args);
                return adaptDelete(deleted);
            case PAGE:
                return executePage(backend, entityTypeArg, args);
            case SLICE:
                return executeSlice(backend, entityTypeArg, args);
            case OPTIONAL:
            case SINGLE:
                return executeSingle(backend, entityTypeArg, args);
            case STREAM:
                return backend.findByDerivedQuery(entityTypeArg, this, args, resolveConstraints(args)).stream();
            case LIST:
            default:
                return backend.findByDerivedQuery(entityTypeArg, this, args, resolveConstraints(args));
        }
    }

    private Object adaptCount(long count) {
        return boxedCount(method.getReturnType(), count);
    }

    private Object adaptDelete(long deleted) {
        Class<?> returnType = method.getReturnType();
        if (returnType == void.class || returnType == Void.class) {
            return null;
        }
        return boxedCount(returnType, deleted);
    }

    /**
     * Boxes to whichever of {@code Integer}/{@code Long} the method actually declared.
     *
     * <p>Written as statements rather than a ternary deliberately. {@code cond ? Math.toIntExact(n) : n} looks
     * like it returns an {@code Integer} on the true branch, but binary numeric promotion widens both branches
     * to {@code long} and boxes the result to {@code Long} -- so an {@code int}-returning finder was handed a
     * {@code Long} and the repository proxy threw {@code ClassCastException} on return. Latent here until
     * OMI-398 wrote an {@code int}-returning method and hit it immediately.
     */
    private static Object boxedCount(Class<?> returnType, long value) {
        if (returnType == int.class || returnType == Integer.class) {
            return Integer.valueOf(Math.toIntExact(value));
        }
        return Long.valueOf(value);
    }

    private Object executeSingle(RepositoryBackend backend, Class<?> entityTypeArg, Object[] args) {
        Constraints constraints = resolveConstraints(args);
        // Cap at 2 when the caller placed no explicit Top/First/Limit, purely so a >1-match single-result
        // finder can report the ambiguity (matching Spring Data's own IncorrectResultSize behavior) without
        // loading an entire table to do so.
        if (constraints.maxResults() == null) {
            constraints = new Constraints(constraints.sort(), constraints.skip(), 2);
        }
        List<Object> results = backend.findByDerivedQuery(entityTypeArg, this, args, constraints);
        if (results.size() > 1) {
            throw new IllegalStateException("Derived finder " + method + " returned " + results.size()
                    + " results but its return type expects at most one -- use a List/Optional return, add a "
                    + "narrower predicate, or a Top1/First qualifier.");
        }
        Object single = results.isEmpty() ? null : results.get(0);
        return returnKind == ReturnKind.OPTIONAL ? Optional.ofNullable(single) : single;
    }

    private Object executePage(RepositoryBackend backend, Class<?> entityTypeArg, Object[] args) {
        Pageable pageable = pageable(args);
        Constraints constraints = resolveConstraints(args);
        List<Object> content = backend.findByDerivedQuery(entityTypeArg, this, args, constraints);
        long total = backend.countByDerivedQuery(entityTypeArg, this, args);
        return new PageImpl<>(content, pageable != null ? pageable : Pageable.unpaged(), total);
    }

    private Object executeSlice(RepositoryBackend backend, Class<?> entityTypeArg, Object[] args) {
        Pageable pageable = pageable(args);
        Constraints base = resolveConstraints(args);
        boolean paged = pageable != null && pageable.isPaged();
        // Fetch one extra to decide hasNext without a separate count query -- the Slice contract's whole
        // point vs. Page (no total-count round trip).
        Integer probeLimit = paged ? pageable.getPageSize() + 1 : base.maxResults();
        Constraints probe = new Constraints(base.sort(), base.skip(), probeLimit);
        List<Object> fetched = backend.findByDerivedQuery(entityTypeArg, this, args, probe);
        boolean hasNext = paged && fetched.size() > pageable.getPageSize();
        List<Object> content = hasNext ? new ArrayList<>(fetched.subList(0, pageable.getPageSize())) : fetched;
        return new SliceImpl<>(content, pageable != null ? pageable : Pageable.unpaged(), hasNext);
    }
}
