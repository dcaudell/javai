# Agentic Supervision

Module: `javai-supervision`. Whitepaper: §5.7. Depends only on `javai-annotations` (+ ByteBuddy) — the same
early, low-risk tier as Acceleration Substrate (`javai-substrate`), not downstream of Vector Core, Vector
Collections, or Completion Fabric.

**A reframing worth stating explicitly, since it's the motivation for the whole area:** a classical stack
machine is a program, a stack, and a heap the stack points into. JavAI Extensions already turns the heap
into a vectorized, queryable object graph (Vector Core, Vector Collections). AoP-style interception already
means "something happens when a stack frame is pushed or popped" — a call, a return, a throw. Agentic
Supervision is what you get by taking those two things that already exist and wiring them together at the
join point: a fourth architectural component, alongside program/stack/heap, that can observe and mutate
execution at frame boundaries, grounded in queries over the heap-as-graph. Nothing about that requires a
new kind of primitive — see "Relationship to the rest of JavAI Extensions" below.

## Prior art — a real predecessor, not a clean-room design

This module's mechanism is a from-scratch reimplementation, on ByteBuddy, of an ASM-based AoP framework
(`com.xtrafe.vyper2.aspect`, 2009): six pointcuts (`CONSTRUCTOR_PRE`, `CONSTRUCTOR_POST`, `METHOD_PRE`,
`METHOD_POST`, `CONSTRUCTOR_EXCEPTION`, `METHOD_EXCEPTION`), woven via a custom `ClassLoader` at
class-load time into any class marked with a `@Managed` annotation, with a per-method `@Ignore` opt-out.
Every intercepted call boxed its arguments into `Object[]`, handed off to a static `Interceptor`, which
reflectively resolved the `Method`/`Constructor` and fanned out to two parallel extensibility mechanisms: a
list of statically-registered `Advisor`s (each scoped to a class hierarchy via `supportedClass()`), and an
optional pub/sub `PointcutEventHandler` layer gated by a config flag. Every join point could rewrite what
happened — PRE rewrote arguments (or vetoed the call by throwing), POST rewrote the return value, EXCEPTION
rewrote the throwable — with one real gap: EXCEPTION could only swap *which* throwable propagated, never
convert a throw into a normal return.

What this module keeps: the pub/sub listener model, and the core idea that PRE/POST/EXCEPTION carry real
read-write access rather than being passive notifications. What changes, and why:

- **Six pointcuts collapse to three.** A constructor is just another `java.lang.reflect.Executable` —
  which one fired is already recoverable from the event itself (`SupervisionEvent.executable()`), so there
  is no need for a separate constructor-flavored enum value the way the original had.
- **Reflection-and-boxing becomes ByteBuddy `Advice`.** The original's static `Interceptor` reflectively
  re-resolved the `Method`/`Constructor` on every single call and boxed every primitive argument into
  `Object[]` to do it. ByteBuddy `Advice` is inlined, typed bytecode — see `javai-substrate`'s `JavAIWeaver`
  for the idiom already established in this project (`@Advice.OnMethodExit`, `@Advice.This`,
  `@Advice.Argument`) and follow it here.
- **Class-level allowlist via a custom `ClassLoader` becomes annotation-scoped `ElementMatchers`,
  applied per-method/constructor, not per-class.** The original's `@Managed`/`@Ignore` pair worked at the
  class level (a whole class opts in, individual methods opt out). This module's weaving is opt-in at the
  method/constructor level directly (`@SyncSupervision`/`@AsyncSupervision`), and deliberately has no
  class-level "everything in this class is supervised" form — see "Why opt-in, and why sparse" below.
- **EXCEPTION gets strictly more capable, on methods.** `SupervisionEvent.setThrown(null)` converts an
  exceptional exit into a normal return of whatever `setReturnValue` was also called with — the one thing
  the original couldn't do, a natural consequence of `Advice.OnMethodExit(onThrowable = ...)` giving
  read-write access to both the thrown value and the return value at the same join point. A second,
  separate improvement falls out of the same mechanism: EXCEPTION fires for *any* exception leaving the
  method, including one propagated from a call it makes, not just a literal `throw` textually inside it —
  the original could only ever see the latter, since it hooked `ATHROW` opcodes directly rather than
  installing a real exception handler.
- **EXCEPTION does not extend to constructors, for a real JVM reason discovered while building this, not
  assumed up front.** The JVM verifier will not allow an exception handler to span a constructor's
  mandatory `super()`/`this()` call, so ByteBuddy refuses to attach `Advice.OnMethodExit(onThrowable = ...)`
  to any constructor at all. `SupervisionWeaver` rejects `EXCEPTION` on a constructor at weave time rather
  than leaving it a silent no-op — see `javai-supervision`'s own README/package-info for the exact,
  empirically-confirmed failure mode. A related, narrower constraint: a constructor's PRE dispatch always
  observes `instance() == null`, since ByteBuddy's entry advice categorically refuses to bind `this` on a
  constructor regardless of where the super/this call completes in the bytecode — the same restriction it
  applies to a static method's entry advice. Neither of these are regressions from the original: its
  ASM-based mechanism never installed a real exception handler at all (see above), so it never hit the
  first restriction, and it loaded `this` via a raw, low-level `ALOAD 0` rather than ByteBuddy's higher-level
  `Advice` binding, so it never hit the second either — but reproducing the original's exact capability
  here wasn't judged worth the added complexity for this spike.
- **A second, orthogonal axis is new: synchronous vs. asynchronous delivery.** The original had no
  equivalent. See below — this is the actual answer to "an AoP join point can trigger arbitrarily long-running
  work; you cannot always afford to block on that."

## Why opt-in, and why sparse

An LLM round trip is, at minimum, on the order of 100ms (see Completion Fabric's own "blocking,
virtual-thread-backed" framing). A method call can happen millions of times a second. `@JavAIVectorizable`
weaving is deliberately blanket (every field of an annotated class); Agentic Supervision weaving is
deliberately the opposite — every `@SyncSupervision`/`@AsyncSupervision` is a specific, sparse annotation on
a specific method or constructor, chosen because that particular join point is worth the cost of being
observable or interruptible. There is no class-level form, and there should never be one added casually:
a synchronous listener sits in the critical path of every call it's scoped to.

Note also that annotating a call site doesn't guarantee a listener actually responds to it — weaving and
listener registration are independent. A `@SyncSupervision`-annotated method with zero registered listeners
scoped to it costs a cheap no-op dispatch, not an LLM call; whether anything expensive actually happens is
entirely a function of what's registered at runtime, the same separation the original AoP framework already
had between "this class is `@Managed`" and "an `Advisor` is actually registered for it."

## Primitives

| Element | Kind | Purpose |
|---|---|---|
| `SupervisionPointcut` | Enum (`javai-annotations`) | `PRE` / `POST` / `EXCEPTION` |
| `@SyncSupervision(SupervisionPointcut...)` | Annotation (`javai-annotations`), method/constructor | Weaves blocking, read-write dispatch at the given pointcuts |
| `@AsyncSupervision(SupervisionPointcut...)` | Annotation (`javai-annotations`), method/constructor | Weaves fire-and-forget, observation-only dispatch at the given pointcuts |
| `SupervisionEvent` | Value type (`javai-supervision`) | Pointcut, instance, `Executable`, mutable arguments/returnValue/thrown |
| `SyncSupervisionListener` | Interface (`javai-supervision`) | `onPre`/`onPost`/`onException`, real mutation rights, default no-ops |
| `AsyncSupervisionListener` | Interface (`javai-supervision`) | Same three methods, observation-only, mutations discarded |
| `JavAISupervisionRuntime` | Static facade (`javai-supervision`) | Listener registration + the dispatch logic Advice calls into |
| `SupervisionWeaver` | ByteBuddy `AgentBuilder` installer (`javai-supervision`) | Weaves annotated methods/constructors; its own installer, independent of `javai-substrate`'s |

## The sync/async model, precisely

At a given pointcut on a given call: every registered `SyncSupervisionListener` scoped to the call runs
first, on the calling thread, in registration order, each seeing (and free to further mutate) whatever the
previous one left in the `SupervisionEvent`. Once the synchronous tier is done, the event — now reflecting
whatever it committed to — is handed to every registered `AsyncSupervisionListener` scoped to the call,
dispatched onto a virtual-thread-per-task executor, fire-and-forget. The calling thread never waits on the
asynchronous tier, no matter how long a listener takes; any mutation an async listener makes to its copy of
the event is simply discarded, since by the time it runs there may no longer be a call in flight for a
mutation to apply to.

This directly answers the risk the original design surfaced: "the AoP allows an entire separate program to
run" at a join point — an LLM agent loop, potentially with its own tool calls, could genuinely take a long
time. Making every supervised call block unconditionally on that would make the mechanism unusable for
anything but the rarest, most deliberate interception points. Splitting delivery into a blocking tier with
real control and a non-blocking tier with none means a method can demand a synchronous veto or rewrite
exactly where that's load-bearing, and fan out slower, more open-ended reactions everywhere else, on the
same mechanism, using the same event shape, without one design compromising the other.

This is also where "trigger other work in response to this call" belongs. An async listener is
observation-only *with respect to the call that triggered it* — but nothing stops it from having arbitrary
side effects elsewhere: calling other supervised methods, kicking off unrelated tasks, writing to a
different part of the object graph. It just can never reach back and change the call that triggered it,
because by the time it plausibly could, that call may have already returned.

```java
class Article {

    @SyncSupervision(SupervisionPointcut.PRE)
    @AsyncSupervision(SupervisionPointcut.POST)
    public void publish() {
        // ...
    }
}

// Blocking veto power, exercised at PRE:
JavAISupervisionRuntime.registerSyncListener(new SyncSupervisionListener() {
    @Override
    public void onPre(SupervisionEvent event) {
        if (!moderationPolicy.allows(event.instance())) {
            throw new IllegalStateException("blocked by moderation policy");
        }
    }
});

// Fire-and-forget reaction, exercised at POST, doesn't hold up publish():
JavAISupervisionRuntime.registerAsyncListener(new AsyncSupervisionListener() {
    @Override
    public void onPost(SupervisionEvent event) {
        agenticListener.reviewAndMaybeFlag(event.instance());   // may take a while
    }
});
```

## Worked example: an Agentic Listener

The name of the extension area comes from the motivating use case: a listener whose decision is made by an
LLM, grounded in the object graph. That listener is **not** part of this module's own dependency footprint
(see "Relationship to the rest of JavAI Extensions" below) — it's an application-level composition of this
module's generic listener interfaces with Completion Fabric and Vector Core/Collections, sketched here to
show the shape:

```java
class AgenticReviewListener implements SyncSupervisionListener {

    @Override
    public void onPre(SupervisionEvent event) {
        // "At minimum, the object bucket attached to the event" -- what RAG scope means beyond that
        // (a query() walk, a KnowledgeGraph subgraph, an entirely different system) is this listener's
        // call, not javai-supervision's.
        JavAIVectorizable subject = (JavAIVectorizable) event.instance();
        JavAIList<Comment> context = subject.query(subject.vector(), Comment.class);

        CompletionResult verdict = completionProvider.complete(
            CompletionRequest.builder()
                .prompt("Should this be blocked before publishing? Answer yes or no with one reason.")
                .context(context.toContext())
                .maxTokens(60)
                .build());

        if (verdict.text().startsWith("yes")) {
            throw new IllegalStateException("blocked by agentic review: " + verdict.text());
        }
    }

    @Override
    public Class<?> supportedClass() {
        return Article.class;
    }
}
```

## Hard rule: never annotate this module's own classes

`@SyncSupervision`/`@AsyncSupervision` on anything inside `javai-supervision` itself is an infinite loop,
not a subtle bug: every dispatch would itself be a supervised call, which would dispatch again. The
weaving selection is opt-in and method-scoped, so this can only happen if someone explicitly annotates the
runtime's own code — the original AoP lineage had to solve the analogous problem with a `ClassLoader`-level
package denylist, because its weaving was blanket (class-level `@Managed`) rather than opt-in; that
denylist has no equivalent here because opt-in selection means there's nothing to forget to exclude.

## Relationship to the rest of JavAI Extensions

**Not implemented via Acceleration Substrate.** `javai-substrate` depends on `javai-annotations` +
`javai-vector` + `javai-model`, and is deliberately the first thing Phase 0 proves ("prove the weaving
mechanism itself... before building out javai-vector/javai-model and javai-collections in full" —
`CLAUDE.md`). If Agentic Supervision's weaving lived there instead of in its own module, and an Agentic
Listener implementation eventually wants `javai-completion` (to call an LLM) or
`javai-collections`/`javai-vector`/`javai-model` (to ground its decision), then `javai-substrate` would
transitively need those too — dragging the one module meant to be provable earliest and cheapest downstream
of the entire rest of the reactor. `javai-supervision` is instead a second, independent, equally-early risk
spike: its own `AgentBuilder` installer, its own Advice classes, no dependency on `javai-substrate` and no
dependency the other direction either.

**Not the same axis as Codegen Guidance, despite surface similarity.** `@AgentWritable`/`@Frozen`/
`@HumanOnly` govern what an agent may *edit*, at design/generation time. `@SyncSupervision`/
`@AsyncSupervision` govern what an agent may *observe or intervene on*, at run time. A method can carry
Codegen Guidance annotations, Agentic Supervision annotations, both, or neither, independently — they
answer different questions.

**Should co-occur with `@Nondeterministic`/`@Costly` from Codegen Guidance.** A `@SyncSupervision`- or
`@AsyncSupervision`-annotated method's behavior is not fully determined by its own body: a registered
listener, unknown to the method's own source, can change control flow, arguments, or results. That's
precisely what `@Nondeterministic` and `@Costly` already exist to flag — so a supervised method should
carry them too, as an explicit, visible statement in source (not something the weaver silently implies),
consistent with this project's existing rule that Codegen Guidance annotations are meant to be
face-value-readable contracts, not synthesized.

**A separate, run-time concept from `@Provenance`.** `@Provenance` records who *generated a piece of
source code* — compiler-applied, once, at generation time. A supervisory intervention happens at run time,
potentially differently on every call, and recording it is an execution-audit concern, not a code-provenance
one. This module doesn't yet define a primitive for that (an `InterventionRecord`/audit-log concept is a
reasonable next design step, not Phase 0 scope) — don't reach for `@Provenance` to fill that gap; it means
something different.

## Open questions, deliberately deferred past this spike

- **No depth/budget guard yet.** An async listener's side effect can trigger another supervised call,
  which can trigger another async reaction, and so on. `query()`'s cycle-safety and `maxDepth` are the
  precedent for bounding an analogous problem in Vector Core; Agentic Supervision needs its own version of
  that before this goes past a spike, but doesn't need it to prove the mechanism works at all.
- **No audit/provenance primitive yet** for "which listener changed what, on which call" — see above.
- **RAG scope is unbounded by design**, per the "Worked example" section — this module deliberately takes
  no position on it. Whether that remains the right call once real Agentic Listener implementations exist
  is worth revisiting, not assuming.
