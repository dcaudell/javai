package dev.xtrafe.javai.agent;

import dev.xtrafe.javai.annotations.JavAIVectorizable;
import dev.xtrafe.javai.annotations.Summary;
import dev.xtrafe.javai.annotations.Vectorize;
import dev.xtrafe.javai.runtime.DirtyTrackingSupport;
import dev.xtrafe.javai.runtime.EmbeddingVector;
import dev.xtrafe.javai.runtime.JavAIDirtyTracking;
import dev.xtrafe.javai.runtime.JavAIList;
import dev.xtrafe.javai.runtime.JavAIRuntime;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.agent.builder.ResettableClassFileTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.field.FieldDescription;
import net.bytebuddy.description.modifier.Visibility;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.implementation.MethodCall;

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
 * delegates to a static helper on {@code javai-runtime}'s {@link JavAIRuntime}, which is where the actual
 * dirty-tracking, embedding, and graph-walk mechanics live (see that class's javadoc for why). This class
 * only has to know: which fields are {@code @Vectorize}/{@code @Summary}, and that each has a conventional
 * {@code setXxx} setter to instrument.
 *
 * <p>Supersedes the earlier spike (a single-field, fake-embedding proof that the mechanism works at all).
 * Scope still deliberately excludes: non-conventional setters, and multiple annotated fields sharing one
 * setter -- those aren't part of doc/spec/vector-core.md's contract either.
 */
public final class JavAIWeaver {

    private static final Method MARK_FIELD_DIRTY = runtimeMethod("markFieldDirty", Object.class);
    private static final Method IS_FIELD_DIRTY = runtimeMethod("isFieldDirty", Object.class);
    private static final Method CLEAR_FIELD_DIRTY = runtimeMethod("clearFieldDirty", Object.class);
    private static final Method MARK_SUMMARY_DIRTY = runtimeMethod("markSummaryDirty", Object.class);
    private static final Method IS_SUMMARY_DIRTY = runtimeMethod("isSummaryDirty", Object.class);
    private static final Method CLEAR_SUMMARY_DIRTY = runtimeMethod("clearSummaryDirty", Object.class);
    private static final Method ADD_DEPENDENT = runtimeMethod("addDependent", Object.class, Object.class);
    private static final Method DEPENDENTS = runtimeMethod("dependents", Object.class);
    private static final Method VECTOR = runtimeMethod("vector", Object.class, String.class);
    private static final Method FIELD_VECTOR = runtimeMethod("fieldVector", Object.class, String.class);
    private static final Method SUMMARY_VECTOR =
            runtimeMethod("summaryVector", Object.class, String.class, String.class);
    private static final Method SIMILARITY_TO_VECTORIZABLE = runtimeMethod(
            "similarityToVectorizable", Object.class, String.class, dev.xtrafe.javai.runtime.JavAIVectorizable.class);
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
        Set<String> summaryFields = fieldNamesAnnotatedWith(typeDescription, Summary.class);
        String vectorizeFieldsCsv = String.join(",", vectorizeFields);
        String summaryFieldsCsv = String.join(",", summaryFields);

        DynamicType.Builder<?> result = builder
                .implement(dev.xtrafe.javai.runtime.JavAIVectorizable.class)
                .implement(JavAIDirtyTracking.class)
                .defineField(JavAIRuntime.STATE_FIELD, DirtyTrackingSupport.class, Visibility.PRIVATE)

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
                .defineMethod("fieldVector", EmbeddingVector.class, Visibility.PUBLIC)
                .withParameters(String.class)
                .intercept(MethodCall.invoke(FIELD_VECTOR).withThis().withArgument(0))
                .defineMethod("summaryVector", EmbeddingVector.class, Visibility.PUBLIC)
                .intercept(MethodCall.invoke(SUMMARY_VECTOR).withThis().with(summaryFieldsCsv).with(vectorizeFieldsCsv))
                .defineMethod("similarityTo", double.class, Visibility.PUBLIC)
                .withParameters(dev.xtrafe.javai.runtime.JavAIVectorizable.class)
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
            result = result.defineMethod(fieldName + "Vector", EmbeddingVector.class, Visibility.PUBLIC)
                    .intercept(MethodCall.invoke(FIELD_VECTOR).withThis().with(fieldName));
        }

        result = result.visit(Advice.to(ConstructorExitAdvice.class).on(isConstructor()));

        Set<String> annotatedFields = new LinkedHashSet<>();
        annotatedFields.addAll(vectorizeFields);
        annotatedFields.addAll(summaryFields);
        for (String fieldName : annotatedFields) {
            String setterName = setterNameFor(fieldName);
            if (typeDescription.getDeclaredMethods().filter(named(setterName)).isEmpty()) {
                // No conventional setter -- fine for a field that's only ever mutated through itself
                // (e.g. a @Summary collection field initialized inline: elements are added via
                // getItems().add(...), never through a setter). ConstructorExitAdvice plus the
                // collection's own dependents-tracking already cover that case without one.
                continue;
            }
            Class<?> advice = vectorizeFields.contains(fieldName)
                    ? VectorizeFieldSetterAdvice.class
                    : SummaryOnlyFieldSetterAdvice.class;
            result = result.visit(Advice.to(advice).on(named(setterName)));
        }

        return result;
    }

    private static Set<String> fieldNamesAnnotatedWith(TypeDescription typeDescription, Class<? extends Annotation> annotationType) {
        Set<String> names = new LinkedHashSet<>();
        for (FieldDescription.InDefinedShape field : typeDescription.getDeclaredFields().filter(isAnnotatedWith(annotationType))) {
            names.add(field.getName());
        }
        return names;
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
