package dev.xtrafe.javai.agent;

import dev.xtrafe.javai.annotations.JavAIVectorizable;
import dev.xtrafe.javai.annotations.Vectorize;
import dev.xtrafe.javai.runtime.EmbeddingVector;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.agent.builder.ResettableClassFileTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.field.FieldDescription;
import net.bytebuddy.description.field.FieldList;
import net.bytebuddy.description.modifier.Visibility;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.implementation.MethodCall;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;

import static net.bytebuddy.matcher.ElementMatchers.isAnnotatedWith;
import static net.bytebuddy.matcher.ElementMatchers.named;

/**
 * The Acceleration Substrate spike: installs a load-time ByteBuddy transformer that turns a single
 * conventional setter on a {@code @JavAIVectorizable} class into the woven version shown in
 * doc/spec/acceleration-substrate.md -- the original assignment runs untouched, {@code markDirty()}
 * fires on exit, and {@code vector()} recomputes lazily the next time it's read, never on the write
 * itself. Scope is deliberately narrow (one {@code @Vectorize} field, a conventional {@code setXxx}
 * setter, no {@code dependents()}/back-edge propagation across a graph): the goal is proving the
 * weaving mechanism itself, not building out javai-runtime's full contract.
 */
public final class JavAIWeaver {

    private static final Method IS_DIRTY_METHOD = staticMethod("isDirty", Object.class);
    private static final Method RECOMPUTE_COUNT_METHOD = staticMethod("recomputeCount", Object.class);
    private static final Method VECTOR_METHOD = staticMethod("vector", Object.class, String.class);

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
        FieldDescription.InDefinedShape vectorizeField = onlyVectorizeField(typeDescription);
        String setterName = setterNameFor(vectorizeField.getName());

        if (typeDescription.getDeclaredMethods().filter(named(setterName)).isEmpty()) {
            throw new IllegalStateException("Expected a conventional setter " + setterName
                    + "() for @Vectorize field " + vectorizeField.getName() + " on " + typeDescription);
        }

        return builder
                .defineField(WeaverRuntimeSupport.DIRTY_FIELD, boolean.class, Visibility.PRIVATE)
                .defineField(WeaverRuntimeSupport.VECTOR_FIELD, EmbeddingVector.class, Visibility.PRIVATE)
                .defineField(WeaverRuntimeSupport.RECOMPUTE_COUNT_FIELD, int.class, Visibility.PRIVATE)
                .defineMethod("isDirty", boolean.class, Visibility.PUBLIC)
                    .intercept(MethodCall.invoke(IS_DIRTY_METHOD).withThis())
                .defineMethod("recomputeCount", int.class, Visibility.PUBLIC)
                    .intercept(MethodCall.invoke(RECOMPUTE_COUNT_METHOD).withThis())
                .defineMethod("vector", EmbeddingVector.class, Visibility.PUBLIC)
                    .intercept(MethodCall.invoke(VECTOR_METHOD).withThis().with(vectorizeField.getName()))
                .visit(Advice.to(MarkDirtyAdvice.class).on(named(setterName)));
    }

    private static FieldDescription.InDefinedShape onlyVectorizeField(TypeDescription typeDescription) {
        FieldList<FieldDescription.InDefinedShape> vectorizeFields =
                typeDescription.getDeclaredFields().filter(isAnnotatedWith(Vectorize.class));
        if (vectorizeFields.size() != 1) {
            throw new IllegalStateException("This spike weaves exactly one @Vectorize field; "
                    + typeDescription + " has " + vectorizeFields.size());
        }
        return vectorizeFields.get(0);
    }

    private static String setterNameFor(String fieldName) {
        return "set" + Character.toUpperCase(fieldName.charAt(0)) + fieldName.substring(1);
    }

    private static Method staticMethod(String name, Class<?>... parameterTypes) {
        try {
            return WeaverRuntimeSupport.class.getMethod(name, parameterTypes);
        } catch (NoSuchMethodException e) {
            throw new ExceptionInInitializerError(e);
        }
    }
}
