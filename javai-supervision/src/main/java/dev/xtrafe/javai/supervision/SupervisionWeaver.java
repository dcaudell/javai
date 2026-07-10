package dev.xtrafe.javai.supervision;

import dev.xtrafe.javai.annotations.AsyncSupervision;
import dev.xtrafe.javai.annotations.SupervisionPointcut;
import dev.xtrafe.javai.annotations.SyncSupervision;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.agent.builder.ResettableClassFileTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.annotation.AnnotationDescription;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.utility.JavaModule;

import java.lang.System.Logger.Level;
import java.lang.instrument.Instrumentation;

import static net.bytebuddy.matcher.ElementMatchers.declaresMethod;
import static net.bytebuddy.matcher.ElementMatchers.isAnnotatedWith;
import static net.bytebuddy.matcher.ElementMatchers.isConstructor;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;

/**
 * The Agentic Supervision weaver: installs a load-time ByteBuddy transformer that wires {@link
 * SupervisionMethodAdvice}/{@link SupervisionConstructorAdvice} onto every method or constructor annotated
 * {@code @SyncSupervision} and/or {@code @AsyncSupervision}, per doc/spec/agentic-supervision.md. Pure
 * bytecode-wiring, deliberately with no algorithmic logic of its own -- all dispatch decisions (which
 * pointcuts were requested, which listeners are scoped to the call, sync-then-async ordering) live in
 * {@link JavAISupervisionRuntime}, exactly the discipline {@code javai-substrate}'s {@code JavAIWeaver}
 * already established for Acceleration Substrate.
 *
 * <p>Selection is method/constructor-scoped, not type-scoped: unlike {@code JavAIWeaver}'s {@code
 * .type(isAnnotatedWith(JavAIVectorizable.class))}, a type here is only a transform candidate if it
 * <em>declares</em> a matching method or constructor -- there is deliberately no class-level "everything in
 * this class is supervised" form (see doc/spec/agentic-supervision.md's "Why opt-in, and why sparse").
 *
 * <p><b>{@link SupervisionPointcut#EXCEPTION} on a constructor is rejected at weave time by throwing from
 * this class's own transform step.</b> The JVM verifier does not allow an exception handler to span a
 * constructor's mandatory {@code super()}/{@code this()} call, so Byte Buddy cannot attach the {@code
 * onThrowable} exit advice EXCEPTION needs to any constructor -- see {@link SupervisionConstructorAdvice}'s
 * javadoc for the full explanation, including why this project's AoP predecessor never hit this (its
 * mechanism never installed a real exception handler at all). Note the actual failure mode this produces,
 * confirmed empirically: {@code AgentBuilder}'s default error handling does not fail class loading when a
 * transform throws -- it logs (via the error listener installed below) and falls back to leaving that
 * <em>entire type</em> unwoven (not just the offending constructor's EXCEPTION request; PRE/POST on the
 * same or other members of that type are skipped too). That is a deliberately conservative default (a
 * broken weaver should not be able to crash class loading), so this class doesn't fight it -- it installs a
 * listener that logs any such failure loudly instead of leaving it silent.
 */
public final class SupervisionWeaver {

    private static final System.Logger LOG = System.getLogger(SupervisionWeaver.class.getName());

    private SupervisionWeaver() {
    }

    public static ResettableClassFileTransformer install(Instrumentation instrumentation) {
        ElementMatcher.Junction<MethodDescription> annotated =
                isAnnotatedWith(SyncSupervision.class).or(isAnnotatedWith(AsyncSupervision.class));
        return new AgentBuilder.Default()
                .with(new AgentBuilder.Listener.Adapter() {
                    @Override
                    public void onError(String typeName, ClassLoader classLoader, JavaModule module, boolean loaded, Throwable throwable) {
                        LOG.log(Level.ERROR, "Failed to weave " + typeName + " for Agentic Supervision; "
                                + "that type will run entirely unwoven.", throwable);
                    }
                })
                .type(declaresMethod(annotated))
                .transform((builder, typeDescription, classLoader, module, protectionDomain) ->
                        weave(builder, typeDescription, annotated))
                .installOn(instrumentation);
    }

    private static DynamicType.Builder<?> weave(
            DynamicType.Builder<?> builder, TypeDescription typeDescription, ElementMatcher.Junction<MethodDescription> annotated) {
        for (MethodDescription.InDefinedShape constructor :
                typeDescription.getDeclaredMethods().filter(isConstructor().and(annotated))) {
            if (requestsException(constructor)) {
                throw new IllegalStateException(
                        "SupervisionPointcut.EXCEPTION is not supported on constructors (a JVM restriction: "
                                + "an exception handler cannot span a constructor's super()/this() call) -- "
                                + "remove EXCEPTION from " + constructor);
            }
        }
        return builder
                .visit(Advice.to(SupervisionMethodAdvice.class).on(isMethod().and(annotated)))
                .visit(Advice.to(SupervisionConstructorAdvice.class).on(isConstructor().and(annotated)));
    }

    private static boolean requestsException(MethodDescription.InDefinedShape executable) {
        AnnotationDescription.Loadable<SyncSupervision> sync = executable.getDeclaredAnnotations().ofType(SyncSupervision.class);
        if (sync != null && contains(sync.load().value(), SupervisionPointcut.EXCEPTION)) {
            return true;
        }
        AnnotationDescription.Loadable<AsyncSupervision> async = executable.getDeclaredAnnotations().ofType(AsyncSupervision.class);
        return async != null && contains(async.load().value(), SupervisionPointcut.EXCEPTION);
    }

    private static boolean contains(SupervisionPointcut[] pointcuts, SupervisionPointcut pointcut) {
        for (SupervisionPointcut candidate : pointcuts) {
            if (candidate == pointcut) {
                return true;
            }
        }
        return false;
    }
}
