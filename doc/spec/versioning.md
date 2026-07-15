# Versioning

How the project's version number is tracked, every place it's duplicated, and the exact steps to bump it --
written so a future bump doesn't require re-reading the whole codebase to rediscover this.

## Single source of truth

The root [`pom.xml`](../../pom.xml)'s `<version>` element. Every other location below either derives from
it mechanically (the 9-module reactor's own poms, via Maven's parent/child relationship) or is a
hand-maintained copy that has to be bumped in lockstep because it's outside that reactor.

## Every location a version number appears

### 1. The 9-module reactor (bumped together, one command)

Root `pom.xml`'s `<version>`, plus each of the 9 modules' `<parent><version>` (`javai-annotations`,
`javai-vector`, `javai-model`, `javai-substrate`, `javai-supervision`, `javai-collections`,
`javai-persistence`, `javai-completion`, `javai-tagging`). These never need hand-editing -- see "How to
bump" below.

### 2. `e2e-client-test/pom.xml` -- standalone, not touched by the reactor bump

Deliberately not a reactor module (its own comment explains why: it's downstream client code exercising
JavAI Extensions as a published dependency, not one of the 9 Phase 0 components). Three spots, all
hand-edited:
- Its own `<version>` (near the top) -- has tracked the main library's version in lockstep since inception
  by convention, not a hard technical requirement (confirmed via `git log -p`: bumped `0.1.0-SNAPSHOT` ->
  `0.1.0` -> `0.1.1` alongside the library itself).
- The `<javai.version>` property, which every `io.github.dcaudell:javai-*` dependency in that pom resolves
  against via `${javai.version}` -- this is the one that actually matters for the build to pick up the
  right jars.
- One explanatory XML comment near the top (`io.github.dcaudell:javai-{annotations,vector,model,substrate}:
  X.Y.Z are in the local repository`).

### 3. Consumer-facing documentation (hand-maintained dependency snippets)

Both of these show the exact XML/Gradle a downstream consumer would paste into their own project, so both
duplicate every module's coordinate + version:
- [`README.md`](../../README.md) -- "Manual install" section (XML dependency block + Gradle
  `build.gradle.kts` block).
- [`doc/ai-guidance/JavAI_Usage_Guide.md`](../ai-guidance/JavAI_Usage_Guide.md) -- same two blocks, plus one
  prose sentence ("at the version declared in this repository's root `pom.xml` (currently `X.Y.Z` --
  check there directly rather than assuming it hasn't changed)") that deliberately hedges rather than
  promising the number is current -- update it anyway when you bump, but that sentence is why a stale
  number here is a *documentation* bug, not a silent-trust bug.

### 4. `doc/release-process.md` -- deliberately version-agnostic, not a bump target

Uses a generic `vX.Y.Z` placeholder in its flow diagram and tag-commands rather than a real version number,
specifically so this file never needs touching on a routine bump. If you ever find a real version number
creep back into it, that's a regression -- replace it with the placeholder rather than updating the number.

### Not a version-bump target

- `doc/module-dependency-graph.md`, `SPEC.md`, `doc/spec/*.md`, per-module `README.md` files: these describe
  architecture and status, not install instructions, and don't carry version numbers.
- `.idea/workspace.xml`: IDE-local state, gitignored (`.idea/.gitignore` excludes it), never touched.

## How to bump

1. **The 9-module reactor, in one command, from the repo root:**

   ```bash
   mvn versions:set -DnewVersion=X.Y.Z -DgenerateBackupPoms=false
   ```

   This is the `org.codehaus.mojo:versions-maven-plugin`, resolved via Maven's default plugin-prefix
   lookup -- no need to add it to any pom first. It rewrites the root `pom.xml`'s `<version>` and every
   module's `<parent><version>` in place. `-DgenerateBackupPoms=false` skips the `pom.xml.versionsBackup`
   files (there's nothing to roll back to that `git diff`/`git checkout` doesn't already give you).

2. **`e2e-client-test/pom.xml`, by hand** (3 spots -- see "Location 2" above): its own `<version>`, the
   `<javai.version>` property, and the explanatory comment near the top.

3. **`README.md` and `JavAI_Usage_Guide.md`, by hand**: every `<version>X.Y.Z</version>` / `:X.Y.Z")` in
   both files' dependency snippets, plus the one "currently `X.Y.Z`" sentence in the usage guide. If a
   module was added or removed since the last bump, double check both files' dependency lists actually list
   every current module -- these are hand-maintained copies, not generated, so they silently drift when a
   module is added and only the reactor/e2e poms are updated (this happened once already: `javai-tagging`
   shipped in the reactor for a full session before anyone noticed it was missing from both files' install
   instructions).

4. **Do not touch `doc/release-process.md`** -- its `vX.Y.Z` placeholders are intentionally generic (see
   "Location 4" above).

5. **Verify nothing was missed:**

   ```bash
   grep -rn "OLD.VERSION.HERE" . --include="*.xml" --include="*.md" 2>/dev/null | grep -v "/target/"
   ```

   Expect zero hits outside of deliberate historical/narrative mentions (e.g. `doc/release-process.md`'s own
   "already done" note citing real past tags by their real version, or a changelog-style sentence
   describing what a past release did).

6. **Rebuild to confirm the new version actually resolves:**

   ```bash
   mvn -q install -DskipTests          # from the repo root -- 9-module reactor
   cd e2e-client-test && mvn -q test-compile   # standalone project, picks up the just-installed jars
   ```

## Version bump vs. release

Bumping the version (this document) and cutting a release (tag push -> `publish.yml` -> Maven Central) are
two different acts -- bumping just makes the new number the one that *would* be released next. See
[`doc/release-process.md`](../release-process.md) for the actual publish flow, branch protection, and
one-time account setup.

## A note on the current design, for whoever revisits this

Every module pom hand-repeats `<parent><version>X.Y.Z</version></parent>` rather than using Maven's
"CI Friendly Versions" pattern (a `${revision}` property placeholder plus `flatten-maven-plugin`, which
would make step 1 above touch only the root `pom.xml`). That pattern wasn't adopted here because it adds a
build-plugin dependency and a flattened-pom generation step for a problem `versions:set` already solves in
one command -- but if this project ever needs to bump versions from CI (rather than by hand locally), that
pattern is the standard next step, and would shrink "Location 1" above to a single file.
