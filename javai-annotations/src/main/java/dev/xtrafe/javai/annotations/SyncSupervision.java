package dev.xtrafe.javai.annotations;

import java.lang.annotation.*;

/**
 * Marks a method or constructor as woven for <b>synchronous</b> Agentic Supervision at the given {@link
 * SupervisionPointcut}s: the calling thread blocks while every {@code SupervisionListener}
 * (javai-supervision) registered via {@code registerSyncListener} and scoped to this call runs, in
 * registration order, each getting real read-write access -- rewrite arguments, rewrite the return value,
 * replace or suppress the thrown exception. See doc/spec/agentic-supervision.md.
 *
 * <p>Stackable with {@link AsyncSupervision} on the same element, independently: a method can be
 * sync-supervised at some pointcuts, async-observed at others, both at the same pointcut (sync runs first,
 * async runs after, seeing whatever the sync tier already committed to), or neither.
 *
 * <p>Opt-in and sparse by design -- unlike {@code @JavAIVectorizable}, there is no blanket, class-level
 * form of this annotation. A synchronous listener sits in the critical path of every call it's scoped to;
 * only annotate what you actually intend to have observed or intervened on.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.CONSTRUCTOR})
public @interface SyncSupervision {

    SupervisionPointcut[] value() default {SupervisionPointcut.PRE, SupervisionPointcut.POST, SupervisionPointcut.EXCEPTION};
}
