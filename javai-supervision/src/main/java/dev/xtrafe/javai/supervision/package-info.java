/**
 * Agentic Supervision: lets a {@code @SyncSupervision}/{@code @AsyncSupervision}-woven method or
 * constructor be observed, and optionally intervened on, by registered listeners at three moments -- PRE
 * (before the body runs), POST (after a normal return), EXCEPTION (after a throw). See
 * doc/spec/agentic-supervision.md for the full design; this note covers what's Phase 0 scope in this
 * package specifically and why it's shaped the way it is.
 *
 * <p><b>Public contract, defined here:</b> {@link dev.xtrafe.javai.supervision.SupervisionEvent} (the
 * event/"object bucket" every listener receives), {@link dev.xtrafe.javai.supervision.SyncSupervisionListener}
 * (blocking, read-write), {@link dev.xtrafe.javai.supervision.AsyncSupervisionListener} (fire-and-forget,
 * observation-only). Annotations ({@code @SyncSupervision}, {@code @AsyncSupervision}, {@code
 * SupervisionPointcut}) live in {@code javai-annotations}, same as every other annotation in this project.
 *
 * <p><b>Not yet implemented -- the two pieces still needed to make the contract above real:</b>
 * <ul>
 *   <li>{@code JavAISupervisionRuntime} (or similarly named static facade, matching {@code JavAIRuntime}'s
 *       shape in javai-model): {@code registerSyncListener}/{@code registerAsyncListener} plus the
 *       actual dispatch logic a weaver's Advice classes call into -- runs sync listeners on the calling
 *       thread in registration order, then dispatches async listeners onto a
 *       virtual-thread-per-task executor, fire-and-forget, per doc/spec/agentic-supervision.md.
 *   <li>{@code SupervisionWeaver}: a ByteBuddy {@code AgentBuilder} installer selecting methods/
 *       constructors annotated {@code @SyncSupervision}/{@code @AsyncSupervision} (method/constructor-level
 *       selection, not the type-level selection {@code javai-substrate}'s {@code JavAIWeaver} uses) and
 *       wiring {@code Advice} at each requested {@link dev.xtrafe.javai.annotations.SupervisionPointcut}
 *       to build a {@link dev.xtrafe.javai.supervision.SupervisionEvent} and forward it to the runtime
 *       facade above. Follow {@code JavAIWeaver}'s established discipline: the weaver and its Advice
 *       classes do no algorithmic logic of their own, they only capture the join point and delegate.
 * </ul>
 *
 * <p><b>Why this module has its own weaver instead of extending {@code javai-substrate}'s:</b> {@code
 * javai-substrate} depends on {@code javai-annotations} + {@code javai-vector} + {@code javai-model} and is
 * deliberately the first thing proven in Phase 0 ("prove the weaving mechanism itself... before building
 * out javai-vector/javai-model and javai-collections in full" -- CLAUDE.md). If this module's weaving lived
 * in {@code javai-substrate} instead, and an Agentic Listener implementation eventually wants to depend on
 * {@code javai-completion} (to actually call an LLM) or {@code javai-collections}/{@code javai-vector}/
 * {@code javai-model} (to ground its decision in the object graph), {@code javai-substrate} would end up
 * needing those too -- pulling the one module meant to be provable earliest and cheapest all the way
 * downstream of the rest of the reactor. Keeping Agentic Supervision's weaver independent (depending only
 * on {@code javai-annotations}, same tier as {@code javai-vector}) means it's a second, parallel,
 * equally-early risk spike instead of a blocker on the first one. Nothing here needs to know about
 * vectorization at all -- see {@link
 * dev.xtrafe.javai.supervision.SupervisionEvent}'s javadoc: RAG scope over the object graph, if a listener
 * wants any at all, is entirely that listener implementation's business, not this module's.
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
 * <p><b>Also not yet designed, deliberately deferred:</b> a depth/budget guard for cascading async
 * reactions (an async listener's side effect triggering another supervised call, which triggers another
 * async listener, ...). Worth a real answer before this goes past a Phase 0 spike; not blocking the spike
 * itself. See doc/spec/agentic-supervision.md's open-questions note.
 */
package dev.xtrafe.javai.supervision;
