package dev.xtrafe.javai.persistence;

import dev.xtrafe.javai.annotations.Modifying;
import dev.xtrafe.javai.annotations.Query;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.domain.SliceImpl;
import org.springframework.data.domain.Sort;
import org.springframework.data.repository.query.Param;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * A query a repository method carries itself, rather than spells in its name (OMI-398) -- the third and last
 * dispatch path, beside {@link DerivedQueryMethods}'s vector convention and {@link DerivedFinderQuery}'s
 * relational one.
 *
 * <p>It exists for the questions neither grammar can ask. A grouped aggregate is the plainest: {@code countBy…In}
 * returns one total, not one per id, so "how many likes does each of these thirty assets have" left N+1 counts,
 * counting in memory, or reaching around the repository to the {@code SessionFactory} as the only options.
 *
 * <p><b>Deliberately the same shape as {@link DerivedFinderQuery}</b>, which is what the parent ticket asked
 * for: this class owns the parse, the signature analysis (which parameters bind and which are
 * {@link Sort}/{@link Pageable}/{@link Limit}), the return-type adaptation and the argument binding; a
 * {@link RepositoryBackend} implements three primitives and never has to understand a return type. The
 * {@link DerivedFinderQuery.Constraints} record is reused outright rather than duplicated, so ordering and
 * windowing mean the same thing whichever path produced them.
 *
 * <h2>Validation happens in two moments, and this is a property rather than an accident</h2>
 *
 * Every other creation-time check in this module is pure reflection. Parsing a query is the first that is not:
 * it needs Hibernate's query engine, and building that engine freezes the entity set -- exactly what OMI-214
 * arranged should not happen when a repository is realized. So {@link #parse} does everything reflection can
 * (binding, return type, trailing parameters) at repository-creation time, and the backend validates the query
 * <em>text</em> as soon as it has an ORM to validate it with -- immediately if one exists, otherwise the
 * moment it is built. A malformed query still fails before any repository method runs; it simply cannot always
 * fail in the same microsecond the signature does.
 */
final class DeclaredQuery {

    /** What the declared return type asks us to make of the backend's raw result. */
    enum ReturnKind { LIST, OPTIONAL, SINGLE, STREAM, PAGE, SLICE, MODIFYING }

    /**
     * A named parameter ({@code :name}) or a positional one ({@code ?n}), resolved once from the method's own
     * parameter list. Positional numbering is 1-based, JPA's convention.
     */
    record Binding(String name, int position, int argumentIndex) {

        boolean isNamed() {
            return name != null;
        }
    }

    /** {@code :name}, but never the second colon of a Postgres {@code ::cast} and never a bare {@code :}. */
    private static final Pattern NAMED_PARAMETER = Pattern.compile("(?<![:\\w]):([A-Za-z_]\\w*)");

    /** {@code ?1}, {@code ?2}, … -- JPA positional parameters, as against a native {@code ?} placeholder. */
    private static final Pattern POSITIONAL_PARAMETER = Pattern.compile("(?<!\\?)\\?(\\d+)");

    private final Method method;
    private final Class<?> entityType;
    private final String queryText;
    private final String countQueryText;
    private final boolean nativeQuery;
    private final Modifying modifying;
    private final ReturnKind returnKind;
    private final Class<?> resultType;
    private final List<Binding> bindings;
    private final int sortParamIndex;
    private final int pageableParamIndex;
    private final int limitParamIndex;

    private DeclaredQuery(Method method, Class<?> entityType, String queryText, String countQueryText,
            boolean nativeQuery, Modifying modifying, ReturnKind returnKind, Class<?> resultType,
            List<Binding> bindings, int sortParamIndex, int pageableParamIndex, int limitParamIndex) {
        this.method = method;
        this.entityType = entityType;
        this.queryText = queryText;
        this.countQueryText = countQueryText;
        this.nativeQuery = nativeQuery;
        this.modifying = modifying;
        this.returnKind = returnKind;
        this.resultType = resultType;
        this.bindings = bindings;
        this.sortParamIndex = sortParamIndex;
        this.pageableParamIndex = pageableParamIndex;
        this.limitParamIndex = limitParamIndex;
    }

    /** Whether {@code method} carries a declared query at all -- checked <em>before</em> either derived-name
     *  grammar, so an annotated method may be named anything, including something that looks like a finder. */
    static boolean isDeclaredQuery(Method method) {
        return method.isAnnotationPresent(Query.class);
    }

    String queryText() {
        return queryText;
    }

    String countQueryText() {
        return countQueryText;
    }

    boolean isNative() {
        return nativeQuery;
    }

    boolean isModifying() {
        return modifying != null;
    }

    Modifying modifying() {
        return modifying;
    }

    ReturnKind returnKind() {
        return returnKind;
    }

    Class<?> resultType() {
        return resultType;
    }

    List<Binding> bindings() {
        return bindings;
    }

    Method method() {
        return method;
    }

    Class<?> entityType() {
        return entityType;
    }

    /** Whether the query's own result rows are this repository's entity -- which is what decides whether a
     *  dynamic {@link Sort} can be applied to it at all (there is no attribute to order a projection by). */
    boolean returnsEntities() {
        return resultType == entityType;
    }

    /**
     * Parses and validates everything about {@code method} that can be known without an ORM.
     *
     * @throws IllegalArgumentException with a message naming the method, for any signature this path cannot
     *                                  serve -- always at repository-creation time, never on first call
     */
    static DeclaredQuery parse(Method method, Class<?> entityType) {
        Query annotation = method.getAnnotation(Query.class);
        String text = annotation.value().trim();
        if (text.isEmpty()) {
            throw new IllegalArgumentException("@Query on " + method + " is empty.");
        }
        Modifying modifying = method.getAnnotation(Modifying.class);
        String countText = annotation.countQuery().trim();

        Parameter[] parameters = method.getParameters();
        int sortIndex = -1;
        int pageableIndex = -1;
        int limitIndex = -1;
        List<Integer> bindableIndexes = new ArrayList<>();
        for (int i = 0; i < parameters.length; i++) {
            Class<?> type = parameters[i].getType();
            if (Pageable.class.isAssignableFrom(type)) {
                pageableIndex = i;
            } else if (Sort.class.isAssignableFrom(type)) {
                sortIndex = i;
            } else if (Limit.class.isAssignableFrom(type)) {
                limitIndex = i;
            } else {
                bindableIndexes.add(i);
            }
        }
        for (int index : new int[] {sortIndex, pageableIndex, limitIndex}) {
            if (index >= 0 && index < bindableIndexes.size()) {
                throw new IllegalArgumentException("@Query method " + method + " must declare any "
                        + "Sort/Pageable/Limit parameter after all bindable parameters.");
            }
        }

        List<Binding> bindings = resolveBindings(method, text, bindableIndexes);
        ReturnKind returnKind = resolveReturnKind(method, modifying);
        Class<?> resultType = resolveResultType(method, returnKind, entityType);

        if (modifying != null) {
            if (sortIndex >= 0 || pageableIndex >= 0 || limitIndex >= 0) {
                throw new IllegalArgumentException("@Modifying method " + method + " cannot take a "
                        + "Sort/Pageable/Limit parameter -- a write has no ordering or window.");
            }
            if (!countText.isEmpty()) {
                throw new IllegalArgumentException("@Modifying method " + method + " declares a countQuery, "
                        + "which only a Page-returning read uses.");
            }
        }
        if (!countText.isEmpty() && returnKind != ReturnKind.PAGE) {
            throw new IllegalArgumentException("@Query method " + method + " declares a countQuery but does not "
                    + "return a Page, so nothing would ever run it.");
        }
        if (returnKind == ReturnKind.PAGE && countText.isEmpty()) {
            throw new IllegalArgumentException("@Query method " + method + " returns a Page, so it needs "
                    + "countQuery = \"…\". It is not derived from the query itself on purpose: counting an "
                    + "arbitrary select means understanding its projection, joins and grouping, and a rewriter "
                    + "that gets that wrong returns a plausible wrong number rather than failing. Return a "
                    + "Slice instead if the total is not actually needed -- it fetches one extra row rather "
                    + "than counting.");
        }
        if (!countText.isEmpty()) {
            // Bound with the same arguments, so it must ask for the same ones. Checked here rather than at
            // execution, where a mismatch would surface as a binding error against the wrong query.
            requireSameParameters(method, text, countText);
        }
        if (annotation.nativeQuery() && (sortIndex >= 0 || pageableIndex >= 0)) {
            requireNoDynamicSortForNative(method, sortIndex, pageableIndex);
        }
        if (sortIndex >= 0 && !annotation.nativeQuery() && resultType != entityType) {
            throw new IllegalArgumentException("@Query method " + method + " takes a Sort, but returns "
                    + resultType.getSimpleName() + " rather than " + entityType.getSimpleName() + " -- dynamic "
                    + "ordering is applied by attribute against the entity being selected, so there is nothing "
                    + "for it to order a projection by. Put the ordering in the query text.");
        }
        return new DeclaredQuery(method, entityType, text, countText, annotation.nativeQuery(), modifying,
                returnKind, resultType, bindings, sortIndex, pageableIndex, limitIndex);
    }

    /**
     * Matches the method's bindable parameters to the query's own placeholders.
     *
     * <p>Named ({@code @Param("x")} against {@code :x}) and positional ({@code ?1}) are both supported and
     * cannot be mixed, because a half-named signature has no reading that is obviously right. A named
     * parameter the query never mentions, or one the query mentions and the method never supplies, fails here
     * -- the "unbindable parameter" the parent ticket asks to be caught when the repository is realized.
     */
    private static List<Binding> resolveBindings(Method method, String text, List<Integer> bindableIndexes) {
        Parameter[] parameters = method.getParameters();
        Set<String> declared = new LinkedHashSet<>();
        boolean anyNamed = false;
        for (int index : bindableIndexes) {
            Param param = parameters[index].getAnnotation(Param.class);
            if (param != null) {
                anyNamed = true;
                if (!declared.add(param.value())) {
                    throw new IllegalArgumentException("@Query method " + method + " declares @Param(\""
                            + param.value() + "\") twice.");
                }
            }
        }
        Set<String> inQuery = namesIn(text);

        if (!anyNamed) {
            if (!inQuery.isEmpty()) {
                throw new IllegalArgumentException("@Query on " + method + " uses named parameters " + inQuery
                        + " but no parameter is annotated @Param "
                        + "(org.springframework.data.repository.query.Param).");
            }
            int highest = highestPositional(text);
            if (highest != bindableIndexes.size()) {
                throw new IllegalArgumentException("@Query on " + method + " uses positional parameters up to ?"
                        + highest + " but the method declares " + bindableIndexes.size()
                        + " bindable parameter(s).");
            }
            List<Binding> bindings = new ArrayList<>();
            for (int i = 0; i < bindableIndexes.size(); i++) {
                bindings.add(new Binding(null, i + 1, bindableIndexes.get(i)));
            }
            return List.copyOf(bindings);
        }

        List<Binding> bindings = new ArrayList<>();
        for (int index : bindableIndexes) {
            Param param = parameters[index].getAnnotation(Param.class);
            if (param == null) {
                throw new IllegalArgumentException("@Query method " + method + " mixes named and unnamed "
                        + "parameters -- parameter " + index + " (" + parameters[index].getType().getSimpleName()
                        + ") has no @Param. Annotate every bindable parameter, or use positional ?1/?2 for all "
                        + "of them.");
            }
            if (!inQuery.contains(param.value())) {
                throw new IllegalArgumentException("@Query method " + method + " binds @Param(\"" + param.value()
                        + "\") but the query never mentions :" + param.value() + " -- it names " + inQuery + ".");
            }
            bindings.add(new Binding(param.value(), -1, index));
        }
        for (String name : inQuery) {
            if (!declared.contains(name)) {
                throw new IllegalArgumentException("@Query on " + method + " uses :" + name
                        + " but no parameter is annotated @Param(\"" + name + "\") -- declared: " + declared + ".");
            }
        }
        return List.copyOf(bindings);
    }

    private static void requireSameParameters(Method method, String text, String countText) {
        Set<String> queryNames = namesIn(text);
        Set<String> countNames = namesIn(countText);
        if (!queryNames.equals(countNames) || highestPositional(text) != highestPositional(countText)) {
            throw new IllegalArgumentException("@Query method " + method + "'s countQuery must take the same "
                    + "parameters as the query itself -- both are bound from the same call. The query uses "
                    + queryNames + "/?" + highestPositional(text) + ", the count uses " + countNames + "/?"
                    + highestPositional(countText) + ".");
        }
    }

    private static void requireNoDynamicSortForNative(Method method, int sortIndex, int pageableIndex) {
        if (sortIndex >= 0) {
            throw new IllegalArgumentException("@Query(nativeQuery = true) on " + method + " cannot take a "
                    + "dynamic Sort: applying one would mean rewriting the SQL text, and a rewriter that "
                    + "misreads a statement produces a query that runs and answers wrongly. Write ORDER BY into "
                    + "the SQL, or use JPQL, where ordering is applied through the query model instead.");
        }
        // A Pageable is still accepted for its window, which needs no rewriting -- only its sort is refused,
        // and only if the caller actually supplies one (checked per call, since it is a runtime value).
    }

    private static Set<String> namesIn(String text) {
        Set<String> names = new LinkedHashSet<>();
        Matcher matcher = NAMED_PARAMETER.matcher(text);
        while (matcher.find()) {
            names.add(matcher.group(1));
        }
        return names;
    }

    private static int highestPositional(String text) {
        int highest = 0;
        Matcher matcher = POSITIONAL_PARAMETER.matcher(text);
        while (matcher.find()) {
            highest = Math.max(highest, Integer.parseInt(matcher.group(1)));
        }
        return highest;
    }

    private static ReturnKind resolveReturnKind(Method method, Modifying modifying) {
        Class<?> returnType = method.getReturnType();
        if (modifying != null) {
            if (returnType != void.class && returnType != Void.class && !isNumeric(returnType)) {
                throw new IllegalArgumentException("@Modifying method " + method + " must return void or the "
                        + "affected-row count (int/long); it returns " + returnType.getName() + ".");
            }
            return ReturnKind.MODIFYING;
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
        return ReturnKind.SINGLE;
    }

    /** What to ask the store for one row of: the element type of a collection/optional/stream return, or the
     *  return type itself for a single result. {@code Object[]} and record types are ordinary answers here --
     *  a tuple and a constructor expression respectively. */
    private static Class<?> resolveResultType(Method method, ReturnKind returnKind, Class<?> entityType) {
        if (returnKind == ReturnKind.MODIFYING) {
            return void.class;
        }
        if (returnKind == ReturnKind.SINGLE) {
            Class<?> returnType = method.getReturnType();
            return returnType.isPrimitive() ? boxed(returnType) : returnType;
        }
        Type generic = method.getGenericReturnType();
        if (generic instanceof ParameterizedType parameterized && parameterized.getActualTypeArguments().length == 1) {
            Type element = parameterized.getActualTypeArguments()[0];
            if (element instanceof Class<?> elementClass) {
                return elementClass;
            }
            if (element instanceof java.lang.reflect.GenericArrayType) {
                return Object[].class; // List<Object[]> -- the plain tuple return
            }
            if (element instanceof ParameterizedType nested && nested.getRawType() instanceof Class<?> raw) {
                return raw;
            }
        }
        throw new IllegalArgumentException("@Query method " + method + " must declare the element type of its "
                + "return (e.g. List<" + entityType.getSimpleName() + ">, List<Object[]>, List<SomeRecord>) -- "
                + "a raw or wildcard type gives nothing to map rows onto.");
    }

    private static Class<?> boxed(Class<?> primitive) {
        if (primitive == long.class) {
            return Long.class;
        }
        if (primitive == int.class) {
            return Integer.class;
        }
        if (primitive == boolean.class) {
            return Boolean.class;
        }
        if (primitive == double.class) {
            return Double.class;
        }
        if (primitive == float.class) {
            return Float.class;
        }
        if (primitive == short.class) {
            return Short.class;
        }
        if (primitive == byte.class) {
            return Byte.class;
        }
        return primitive;
    }

    private static boolean isNumeric(Class<?> type) {
        return type == long.class || type == Long.class || type == int.class || type == Integer.class;
    }

    /** Ordering + windowing for one call, merging any dynamic {@link Sort}/{@link Pageable}/{@link Limit}. A
     *  declared query carries its own static ordering in its text, so there is nothing to merge that with. */
    DerivedFinderQuery.Constraints resolveConstraints(Object[] args) {
        Sort sort = Sort.unsorted();
        Integer skip = null;
        Integer maxResults = null;
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
        if (sort.isSorted() && nativeQuery) {
            throw new IllegalArgumentException("@Query(nativeQuery = true) on " + method + " was given a sorted "
                    + "Pageable, which would mean rewriting the SQL text. Put ORDER BY in the SQL, page with an "
                    + "unsorted Pageable, or use JPQL.");
        }
        return new DerivedFinderQuery.Constraints(sort, skip, maxResults);
    }

    private Pageable pageable(Object[] args) {
        return pageableParamIndex >= 0 && args[pageableParamIndex] instanceof Pageable p ? p : null;
    }

    /** The single entry point {@link RepositoryInvocationHandler} calls -- the same shape
     *  {@link DerivedFinderQuery#execute} has, so all return-type knowledge stays out of every backend. */
    Object execute(RepositoryBackend backend, Class<?> entityTypeArg, Object[] args) {
        switch (returnKind) {
            case MODIFYING:
                return adaptModifying(backend.runDeclaredUpdate(entityTypeArg, this, args));
            case PAGE:
                return executePage(backend, entityTypeArg, args);
            case SLICE:
                return executeSlice(backend, entityTypeArg, args);
            case OPTIONAL:
            case SINGLE:
                return executeSingle(backend, entityTypeArg, args);
            case STREAM:
                return backend.runDeclaredQuery(entityTypeArg, this, args, resolveConstraints(args)).stream();
            case LIST:
            default:
                return backend.runDeclaredQuery(entityTypeArg, this, args, resolveConstraints(args));
        }
    }

    private Object adaptModifying(long affected) {
        Class<?> returnType = method.getReturnType();
        if (returnType == void.class || returnType == Void.class) {
            return null;
        }
        // Boxed explicitly, in separate statements. A ternary whose branches are int and long is promoted to
        // long and boxes to Long, so an int-returning method gets a Long and the proxy throws
        // ClassCastException on return -- which is exactly what happened here before a test caught it.
        if (returnType == int.class || returnType == Integer.class) {
            return Integer.valueOf(Math.toIntExact(affected));
        }
        return Long.valueOf(affected);
    }

    private Object executeSingle(RepositoryBackend backend, Class<?> entityTypeArg, Object[] args) {
        DerivedFinderQuery.Constraints constraints = resolveConstraints(args);
        if (constraints.maxResults() == null) {
            // Two, not one: enough to notice an ambiguous result and say so, without reading a whole table to
            // find out. The same discipline a single-result derived finder uses.
            constraints = new DerivedFinderQuery.Constraints(constraints.sort(), constraints.skip(), 2);
        }
        List<Object> results = backend.runDeclaredQuery(entityTypeArg, this, args, constraints);
        if (results.size() > 1) {
            throw new IllegalStateException("@Query method " + method + " returned " + results.size()
                    + " results but its return type expects at most one -- return a List/Optional, or narrow "
                    + "the query.");
        }
        Object single = results.isEmpty() ? null : results.get(0);
        return returnKind == ReturnKind.OPTIONAL ? Optional.ofNullable(single) : single;
    }

    private Object executePage(RepositoryBackend backend, Class<?> entityTypeArg, Object[] args) {
        Pageable pageable = pageable(args);
        List<Object> content = backend.runDeclaredQuery(entityTypeArg, this, args, resolveConstraints(args));
        long total = backend.runDeclaredCount(entityTypeArg, this, args);
        return new PageImpl<>(content, pageable != null ? pageable : Pageable.unpaged(), total);
    }

    private Object executeSlice(RepositoryBackend backend, Class<?> entityTypeArg, Object[] args) {
        Pageable pageable = pageable(args);
        DerivedFinderQuery.Constraints base = resolveConstraints(args);
        boolean paged = pageable != null && pageable.isPaged();
        Integer probeLimit = paged ? pageable.getPageSize() + 1 : base.maxResults();
        List<Object> fetched = backend.runDeclaredQuery(entityTypeArg, this,
                args, new DerivedFinderQuery.Constraints(base.sort(), base.skip(), probeLimit));
        boolean hasNext = paged && fetched.size() > pageable.getPageSize();
        List<Object> content = hasNext ? new ArrayList<>(fetched.subList(0, pageable.getPageSize())) : fetched;
        return new SliceImpl<>(content, pageable != null ? pageable : Pageable.unpaged(), hasNext);
    }
}
