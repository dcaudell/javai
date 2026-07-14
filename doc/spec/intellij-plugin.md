# IntelliJ IDEA Plugin

Project: `javai-intellij-idea` (repo root, sibling to the eight reactor modules and to `e2e-client-test`).
Whitepaper: none yet -- `doc/spec/acceleration-substrate.md`'s own primitives table already lists "IntelliJ
/ VS Code plugin" as IDE tooling for a later phase; this is that item, arriving earlier than "later phase"
because the specific pain it addresses (real, not hypothetical) came up directly while using this repo.

Depends on nothing in the reactor. Built with Gradle, not Maven -- the only well-supported way to build an
IntelliJ Platform plugin -- and matches `javai-substrate`'s own annotations by qualified-name string
comparison, never by importing `javai-annotations` itself. See `javai-intellij-idea/README.md` for what it
is; this file is about *why* it's shaped the way it is.

## The problem, precisely

`javai-substrate`'s ByteBuddy weaver adds real, callable methods to a `@JavAIVectorizable`-annotated class
at class-load time: the full `JavAIVectorizable`/`JavAIDirtyTracking` contract (`vector()`, `query(...)`,
`markFieldDirty()`, etc.), plus one `<field>Vector()` convenience accessor per `@Vectorize`-annotated field
(`title` → `titleVector()`). None of this exists in the class's own source, and none of it exists in the
`.class` file `javac` produces either -- it's added later, when the JVM classloader hands the bytecode to
the weaver, which IntelliJ's own static analysis has no way to observe: IntelliJ analyzes *source*, and the
synthesized methods are added strictly *after* compilation. Calling `article.vector()` directly on an
`Article`-typed reference shows a real "cannot resolve method" error in the editor, even though the code is
correct and works at runtime.

This project's own tests already had a documented workaround before this plugin existed: cast through
`JavAIVectorizable`/`JavAIDirtyTracking` (real, source-declared interfaces in `javai-model`) to call the
*fixed* contract methods --

```java
private static JavAIVectorizable vectorizable(Object woven) {
    return (JavAIVectorizable) woven;
}
```

-- see `ArticleGraphEmbeddingE2ETest`'s own javadoc ("Phase 0 has no real compiler yet... that cast is the
one honest concession"). That workaround has a real gap, though: the per-field `<field>Vector()` shortcut
methods have no possible interface home (the method *name* is derived from the annotated field's name at
weave time), so no cast, however clever, can make `article.titleVector()` resolve. That gap is what this
plugin actually closes, on top of removing the need for the cast+interface dance entirely for the fixed
contract methods too.

## Why a real IntelliJ plugin, not a "setting"

There is no IDE setting that can selectively silence "unresolved method" only for methods a weaver will add
later -- IntelliJ's unresolved-symbol inspection is genuine analysis of a class's actual declared+inherited
members, not a pattern-matchable toggle. The blunt lever (disable "cannot resolve symbol" for a scope) was
considered and rejected: it throws away real typo-catching for every class in that scope, and IntelliJ
scopes match by file/directory pattern, not by annotation, so it can't even be aimed precisely at
`@JavAIVectorizable` classes specifically.

The actual mechanism: `com.intellij.psi.augment.PsiAugmentProvider`, an IntelliJ Platform extension point
that lets a plugin inject synthetic `PsiMethod`s into a class's apparent member list for IDE-analysis
purposes only -- inspections, autocomplete, "go to declaration" all see the synthesized members; the real
compiler, the real weaver, and the actual runtime bytecode are completely untouched by any of this. This is
the same mechanism Lombok's own IntelliJ plugin uses to make `@Data`-generated getters/setters resolve
without a real compile-time source change, and it is the standard, precedented answer to "a tool other than
javac adds real members to my classes and I want the IDE to know about it."

## What `JavAIPsiAugmentProvider` actually does

One class (`javai-intellij-idea/src/main/java/dev/xtrafe/javai/intellij/JavAIPsiAugmentProvider.java`),
registered via `plugin.xml`'s `<psiAugmentProvider>` extension point. For any `PsiClass` annotated
`@JavAIVectorizable` (matched by the annotation's qualified name as a string literal, not by importing the
real `dev.xtrafe.javai.annotations.JavAIVectorizable` class -- this plugin has zero compile-time dependency
on any `javai-*` artifact, deliberately, to stay buildable and distributable entirely independently of the
reactor it supports), `getAugments` synthesizes, via `com.intellij.psi.impl.light.LightMethodBuilder`:

- The full `JavAIVectorizable`/`JavAIDirtyTracking` contract, matching `javai-model`'s real interfaces
  exactly in name/arity: `vector()`, `fieldVector(String)`, `summaryVector()`, both `similarityTo(...)`
  overloads, both `query(...)` overloads, `markFieldDirty()`/`isFieldDirty()`/`clearFieldDirty()`,
  `markSummaryDirty()`/`isSummaryDirty()`/`clearSummaryDirty()`, `addDependent(Object)`, `dependents()`.
- One `<field>Vector()` accessor per field found via `PsiClass#getAllFields()` (walks the whole class
  hierarchy, mirroring `JavAIWeaver`'s own support for `@Vectorize` fields declared on a plain,
  non-`@JavAIVectorizable` superclass) that carries `@Vectorize` and not also `@VectorizeIgnore` --
  `@VectorizeIgnore` wins, matching the real weaver's own precedence rule exactly.

## Known gaps, deliberately not closed yet

- **`query(...)`'s synthesized return type is the raw `JavAIList`, not a properly parameterized
  `JavAIList<T>`.** Building a correctly-parameterized generic return type through `LightMethodBuilder`'s
  API is meaningfully more code for a cosmetic difference: the practical effect of leaving it raw is an
  "unchecked conversion" warning at the call site instead of a hard "cannot resolve method" error --
  eliminating the error (a real correctness-of-navigation problem) was the actual goal; the warning is a
  minor, deliberately-accepted cosmetic cost.
- **No tooling for `javai-supervision`.** `@SyncSupervision`/`@AsyncSupervision`-woven methods are wrapped
  by `Advice`, not supplemented with new ones -- the annotated method already exists in source with its own
  real signature, so there is nothing for a `PsiAugmentProvider` to add. This isn't a deferred gap; it's a
  correct reflection of what that weaver actually does (see `doc/spec/agentic-supervision.md`).
  Correspondingly, `javai-intellij-idea` doesn't reference `SupervisionWeaver`/`JavAISupervisionRuntime` at
  all.
- **No constructor-argument or dependency-wiring augmentation.** `JavAIWeaver`'s `ConstructorExitAdvice`
  wires dependency edges at the end of every constructor, but this doesn't add any new *callable member* --
  nothing for the IDE to need augmenting there either.
- **Not published to the JetBrains Marketplace.** See `javai-intellij-idea/doc/manual-install.md` for
  installing a locally-built ZIP instead. Marketplace publication is a reasonable next step once this has
  actually been used for a while, not a Phase 0 requirement.

## Verification

`javai-intellij-idea` is not part of the root Maven reactor, so it isn't exercised by `mvn install`/`mvn
test` from the repo root. Verify it directly:

```sh
cd javai-intellij-idea
./gradlew buildPlugin   # produces build/distributions/javai-intellij-idea-<version>.zip
./gradlew runIde        # launches a sandboxed IDE instance with the plugin installed, for manual verification
```

There is deliberately no automated PSI-level test suite yet (the IntelliJ Platform Test Framework can drive
this kind of check headlessly, asserting a given source snippet resolves a given method with no error) --
worth adding once the augmentation surface grows past what a five-minute manual check in `runIde` can cover
confidently.
