# javai-supervision

Extension area: **Agentic Supervision**. Whitepaper: §5.7 (new — added after Phase 0 scaffolding was
already underway; see the "Origin" note below). Full detail:
[`doc/spec/agentic-supervision.md`](../doc/spec/agentic-supervision.md).

Depends only on `javai-annotations` + ByteBuddy — deliberately the same early, low-risk tier as
`javai-agent`, not downstream of Vector Core, Vector Collections, or Completion Fabric. Lets a
`@SyncSupervision`/`@AsyncSupervision`-annotated method or constructor be observed, and optionally
intervened on, by registered listeners at three moments: PRE (before the body runs), POST (after a normal
return), EXCEPTION (after a throw).

## Origin

This module's design is a from-scratch reimplementation, on ByteBuddy, of an ASM-based AoP framework from
an earlier project (`com.xtrafe.vyper2.aspect`, 2009) — six pointcuts
(`CONSTRUCTOR_PRE`/`POST`/`EXCEPTION`, `METHOD_PRE`/`POST`/`EXCEPTION`), a pub/sub `Advisor`/
`PointcutEventHandler` model, class-level `@Managed` opt-in plus per-method `@Ignore` opt-out. This module
keeps the pub/sub model and the "PRE/POST/EXCEPTION with read-write access" core idea, but collapses six
pointcuts to three (a constructor is just another `java.lang.reflect.Executable` — which one fired is
already recoverable from the event, no separate constructor-flavored pointcuts needed) and adds a second,
orthogonal axis the original didn't have: every pointcut can be woven for **synchronous** delivery
(blocking, read-write — the direct analog of the original), **asynchronous** delivery (fire-and-forget,
observation-only), or both.

The motivating use case — "Agentic Supervision" — is an LLM-backed listener that can observe or intervene
on a call, grounded in RAG over the object graph Vector Core/Collections already makes queryable. That use
case is *why* this module exists, but it isn't part of the module's own dependency footprint: see
"What's actually implemented" below.

## Primitives

| Element | Kind | Purpose |
|---|---|---|
| `SupervisionPointcut` | Enum (`javai-annotations`) | `PRE` / `POST` / `EXCEPTION` — shared by both annotations below |
| `@SyncSupervision(SupervisionPointcut...)` | Annotation (`javai-annotations`), method/constructor | Blocking, read-write weaving at the given pointcuts |
| `@AsyncSupervision(SupervisionPointcut...)` | Annotation (`javai-annotations`), method/constructor | Fire-and-forget, observation-only weaving at the given pointcuts |
| `SupervisionEvent` | Value type (this module) | The "object bucket": pointcut, instance, `Executable`, mutable arguments/returnValue/thrown |
| `SyncSupervisionListener` | Interface (this module) | `onPre`/`onPost`/`onException`, each free to mutate the `SupervisionEvent` it receives; default no-ops |
| `AsyncSupervisionListener` | Interface (this module) | Same three methods, observation-only — mutations are discarded |
| `JavAISupervisionRuntime` (planned) | Static facade | Listener registration + the dispatch logic the weaver's Advice calls into |
| `SupervisionWeaver` (planned) | ByteBuddy `AgentBuilder` installer | Weaves `@SyncSupervision`/`@AsyncSupervision`-annotated methods/constructors |

## The sync/async model

At a given pointcut on a given call, if both a sync and an async listener are registered, **sync always
resolves first**: every registered `SyncSupervisionListener` scoped to the call runs, on the calling
thread, in registration order, each seeing (and able to further mutate) whatever the previous one left
behind. Once the sync tier is done, the event — now reflecting whatever the sync tier committed to — is
handed to every registered `AsyncSupervisionListener` scoped to the call, dispatched onto a
virtual-thread-per-task executor, fire-and-forget. The calling thread never waits on the async tier,
regardless of how long a listener takes.

This is a deliberate answer to a real problem: "the AoP allows an entire separate program to run" at a
join point (an LLM agent loop, potentially with tool calls, could run for a long time) — blocking on that
unconditionally would make method calls prohibitively expensive. Splitting into a blocking tier with real
control and a non-blocking tier with none gives both: a method can demand a synchronous veto/rewrite where
that's actually needed, and fan out to slower, more open-ended reactions everywhere else, on the same
underlying mechanism.

```java
class Article {

    @SyncSupervision(SupervisionPointcut.PRE)
    @AsyncSupervision(SupervisionPointcut.POST)
    public void publish() {
        // ...
    }
}

// A sync listener with real veto power:
JavAISupervisionRuntime.registerSyncListener(new SyncSupervisionListener() {
    @Override
    public void onPre(SupervisionEvent event) {
        if (!moderationPolicy.allows(event.instance())) {
            throw new IllegalStateException("blocked by moderation policy");
        }
    }
});

// An async listener that reacts without holding up the publish() call:
JavAISupervisionRuntime.registerAsyncListener(new AsyncSupervisionListener() {
    @Override
    public void onPost(SupervisionEvent event) {
        agenticListener.reviewAndMaybeFlag(event.instance());   // may take a while; publish() has already returned
    }
});
```

## Hard rule: never annotate this module's own classes

`@SyncSupervision`/`@AsyncSupervision` on anything in `dev.xtrafe.javai.supervision` itself is an infinite
loop, not a subtle bug — every dispatch would itself be a supervised call. The weaving selection is
opt-in (annotation-scoped), so this can't happen by accident, only by someone explicitly annotating the
runtime's own code. Don't.

## What's actually implemented

`SupervisionEvent`, `SyncSupervisionListener`, `AsyncSupervisionListener` — real, compilable, this module's
public contract. `SupervisionPointcut`, `@SyncSupervision`, `@AsyncSupervision` — real, in
`javai-annotations`. Not yet implemented: `JavAISupervisionRuntime` (registration + dispatch) and
`SupervisionWeaver` (the actual ByteBuddy weaving) — see `package-info.java` for the shape both are
expected to take, following `javai-agent`'s `JavAIWeaver`/`JavAIRuntime` split as the established idiom.
`DependencyWiringTest` proves the classpath resolves and the event/listener types are usable; it doesn't
exercise any weaving, because none exists yet.

Not designed at all yet, deliberately deferred past this spike: a depth/budget guard against a cascading
chain of async reactions (an async listener's side effect triggering another supervised call, which
triggers another async reaction, ...).

An "Agentic Listener" — a `SyncSupervisionListener`/`AsyncSupervisionListener` implementation that grounds
its decisions in RAG over the object graph and calls an LLM to decide what to do — is documented as an
application-level worked example in `doc/spec/agentic-supervision.md`, built on this module's listener
interfaces plus `javai-completion` (the actual LLM call) and `javai-runtime`/`javai-collections` (the RAG
grounding). It is not a dependency this module takes on itself, and not part of this module's own Phase 0
deliverable.
