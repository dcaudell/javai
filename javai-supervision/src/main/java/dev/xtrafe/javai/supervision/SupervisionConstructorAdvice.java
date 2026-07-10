package dev.xtrafe.javai.supervision;

import net.bytebuddy.asm.Advice;
import net.bytebuddy.implementation.bytecode.assign.Assigner;

import java.lang.reflect.Constructor;

/**
 * The constructor counterpart to {@link SupervisionMethodAdvice}: PRE and POST (normal completion) only --
 * see {@link SupervisionWeaver} for why EXCEPTION is rejected at weave time for any constructor rather than
 * silently unsupported here. Bound via {@link Advice.Origin} to a {@link Constructor} rather than a {@link
 * java.lang.reflect.Method} (Byte Buddy doesn't support one Advice class binding {@code Origin} to the
 * common {@code Executable} supertype, hence the duplication rather than one shared class).
 *
 * <p><b>PRE always observes {@code instance() == null} for a constructor</b> -- confirmed empirically, not
 * merely inferred: Byte Buddy's entry advice categorically refuses to bind {@code @Advice.This} on a
 * constructor at all ({@code IllegalStateException: Cannot map this reference for static method or
 * constructor start}), the same restriction it applies to a static method's entry advice, regardless of
 * where in the constructor body the super/this call actually completes. {@link
 * JavAISupervisionRuntime#dispatchPre}'s existing null-instance fallback (used for static methods) already
 * covers this: {@code supportedClass()} scoping falls back to the constructor's declaring class. This is a
 * real, if narrow, capability gap versus the ASM-based predecessor (which could load an uninitialized
 * {@code this} reference at constructor PRE, since raw {@code ALOAD 0} is legal there at the JVM level even
 * before {@code super()} completes) -- but it's a Byte Buddy Advice-API restriction, not a design choice
 * made here. A PRE listener can still rewrite arguments used later in the constructor body (field
 * assignments, etc.) via {@link Advice.AllArguments}, which has no such restriction.
 *
 * <p>There is no {@code onThrowable} exit variant here, unlike {@link SupervisionMethodAdvice}: the JVM
 * verifier does not permit an exception handler whose range spans a constructor's {@code super()}/{@code
 * this()} call (the object is not yet definitely initialized within that range), so Byte Buddy refuses to
 * attach {@code @Advice.OnMethodExit(onThrowable = ...)} to any constructor at all -- also confirmed
 * empirically. This is strictly a capability the ASM-based predecessor also lacked in the general case: it
 * only ever hooked literal {@code ATHROW} opcodes physically present in a method/constructor's own
 * bytecode, never installed a real exception-handler range -- so it never hit this restriction, but also
 * never caught an exception *propagated* from a call the constructor makes (see {@link
 * SupervisionMethodAdvice}'s javadoc for why this module's method-level EXCEPTION support deliberately
 * improves on that). Reproducing even the predecessor's narrower, literal-throw-only mechanism for
 * constructors specifically was judged not worth the added complexity for this spike, so {@link
 * SupervisionPointcut#EXCEPTION} is declared as a hard mismatch for constructors.
 *
 * @see dev.xtrafe.javai.annotations.SupervisionPointcut
 */
class SupervisionConstructorAdvice {

    @Advice.OnMethodEnter
    static void onEnter(
            @Advice.Origin Constructor<?> constructor,
            @Advice.AllArguments(readOnly = false, typing = Assigner.Typing.DYNAMIC) Object[] arguments) {
        arguments = JavAISupervisionRuntime.dispatchPre(null, constructor, arguments);
    }

    @Advice.OnMethodExit
    static void onExit(@Advice.This Object self, @Advice.Origin Constructor<?> constructor) {
        JavAISupervisionRuntime.dispatchPost(self, constructor, null);
    }

    private SupervisionConstructorAdvice() {
    }
}
