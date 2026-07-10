/**
 * Agentic Supervision: lets a {@code @SyncSupervision}/{@code @AsyncSupervision}-woven method or
 * constructor be observed, and optionally intervened on, by registered listeners at three moments -- PRE
 * (before the body runs), POST (after a normal return), EXCEPTION (after a throw). See
 * doc/spec/agentic-supervision.md for the full design; this note covers what's Phase 0 scope in this
 * package specifically and why it's shaped the way it is.
 *
 * <p><b>Public contract:</b> {@link dev.xtrafe.javai.supervision.SupervisionEvent} (the event/"object
 * bucket" every listener receives), {@link dev.xtrafe.javai.supervision.SyncSupervisionListener} (blocking,
 * read-write), {@link dev.xtrafe.javai.supervision.AsyncSupervisionListener} (fire-and-forget,
 * observation-only). Annotations ({@code @SyncSupervision}, {@code @AsyncSupervision}, {@code
 * SupervisionPointcut}) live in {@code javai-annotations}, same as every other annotation in this project.
 *
 * <p><b>The two pieces that make the contract real, both implemented:</b>
 * <ul>
 *   <li>{@link dev.xtrafe.javai.supervision.JavAISupervisionRuntime} -- the static facade Advice calls
 *       into: {@code registerSyncListener}/{@code registerAsyncListener} (plus matching unregister
 *       methods) and the dispatch logic itself. Runs sync listeners on the calling thread, in registration
 *       order, before dispatching async listeners onto a virtual-thread-per-task executor, fire-and-forget,
 *       each with its own defensive snapshot of the event (see that class's javadoc for why a shared
 *       mutable event across concurrent async listeners would be a real data race, not just a documented
 *       "mutations are discarded" abstraction).
 *   <li>{@link dev.xtrafe.javai.supervision.SupervisionWeaver} -- the ByteBuddy {@code AgentBuilder}
 *       installer, selecting methods/constructors annotated {@code @SyncSupervision}/{@code
 *       @AsyncSupervision} (method/constructor-level selection, not the type-level selection {@code
 *       javai-substrate}'s {@code JavAIWeaver} uses) and wiring {@link
 *       dev.xtrafe.javai.supervision.SupervisionMethodAdvice}/{@link
 *       dev.xtrafe.javai.supervision.SupervisionConstructorAdvice} at the join point. Follows {@code
 *       JavAIWeaver}'s established discipline: the weaver and its Advice classes do no algorithmic logic of
 *       their own, they only capture the join point and delegate.
 * </ul>
 *
 * <p><b>A real improvement over this project's AoP lineage, confirmed by test:</b> EXCEPTION on a
 * <em>method</em> fires for any exception leaving the method, including one propagated from a call it
 * makes -- not just a literal {@code throw} statement in the method's own body, which is all the ASM-based
 * predecessor could ever see (it hooked {@code ATHROW} opcodes directly rather than installing a real
 * exception handler). See {@link dev.xtrafe.javai.supervision.SupervisionMethodAdvice}'s javadoc.
 *
 * <p><b>A real, JVM-imposed limitation on constructors, also confirmed by test, not assumed:</b> the JVM
 * verifier will not allow an exception handler to span a constructor's mandatory {@code super()}/{@code
 * this()} call, so Byte Buddy refuses to attach EXCEPTION's {@code onThrowable} exit advice to any
 * constructor at all. {@link dev.xtrafe.javai.supervision.SupervisionWeaver} rejects {@code
 * SupervisionPointcut#EXCEPTION} on a constructor at weave time rather than leaving it a silent no-op; see
 * that class's and {@link dev.xtrafe.javai.supervision.SupervisionConstructorAdvice}'s javadoc for the full
 * explanation, including the actual observed failure mode ({@code AgentBuilder} logs and leaves the whole
 * type unwoven, rather than failing class loading). Separately, and also empirically confirmed: a
 * constructor's PRE dispatch always observes {@code instance() == null} -- Byte Buddy's entry advice
 * categorically refuses to bind {@code this} on a constructor, the same restriction it applies to a static
 * method, regardless of where the super/this call actually completes in the bytecode.
 *
 * <p><b>Hard rule: this module's own classes must never carry {@code @SyncSupervision}/{@code
 * @AsyncSupervision}.</b> The weaving selection is opt-in (annotation-scoped, not blanket), so this is
 * satisfied automatically as long as nobody annotates the supervision runtime itself -- but do not do that.
 * Advice on advice is an infinite loop, not a subtle bug: every dispatch would itself be a supervised call,
 * which would dispatch again, forever. (This project's AoP lineage solved the same problem with a
 * classloader-level package denylist, since its weaving was blanket rather than opt-in; that mechanism
 * doesn't exist here because it isn't needed -- opt-in selection means there is no denylist to maintain or
 * forget to update.)
 *
 * <p><b>Not yet designed, deliberately deferred:</b> a depth/budget guard for cascading async reactions (an
 * async listener's side effect triggering another supervised call, which triggers another async listener,
 * ...). Worth a real answer before this goes past a Phase 0 spike; not blocking the spike itself. See
 * doc/spec/agentic-supervision.md's open-questions note.
 */
package dev.xtrafe.javai.supervision;
