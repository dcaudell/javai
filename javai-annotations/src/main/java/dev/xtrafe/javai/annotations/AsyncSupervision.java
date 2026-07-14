package dev.xtrafe.javai.annotations;

import java.lang.annotation.*;

/**
 * Marks a method or constructor as woven for <b>asynchronous</b> Agentic Supervision at the given {@link
 * SupervisionPointcut}s: every {@code SupervisionListener} (javai-supervision) registered via {@code
 * registerAsyncListener} and scoped to this call is dispatched fire-and-forget -- observation only, no
 * rewrite rights over arguments, return value, or thrown exception, and no blocking of the calling thread
 * regardless of how long a listener takes. This is deliberately where "trigger other work in response to
 * this call" belongs: an async
 * listener is free to have arbitrary side effects (call other supervised methods, kick off unrelated tasks,
 * write elsewhere in the object graph) -- it just can't reach back and change the call that triggered it.
 * See doc/spec/agentic-supervision.md.
 *
 * <p>Stackable with {@link SyncSupervision} -- see that annotation's javadoc for the combined-ordering
 * rule (sync tier commits first, async tier observes what the sync tier committed to).
 *
 * <p>Opt-in and sparse by design, same rationale as {@link SyncSupervision}.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.CONSTRUCTOR})
public @interface AsyncSupervision {

    SupervisionPointcut[] value() default {SupervisionPointcut.PRE, SupervisionPointcut.POST, SupervisionPointcut.EXCEPTION};
}
