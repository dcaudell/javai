# JavAI Codegen Guidance — Instructions for LLM Coding Agents

This file tells a coding agent (Claude, or any other LLM-based tool) how to read and respect the
**Codegen Guidance** annotations defined in JavAI Extensions §5.5. Drop it in a repository root, or
concatenate it into a system prompt, wherever a project already uses a `CLAUDE.md`/`AGENTS.md`-style
instructions file — this is the JavAI-specific supplement to that file, not a replacement for it.

Codegen Guidance is one of six JavAI extension areas (see the whitepaper, §5). It shares one annotation
mechanism with Vector Core's search-visibility annotations (§5.1) but a different purpose: these
annotations exist to tell *you*, the agent, what you're allowed to touch, what must remain true after you
touch it, and how much to trust your own judgment versus a compiler-checked guarantee.

## The five annotations, and exactly what each one asks of you

### `@Requires` / `@Ensures` / `@Invariant` — hard, compiler-checked contracts

These are not documentation. They are a pass/fail oracle, checked by `javaic` (or the Phase 0 weaver) at
build time, independent of anything you believe about your own change.

- Before you touch a method carrying `@Requires`, treat its precondition as load-bearing: any change that
  can no longer guarantee it is a broken change, not a style choice.
- After you touch a method carrying `@Ensures`, the postcondition must still hold. If you can't convince
  yourself it holds, say so explicitly rather than declaring the task done — a build failure here is the
  system working as intended, not an obstacle to route around.
- `@Invariant` on a class applies across *every* method, including ones you didn't touch. A change to one
  method that breaks another method's ability to maintain the class invariant is still your bug.
- Never delete or weaken a `@Requires`/`@Ensures`/`@Invariant` to make your own change pass. If the
  contract itself seems wrong, say so and ask — don't silently loosen it.

### `@Intent("...")` — natural-language purpose, loosely checked

Unlike the three above, `@Intent` is not hard-checked by the compiler — it's a loosely-verified oracle,
typically checked by an LLM reviewing the diff, not `javaic`.

- Treat the intent string as the actual spec when it's more specific than the method name or surrounding
  code. If your change satisfies the letter of a type signature but violates the stated intent, you have
  not completed the task correctly.
- If you change what a method does in a way that no longer matches its `@Intent`, update the annotation
  in the same change — don't leave stale intent text next to new behavior.
- Where no hard contract exists, `@Intent` is the best signal available for what "correct" means. Don't
  treat its absence of hard enforcement as license to ignore it.

### `@AgentWritable` / `@Frozen` / `@HumanOnly` — edit-scope permission

This is independent of Java's `public`/`private`/`protected` and independent of `@SearchVisibility`
(§5.1). It answers one question only: is an autonomous agent allowed to generate or modify this code at
all?

- `@AgentWritable`: you may generate or rewrite this element. Absence of this annotation is not implicit
  permission — check the class/package-level default before assuming.
- `@Frozen`: do not modify this element's body under any circumstances, even if a change here would be
  the most natural fix for a bug. Propose the change in prose instead, and let a human apply it.
- `@HumanOnly`: stronger than `@Frozen` — you should not even suggest a specific diff for this element.
  Describe the problem; do not describe the fix as code.
- If a change requires touching both an `@AgentWritable` method and a `@Frozen` one to work, do the part
  you're allowed to do, and stop — flag the `@Frozen` dependency explicitly rather than silently working
  around it or leaving the change half-applied without saying so.

### `@Nondeterministic` / `@Costly` — effect tags forcing explicit handling

These mark methods that call an embedding model or a completion provider (Vector Core §5.1, Completion
Fabric §5.3) — calls that can fail, vary between runs, and cost real time or money.

- Never call a `@Nondeterministic` method inside a loop, a retry-without-backoff, or a hot path without
  explicit justification — the annotation exists specifically to make you stop and think about call
  volume before you write it, not after.
- Any code you write that calls a `@Costly` method should have visible, deliberate error/retry handling.
  "It usually works" is not acceptable justification for skipping it.
- Do not memoize or cache the result of a `@Nondeterministic` call as if it were pure, unless the
  surrounding code already does so deliberately (e.g., via `EmbeddingVector`'s versioning, §6.2) — silently
  assuming determinism where none is promised is exactly the class of bug these tags exist to prevent.

### `@Provenance` — compiler-applied, never hand-written

This annotation records which model generated a piece of code and when. `javaic`/the weaver applies it
automatically to agent-written code (per `@AgentWritable`) at commit or build time.

- Do not write `@Provenance` yourself. If you see a task asking you to add one by hand, that's a signal
  something upstream is misconfigured — flag it rather than fabricating a plausible-looking value.
- Don't remove or edit an existing `@Provenance` annotation on code you didn't just generate — it's a
  historical record, not a stale comment to clean up.

## Worked example

```java
@JavAIVectorizable
@JavAIGraphNode
public class Article {

    @Vectorize @SearchVisibility(PUBLIC)
    private String title;

    @Requires("title != null && !title.isBlank()")
    @Ensures("result.vector() != null")
    @Intent("Normalizes whitespace before an article is (re)vectorized")
    @AgentWritable
    public Article normalize() { /* you may rewrite this body */ return this; }

    @Frozen
    @Nondeterministic @Costly
    public EmbeddingVector regenerateEmbedding(@EmbeddingModel("e5-large-v3") String model) {
        /* do not modify -- propose changes in prose instead */
        return this.vector();
    }
}
```

Reading this class correctly, as an agent:

1. You may rewrite `normalize()`'s body freely (`@AgentWritable`), but whatever you write must still
   accept only non-blank titles (`@Requires`), must still leave `vector()` non-null afterward
   (`@Ensures`), and should still do what "normalizes whitespace before (re)vectorization" describes
   (`@Intent`) — not just something that happens to compile.
2. You may not modify `regenerateEmbedding()`'s body at all (`@Frozen`), even if you believe you see a bug
   in it. Describe the issue in your response; do not include a diff for this method.
3. If asked to add retry logic around a call to `regenerateEmbedding()`, that's expected and welcome —
   `@Nondeterministic @Costly` is precisely the signal that such handling is required elsewhere, even
   though the method's own body is off-limits.
4. Do not add `@Provenance` to `normalize()` yourself, even after rewriting it — that annotation is
   applied by the toolchain, not by you.

## Quick checklist before you call a change complete

- [ ] Every `@Requires`/`@Ensures`/`@Invariant` touched by this change still holds — not "probably," verified.
- [ ] Every `@Intent` string near this change still describes what the code actually does.
- [ ] Nothing marked `@Frozen` or `@HumanOnly` was modified, even incidentally.
- [ ] Every new call to a `@Nondeterministic`/`@Costly` method has explicit error/retry handling and isn't
      placed somewhere it will be called at unbounded volume.
- [ ] No `@Provenance` annotation was hand-written or hand-edited.

See the JavAI Extensions whitepaper, §5.5 (Codegen Guidance) and §5.1 (Vector Core, for the other hat of
the same annotation mechanism), for the full primitive definitions this file assumes.
