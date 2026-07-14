# javai-intellij-idea

An IntelliJ IDEA plugin that teaches the editor about the members `javai-substrate`'s ByteBuddy weaver adds
to a `@JavAIVectorizable`-annotated class at class-load time -- so writing `article.vector()` or
`article.titleVector()` doesn't show a red "cannot resolve method" underline, even though neither method
exists anywhere in source.

**Deliberately standalone**, like `e2e-client-test`: not part of the root Maven reactor (`pom.xml`'s
`<modules>`), not one of the eight Phase 0 modules `SPEC.md` describes. It's IDE tooling for consumers of
the library, built with Gradle (the standard, essentially only well-supported way to build an IntelliJ
Platform plugin) rather than Maven, and has zero dependency on any `javai-*` artifact -- it matches by
annotation *name* (a string), never by importing the real annotation classes. See
[`doc/spec/intellij-plugin.md`](../doc/spec/intellij-plugin.md) in the main repository for the full design
rationale, and [`doc/manual-install.md`](doc/manual-install.md) for how to install this without a
JetBrains Marketplace listing.

## What it does

One class, [`JavAIPsiAugmentProvider`](src/main/java/dev/xtrafe/javai/intellij/JavAIPsiAugmentProvider.java),
implementing the IntelliJ Platform's `PsiAugmentProvider` extension point -- the same mechanism Lombok's own
IntelliJ plugin uses to make `@Data`-generated getters/setters resolve without a real compile-time source
change. For any class annotated `@JavAIVectorizable`, it synthesizes (for IDE analysis only -- inspections,
autocomplete, "go to declaration" -- never for actual compilation):

- The full `JavAIVectorizable`/`JavAIDirtyTracking` contract: `vector()`, `fieldVector(String)`,
  `summaryVector()`, both `similarityTo(...)` overloads, both `query(...)` overloads, and the six
  dirty-tracking methods (`markFieldDirty()`, `isFieldDirty()`, etc.).
- One `<field>Vector()` accessor per `@Vectorize`-annotated field (minus any also carrying
  `@VectorizeIgnore`), walking the whole class hierarchy the same way `JavAIWeaver` itself does, so a field
  declared on a plain superclass is covered too.

## What it deliberately doesn't cover yet

See `doc/spec/intellij-plugin.md`'s "Known gaps" section -- short version: no supervision-related tooling
needed (`javai-supervision`'s weaving never adds a new callable method, so there's nothing to augment
there), and `query(...)`'s return type is the raw `JavAIList`, not a properly parameterized `JavAIList<T>`
(an "unchecked conversion" warning at the call site instead of a hard error -- a deliberate simplicity
trade-off, not an oversight).

## Building

```sh
./gradlew buildPlugin
```

Produces `build/distributions/javai-intellij-idea-<version>.zip`. See `doc/manual-install.md` if Gradle
can't find a JDK 21 on your machine, or for how to install the result into IntelliJ.

## Verifying it works

```sh
./gradlew runIde
```

Launches a sandboxed IntelliJ instance with this plugin installed -- open any project containing a
`@JavAIVectorizable` class (the main `javai` reactor's `e2e-client-test/src/main/java/.../domain/Article.java`
is a real, non-trivial example) and confirm autocomplete/no-red-squiggly on the woven-in methods described
above.
