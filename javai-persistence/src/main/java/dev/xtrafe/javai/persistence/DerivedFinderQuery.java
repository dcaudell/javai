package dev.xtrafe.javai.persistence;

import org.springframework.data.core.PropertyPath;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.domain.SliceImpl;
import org.springframework.data.domain.Sort;
import org.springframework.data.core.PropertyReferenceException;
import org.springframework.data.repository.query.parser.Part;
import org.springframework.data.repository.query.parser.PartTree;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
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
     *  {@code Between}). */
    record BoundPart(PropertyPath property, Part.Type type, boolean ignoreCase, List<Object> arguments) {
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

    private DerivedFinderQuery(Method method, Class<?> entityType, PartTree partTree, ReturnKind returnKind,
            int bindableCount, int sortParamIndex, int pageableParamIndex, int limitParamIndex) {
        this.method = method;
        this.entityType = entityType;
        this.partTree = partTree;
        this.returnKind = returnKind;
        this.bindableCount = bindableCount;
        this.sortParamIndex = sortParamIndex;
        this.pageableParamIndex = pageableParamIndex;
        this.limitParamIndex = limitParamIndex;
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

    /** Parses and fully validates {@code method} against {@code entityType}, throwing a clear
     *  {@code IllegalArgumentException} for an unknown property or a signature whose bindable-parameter
     *  count doesn't match the predicate's argument demand. Backend feasibility (e.g. whether a nested path
     *  is reachable on that specific store) is a separate check -- see
     *  {@link RepositoryBackend#validateDerivedQuery}. */
    static DerivedFinderQuery parse(Method method, Class<?> entityType) {
        PartTree partTree;
        try {
            partTree = new PartTree(method.getName(), entityType);
        } catch (PropertyReferenceException e) {
            throw new IllegalArgumentException("Derived query method " + method + " references a property that "
                    + "does not exist on " + entityType.getName() + " -- " + e.getMessage(), e);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Cannot parse derived query method " + method + " on repository for "
                    + entityType.getName() + " -- " + e.getMessage(), e);
        }

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
                demanded += part.getNumberOfArguments();
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

        ReturnKind returnKind = resolveReturnKind(method, partTree, entityType);
        return new DerivedFinderQuery(
                method, entityType, partTree, returnKind, bindableCount, sortIndex, pageableIndex, limitIndex);
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

    /** Operators no backend translates in this phase: geo ({@code Near}/{@code Within}), raw {@code Regex}
     *  (each store spells it differently), and the collection-emptiness family ({@code IsEmpty}/{@code IsNotEmpty}/
     *  {@code Exists}) -- the latter would filter on a to-many association, which every backend stores
     *  out-of-band (Postgres {@code javai_collection_members}, Neo4j relationships, Mongo reference pointers)
     *  rather than as a filterable column, tied to the same collection-membership follow-up as nested
     *  collection traversal. */
    private static final Set<Part.Type> UNSUPPORTED_TYPES = EnumSet.of(
            Part.Type.NEAR, Part.Type.WITHIN, Part.Type.REGEX,
            Part.Type.IS_EMPTY, Part.Type.IS_NOT_EMPTY, Part.Type.EXISTS);

    /** Shared feasibility guard every backend calls from its own {@link RepositoryBackend#validateDerivedQuery}:
     *  rejects any operator not translatable on any backend in this phase (see {@link #UNSUPPORTED_TYPES}),
     *  at repository-creation time. Backends layer their own store-specific path checks on top. */
    void assertCoreOperatorsOnly() {
        for (Part part : partTree.getParts()) {
            if (UNSUPPORTED_TYPES.contains(part.getType())) {
                throw new IllegalArgumentException("Derived finder " + method + " uses the '" + part.getType()
                        + "' operator, which JavAIRepository does not support in this phase (geo/regex/collection-"
                        + "emptiness operators). Property: " + part.getProperty().toDotPath() + ".");
            }
        }
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
        List<List<BoundPart>> groups = new ArrayList<>();
        for (PartTree.OrPart orPart : partTree) {
            List<BoundPart> group = new ArrayList<>();
            for (Part part : orPart) {
                int n = part.getNumberOfArguments();
                List<Object> partArgs = new ArrayList<>(n);
                for (int i = 0; i < n; i++) {
                    partArgs.add(bindables[cursor++]);
                }
                boolean ignoreCase = part.shouldIgnoreCase() != Part.IgnoreCaseType.NEVER;
                group.add(new BoundPart(part.getProperty(), part.getType(), ignoreCase, partArgs));
            }
            groups.add(group);
        }
        return groups;
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
        Class<?> returnType = method.getReturnType();
        return returnType == int.class || returnType == Integer.class ? Math.toIntExact(count) : count;
    }

    private Object adaptDelete(long deleted) {
        Class<?> returnType = method.getReturnType();
        if (returnType == void.class || returnType == Void.class) {
            return null;
        }
        return returnType == int.class || returnType == Integer.class ? Math.toIntExact(deleted) : deleted;
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
