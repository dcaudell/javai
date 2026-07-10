package dev.xtrafe.javai.supervision;

import net.bytebuddy.asm.Advice;
import net.bytebuddy.implementation.bytecode.assign.Assigner;

import java.lang.reflect.Method;

/**
 * Inlined into every {@code @SyncSupervision}/{@code @AsyncSupervision}-annotated method: captures PRE at
 * entry and POST/EXCEPTION at exit (a single exit hook covers both, per {@link
 * Advice.OnMethodExit#onThrowable()} -- {@code thrown == null} means POST, non-null means EXCEPTION),
 * delegating all decision-making to {@link JavAISupervisionRuntime}. See that class's javadoc for why every
 * call here is cheap even when no pointcut was actually requested.
 */
class SupervisionMethodAdvice {

    @Advice.OnMethodEnter
    static void onEnter(
            @Advice.This(optional = true) Object self,
            @Advice.Origin Method method,
            @Advice.AllArguments(readOnly = false, typing = Assigner.Typing.DYNAMIC) Object[] arguments) {
        arguments = JavAISupervisionRuntime.dispatchPre(self, method, arguments);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    static void onExit(
            @Advice.This(optional = true) Object self,
            @Advice.Origin Method method,
            @Advice.Return(readOnly = false, typing = Assigner.Typing.DYNAMIC) Object returnValue,
            @Advice.Thrown(readOnly = false, typing = Assigner.Typing.DYNAMIC) Throwable thrown) {
        if (thrown == null) {
            returnValue = JavAISupervisionRuntime.dispatchPost(self, method, returnValue);
        } else {
            SupervisionEvent outcome = JavAISupervisionRuntime.dispatchException(self, method, thrown, returnValue);
            thrown = outcome.thrown();
            returnValue = outcome.returnValue();
        }
    }

    private SupervisionMethodAdvice() {
    }
}
