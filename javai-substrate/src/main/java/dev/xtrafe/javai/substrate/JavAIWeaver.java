package dev.xtrafe.javai.substrate;

import dev.xtrafe.javai.annotations.JavAIVectorizable;
import dev.xtrafe.javai.annotations.Summary;
import dev.xtrafe.javai.annotations.Vectorize;
import dev.xtrafe.javai.annotations.VectorizeIgnore;
import dev.xtrafe.javai.model.JavAIList;
import dev.xtrafe.javai.model.JavAIRuntime;
import dev.xtrafe.javai.vector.DirtyTrackingSupport;
import dev.xtrafe.javai.vector.EmbeddingVector;
import dev.xtrafe.javai.vector.JavAIDirtyTracking;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.agent.builder.ResettableClassFileTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.field.FieldDescription;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.modifier.FieldPersistence;
import net.bytebuddy.description.modifier.Visibility;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.implementation.MethodCall;
import net.bytebuddy.implementation.SuperMethodCall;

import java.lang.annotation.Annotation;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.util.LinkedHashSet;
import java.util.Set;

import static net.bytebuddy.matcher.ElementMatchers.isAnnotatedWith;
import static net.bytebuddy.matcher.ElementMatchers.isConstructor;
import static net.bytebuddy.matcher.ElementMatchers.named;

/**
 * The Acceleration Substrate weaver: installs a load-time ByteBuddy transformer that turns any
 * {@code @JavAIVectorizable} class into a real implementation of {@code JavAIVectorizable} +
 * {@code JavAIDirtyTracking}, per doc/spec/vector-core.md and doc/spec/acceleration-substrate.md.
 *
 * <p>Pure bytecode-wiring, deliberately with no algorithmic logic of its own -- every synthesized method
 * delegates to a static helper on {@code javai-model}'s {@link JavAIRuntime}, which is where the actual
 * dirty-tracking, embedding, and graph-walk mechanics live (see that class's javadoc for why). This class
 * only has to know: which fields are {@code @Vectorize}/{@code @Summary}, and that each has a conventional
 * {@code setXxx} setter to instrument.
 *
 * <p>Supersedes the earlier spike (a single-field, fake-embedding proof that the mechanism works at all).
 * Scope still deliberately excludes: non-conventional setters and multiple annotated fields sharing one
 * setter.
 *
 * <p><b>{@code @Vectorize}/{@code @Summary} fields declared on a superclass</b> (the common shape: a plain,
 * unannotated base class holding shared fields, with only the concrete leaf class carrying
 * {@code @JavAIVectorizable}) are supported, but not by instrumenting the ancestor's setter directly --
 * Advice can only instrument bytecode physically present in the class actually being transformed, and an
 * inherited setter's bytecode lives in the ancestor's own class file, not this one. Instead, when the
 * setter for such a field isn't declared on the woven class itself, this weaver synthesizes a real
 * override -- {@code public void setXxx(T value) { super.setXxx(value); }} via {@link SuperMethodCall} --
 * so the setter now has bytecode of its own in the transformed class, and the usual {@link Advice} wiring
 * attaches to that exactly as it would to a genuinely-declared setter. This is ordinary Java override
 * dispatch, not a workaround with a hole in it: because instance methods resolve virtually against the
 * runtime type, calling {@code setXxx} on a leaf instance reaches the synthesized override even through a
 * reference statically typed as the ancestor (an explicit cast does not defeat it). What genuinely can't be
 * covered, and is skipped silently (same as an annotated field with no setter at all): a {@code final}
 * ancestor setter (cannot be overridden -- a JVM-level rule, not this weaver's choice); a {@code private}
 * ancestor setter (private methods aren't inherited in Java's model, so there is nothing to override); a
 * package-private ancestor setter whose declaring class is in a different package than the woven class; and,
 * regardless of any of the above, a field ever mutated by direct assignment rather than through its setter
 * (e.g. from within the ancestor's own constructor) -- no setter call occurs there for Advice to observe,
 * woven or not. {@link JavAIRuntime}'s read-side reflection (the {@code query()} graph walk, generic
 * dependency wiring) has no bytecode-locality restriction to begin with and already walks the full class
 * hierarchy directly.
 */
public final class JavAIWeaver {

    /** Every method name this weaver synthesizes onto a woven class, checked against each
     *  {@code @Vectorize} field's own {@code fieldName + "Vector"} accessor name -- see the collision check
     *  in {@link #weave} for why this exists. */
    private static final Set<String> RESERVED_METHOD_NAMES = Set.of(
            "markFieldDirty", "isFieldDirty", "clearFieldDirty", "markSummaryDirty", "isSummaryDirty",
            "clearSummaryDirty", "addDependent", "dependents", "vector", "concatenatedTextVector", "fieldVector",
            "summaryVector", "similarityTo", "query");

    private static final Method MARK_FIELD_DIRTY = runtimeMethod("markFieldDirty", Object.class);
    private static final Method IS_FIELD_DIRTY = runtimeMethod("isFieldDirty", Object.class);
    private static final Method CLEAR_FIELD_DIRTY = runtimeMethod("clearFieldDirty", Object.class);
    private static final Method MARK_SUMMARY_DIRTY = runtimeMethod("markSummaryDirty", Object.class);
    private static final Method IS_SUMMARY_DIRTY = runtimeMethod("isSummaryDirty", Object.class);
    private static final Method CLEAR_SUMMARY_DIRTY = runtimeMethod("clearSummaryDirty", Object.class);
    private static final Method ADD_DEPENDENT = runtimeMethod("addDependent", Object.class, Object.class);
    private static final Method DEPENDENTS = runtimeMethod("dependents", Object.class);
    private static final Method VECTOR = runtimeMethod("vector", Object.class, String.class);
    private static final Method CONCATENATED_TEXT_VECTOR = runtimeMethod("concatenatedTextVector", Object.class, String.class);
    private static final Method FIELD_VECTOR = runtimeMethod("fieldVector", Object.class, String.class);
    private static final Method SUMMARY_VECTOR =
            runtimeMethod("summaryVector", Object.class, String.class, String.class);
    private static final Method SIMILARITY_TO_VECTORIZABLE = runtimeMethod(
            "similarityToVectorizable", Object.class, String.class, dev.xtrafe.javai.model.JavAIVectorizable.class);
    private static final Method SIMILARITY_TO_REFERENCE =
            runtimeMethod("similarityToReference", Object.class, String.class, EmbeddingVector.class);
    private static final Method QUERY =
            runtimeMethod("query", Object.class, EmbeddingVector.class, Class.class, int.class);

    private JavAIWeaver() {
    }

    public static ResettableClassFileTransformer install(Instrumentation instrumentation) {
        return new AgentBuilder.Default()
                .type(isAnnotatedWith(JavAIVectorizable.class))
                .transform((builder, typeDescription, classLoader, module, protectionDomain) ->
                        weave(builder, typeDescription))
                .installOn(instrumentation);
    }

    private static DynamicType.Builder<?> weave(DynamicType.Builder<?> builder, TypeDescription typeDescription) {
        Set<String> vectorizeFields = fieldNamesAnnotatedWith(typeDescription, Vectorize.class);
        // @VectorizeIgnore wins over @Vectorize if a field somehow carries both -- an explicit "exclude"
        // signal should never be silently overridden by an "include" one on the same field.
        vectorizeFields.removeAll(fieldNamesAnnotatedWith(typeDescription, VectorizeIgnore.class));
        Set<String> summaryFields = fieldNamesAnnotatedWith(typeDescription, Summary.class);
        String vectorizeFieldsCsv = String.join(",", vectorizeFields);
        String summaryFieldsCsv = String.join(",", summaryFields);

        DynamicType.Builder<?> result = builder
                .implement(dev.xtrafe.javai.model.JavAIVectorizable.class)
                .implement(JavAIDirtyTracking.class)
                // FieldPersistence.TRANSIENT (the JVM modifier, not just JPA's @Transient annotation) --
                // so that a class that's both @Entity and @JavAIVectorizable doesn't need an annotation on
                // a field it never sees in source: Hibernate's default field-access mapping already skips
                // the `transient` keyword automatically. See javai-persistence's EntityReflection/backends,
                // which independently also skip any "$javai$"-prefixed field by name for the same reason.
                .defineField(JavAIRuntime.STATE_FIELD, DirtyTrackingSupport.class, Visibility.PRIVATE, FieldPersistence.TRANSIENT)

                .defineMethod("markFieldDirty", void.class, Visibility.PUBLIC)
                .intercept(MethodCall.invoke(MARK_FIELD_DIRTY).withThis())
                .defineMethod("isFieldDirty", boolean.class, Visibility.PUBLIC)
                .intercept(MethodCall.invoke(IS_FIELD_DIRTY).withThis())
                .defineMethod("clearFieldDirty", void.class, Visibility.PUBLIC)
                .intercept(MethodCall.invoke(CLEAR_FIELD_DIRTY).withThis())
                .defineMethod("markSummaryDirty", void.class, Visibility.PUBLIC)
                .intercept(MethodCall.invoke(MARK_SUMMARY_DIRTY).withThis())
                .defineMethod("isSummaryDirty", boolean.class, Visibility.PUBLIC)
                .intercept(MethodCall.invoke(IS_SUMMARY_DIRTY).withThis())
                .defineMethod("clearSummaryDirty", void.class, Visibility.PUBLIC)
                .intercept(MethodCall.invoke(CLEAR_SUMMARY_DIRTY).withThis())
                .defineMethod("addDependent", void.class, Visibility.PUBLIC)
                .withParameters(Object.class)
                .intercept(MethodCall.invoke(ADD_DEPENDENT).withThis().withArgument(0))
                .defineMethod("dependents", Iterable.class, Visibility.PUBLIC)
                .intercept(MethodCall.invoke(DEPENDENTS).withThis())

                .defineMethod("vector", EmbeddingVector.class, Visibility.PUBLIC)
                .intercept(MethodCall.invoke(VECTOR).withThis().with(vectorizeFieldsCsv))
                .defineMethod("concatenatedTextVector", EmbeddingVector.class, Visibility.PUBLIC)
                .intercept(MethodCall.invoke(CONCATENATED_TEXT_VECTOR).withThis().with(vectorizeFieldsCsv))
                .defineMethod("fieldVector", EmbeddingVector.class, Visibility.PUBLIC)
                .withParameters(String.class)
                .intercept(MethodCall.invoke(FIELD_VECTOR).withThis().withArgument(0))
                .defineMethod("summaryVector", EmbeddingVector.class, Visibility.PUBLIC)
                .intercept(MethodCall.invoke(SUMMARY_VECTOR).withThis().with(summaryFieldsCsv).with(vectorizeFieldsCsv))
                .defineMethod("similarityTo", double.class, Visibility.PUBLIC)
                .withParameters(dev.xtrafe.javai.model.JavAIVectorizable.class)
                .intercept(MethodCall.invoke(SIMILARITY_TO_VECTORIZABLE).withThis().with(vectorizeFieldsCsv).withArgument(0))
                .defineMethod("similarityTo", double.class, Visibility.PUBLIC)
                .withParameters(EmbeddingVector.class)
                .intercept(MethodCall.invoke(SIMILARITY_TO_REFERENCE).withThis().with(vectorizeFieldsCsv).withArgument(0))
                .defineMethod("query", JavAIList.class, Visibility.PUBLIC)
                .withParameters(EmbeddingVector.class, Class.class)
                .intercept(MethodCall.invoke(QUERY).withThis().withArgument(0).withArgument(1).with(Integer.MAX_VALUE))
                .defineMethod("query", JavAIList.class, Visibility.PUBLIC)
                .withParameters(EmbeddingVector.class, Class.class, int.class)
                .intercept(MethodCall.invoke(QUERY).withThis().withArgument(0).withArgument(1).withArgument(2));

        for (String fieldName : vectorizeFields) {
            String accessorName = fieldName + "Vector";
            if (RESERVED_METHOD_NAMES.contains(accessorName)) {
                // A @Vectorize field named e.g. "text" or "concatenatedText" would produce a per-field
                // accessor ("textVector"/"concatenatedTextVector") that collides with one of this interface's
                // own reserved method names -- confirmed empirically to otherwise fail silently at weave
                // time (ByteBuddy throws IllegalStateException: "Duplicate method signature", which
                // AgentBuilder's default configuration swallows, leaving the class entirely unwoven rather
                // than surfacing the real cause). Fail loud and specific instead.
                throw new IllegalStateException("@Vectorize field \"" + fieldName + "\" on "
                        + typeDescription.getName() + " produces a per-field accessor (" + accessorName
                        + ") that collides with a reserved JavAIVectorizable method name -- rename the field.");
            }
            result = result.defineMethod(accessorName, EmbeddingVector.class, Visibility.PUBLIC)
                    .intercept(MethodCall.invoke(FIELD_VECTOR).withThis().with(fieldName));
        }

        result = result.visit(Advice.to(ConstructorExitAdvice.class).on(isConstructor()));

        Set<String> annotatedFields = new LinkedHashSet<>();
        annotatedFields.addAll(vectorizeFields);
        annotatedFields.addAll(summaryFields);
        for (String fieldName : annotatedFields) {
            String setterName = setterNameFor(fieldName);
            if (typeDescription.getDeclaredMethods().filter(named(setterName)).isEmpty()) {
                // Not declared directly on the woven class -- the field itself may still be inherited
                // (allowed, see class javadoc) even though its setter isn't declared here. Look for a
                // setter further up the hierarchy that this class can legally override.
                MethodDescription.InDefinedShape inherited = findOverridableInheritedSetter(typeDescription, setterName);
                if (inherited == null) {
                    // Either no setter exists anywhere reachable (fine -- e.g. a @Summary collection field
                    // mutated only via getItems().add(...), never a setter; ConstructorExitAdvice plus the
                    // collection's own dependents-tracking already cover that case), or the nearest one
                    // found can't legally be overridden (final/private/cross-package package-private -- see
                    // class javadoc). Same permissive skip either way: no accessor fires for this setter,
                    // but nothing else about the woven class is affected.
                    continue;
                }
                // Synthesize `public <returnType> setXxx(<paramType> value) { super.setXxx(value); }` --
                // ordinary override dispatch, so Advice attached below to *this* class's own copy of the
                // method fires on every call, including through an ancestor-typed reference.
                result = result.defineMethod(setterName, inherited.getReturnType(), Visibility.PUBLIC)
                        .withParameters(inherited.getParameters().asTypeList())
                        .intercept(SuperMethodCall.INSTANCE);
            }
            if (vectorizeFields.contains(fieldName)) {
                // FieldName bound explicitly, per setter, at weave time -- see VectorizeFieldSetterAdvice's
                // own javadoc for why this replaced an earlier @Advice.Origin-based approach.
                result = result.visit(Advice.withCustomMapping()
                        .bind(FieldName.class, fieldName)
                        .to(VectorizeFieldSetterAdvice.class)
                        .on(named(setterName)));
            } else {
                result = result.visit(Advice.to(SummaryOnlyFieldSetterAdvice.class).on(named(setterName)));
            }
        }

        return result;
    }

    private static Set<String> fieldNamesAnnotatedWith(TypeDescription typeDescription, Class<? extends Annotation> annotationType) {
        Set<String> names = new LinkedHashSet<>();
        for (TypeDescription current = typeDescription; current != null && !current.represents(Object.class);
                current = current.getSuperClass() == null ? null : current.getSuperClass().asErasure()) {
            for (FieldDescription.InDefinedShape field : current.getDeclaredFields().filter(isAnnotatedWith(annotationType))) {
                names.add(field.getName());
            }
        }
        return names;
    }

    /**
     * Walks from {@code typeDescription}'s superclass upward looking for a declared {@code setterName}
     * method with exactly one parameter. Returns the nearest one found only if this class can legally
     * override it (not {@code final}, not {@code private}, and not package-private in a different package);
     * returns {@code null} if none is found, or if the nearest one found can't be overridden -- deliberately
     * not continuing past it to search for some more distant, unrelated shadowed method.
     */
    private static MethodDescription.InDefinedShape findOverridableInheritedSetter(
            TypeDescription typeDescription, String setterName) {
        TypeDescription.Generic superClass = typeDescription.getSuperClass();
        for (TypeDescription current = superClass == null ? null : superClass.asErasure();
                current != null && !current.represents(Object.class);
                current = current.getSuperClass() == null ? null : current.getSuperClass().asErasure()) {
            for (MethodDescription.InDefinedShape candidate : current.getDeclaredMethods().filter(named(setterName))) {
                if (candidate.isStatic() || candidate.getParameters().size() != 1) {
                    continue;
                }
                return isOverridableFrom(candidate, typeDescription) ? candidate : null;
            }
        }
        return null;
    }

    private static boolean isOverridableFrom(MethodDescription.InDefinedShape method, TypeDescription subclass) {
        if (method.isFinal() || method.isPrivate()) {
            return false;
        }
        if (method.isPackagePrivate()) {
            return method.getDeclaringType().getPackage().equals(subclass.getPackage());
        }
        return true;
    }

    private static String setterNameFor(String fieldName) {
        return "set" + Character.toUpperCase(fieldName.charAt(0)) + fieldName.substring(1);
    }

    private static Method runtimeMethod(String name, Class<?>... parameterTypes) {
        try {
            return JavAIRuntime.class.getMethod(name, parameterTypes);
        } catch (NoSuchMethodException e) {
            throw new ExceptionInInitializerError(e);
        }
    }
}
