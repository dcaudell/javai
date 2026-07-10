# JavAI Extensions — AI Guidance Package

This directory is a self-contained, copyable package of documentation for an AI coding agent (Claude Code
or similar) helping a developer in a project that depends on **JavAI Extensions** (`dev.xtrafe.javai:*`) as
an ordinary Maven library. It is written for that downstream consumer, not for a contributor working inside
*this* repository — contributors should read this repo's own root `SPEC.md`/`CLAUDE.md` instead.

Two files, read for different reasons:

| File | Read this... | Covers |
|---|---|---|
| [`JavAI_Usage_Guide.md`](JavAI_Usage_Guide.md) | **Always**, before writing or editing any code that uses `dev.xtrafe.javai:*` | What the library does, the full annotation vocabulary, every method a woven class gains at runtime that isn't in its source, and how to install/build/activate it |
| [`JavAI_Codegen_Guidance.md`](JavAI_Codegen_Guidance.md) | **Only** when the code you're touching carries `@Requires`/`@Ensures`/`@Invariant`, `@Intent`, `@AgentWritable`/`@Frozen`/`@HumanOnly`, `@Nondeterministic`/`@Costly`, or `@Provenance` | A separate, narrower feature: annotations a *human* puts on their own code to constrain what an AI agent is allowed to read, generate, or modify — not a general usage guide |

They're kept separate deliberately: the first is "what can this library do and how do I call it," the
second is "what am I, the agent, allowed to touch in code that happens to use one specific feature of it."
Most sessions only need the first.

## Installing this package in a project that consumes JavAI Extensions

There's no fully-automatic way to pull a markdown file across independent git repositories — a human (or
you, on their behalf) has to place it once. The straightforward version:

1. **Copy this whole directory** into the consuming project, e.g. to `docs/javai-guidance/` or
   `.claude/javai-guidance/`. Keep the two files together — they're meant to travel as a pair.
2. **If the project uses Claude Code**, add one line near the top of its own `CLAUDE.md` (create one if it
   doesn't have one yet):

   ```
   @docs/javai-guidance/README.md
   ```

   Claude Code resolves `@path` lines in `CLAUDE.md` as file imports and loads the referenced file — and,
   transitively, the two files this one points to — into context automatically. No further action needed
   after that line is in place.
3. **If the project's AI tool doesn't support that import syntax** (e.g. a plain `AGENTS.md`, or a tool
   that only reads its own instructions file literally), add a plain sentence instead, near the top of
   whatever instructions file it does read:

   > Before writing or editing any code that uses `dev.xtrafe.javai:*` (JavAI Extensions), read
   > `docs/javai-guidance/README.md` first.

4. **Once JavAI Extensions is published to Maven Central**, shipping this package inside the
   `javai-annotations` artifact's sources/javadoc jar (so it travels with the dependency itself, no manual
   copy step) is a real option worth doing — not done yet; the manual copy above is the current, real
   mechanism. Don't assume the packaged version exists until this note is updated.

If you're an AI agent reading this because a user asked you to "add JavAI Extensions support" or similar:
performing steps 1–3 above yourself (copy the directory, add the import line) is exactly the kind of setup
work you should just do, the same way you'd add any other dependency's usage notes to a project.
