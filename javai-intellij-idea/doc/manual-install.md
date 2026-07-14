# Manual installation

This plugin isn't published to the JetBrains Marketplace. Two ways to get it into IntelliJ IDEA instead.

## Option A: install a pre-built ZIP

1. Build the plugin ZIP (see "Building from source" below), or get one someone else already built --
   either way you need `javai-intellij-idea-<version>.zip`.
2. In IntelliJ IDEA: **Settings/Preferences → Plugins → ⚙️ (gear icon) → Install Plugin from Disk...**
3. Select the ZIP file.
4. Restart IntelliJ when prompted.

That's it -- no repository URL, no marketplace account, no signing.

## Option B: build it yourself, then install

### Prerequisites

- A JDK 21 that Gradle can find. If you already have one on `PATH` (`java -version` says 21), skip ahead.
  If not:
  ```sh
  brew install openjdk@21
  ```
  Homebrew installs this "keg-only" (not linked into `/Library/Java/JavaVirtualMachines`, since it's an
  alternate version alongside whatever `openjdk` you may already have). Gradle's own JDK auto-detection
  doesn't always find a keg-only install on macOS. If `./gradlew buildPlugin` below fails with `Cannot find
  a Java installation on your machine ... matching {languageVersion=21, ...}`, register the JDK explicitly
  in your **user-level** Gradle settings (`~/.gradle/gradle.properties` -- not this project's own
  `gradle.properties`, since the path is specific to your machine):
  ```properties
  org.gradle.java.installations.paths=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
  ```
  (Adjust the path if Homebrew's prefix or the JDK's install location differs on your machine -- run
  `brew --prefix openjdk@21` to confirm.)

### Build

From this directory (`javai-intellij-idea/`):

```sh
./gradlew buildPlugin
```

First run downloads the Gradle distribution, the IntelliJ Platform SDK the plugin compiles against, and
the `com.intellij.java` bundled plugin it depends on -- expect it to take a couple of minutes and a few
hundred MB on a cold cache. The result:

```
build/distributions/javai-intellij-idea-<version>.zip
```

Install that ZIP via Option A above.

### Building against a different IDE version

`build.gradle.kts`'s `dependencies { intellijPlatform { create("IC", "2024.2") } }` line pins which IDE
version the plugin compiles against and its declared minimum compatible build
(`intellijPlatform { pluginConfiguration { ideaVersion { sinceBuild.set("242") } } }`). Bump both together if
you need to target a newer IDE baseline; there's no reason both need to match your own installed IDE version
exactly, only that `sinceBuild` stays truthful about what's actually been tested.

## Verifying it's active

After installing and restarting, open (or create) a class annotated `@JavAIVectorizable` with at least one
`@Vectorize` field, e.g.:

```java
import dev.xtrafe.javai.annotations.JavAIVectorizable;
import dev.xtrafe.javai.annotations.Vectorize;

@JavAIVectorizable
public class Article {
    @Vectorize
    private String title;
}
```

Then, anywhere with an `Article` reference, type `article.` and confirm `vector()`, `fieldVector(...)`,
`titleVector()`, `similarityTo(...)`, `query(...)`, and the `JavAIDirtyTracking` methods
(`markFieldDirty()`, `isFieldDirty()`, etc.) all appear in autocomplete with no red underline on the call
site. If they don't show up, double-check the plugin is enabled (**Settings → Plugins → Installed**) and
that the class is genuinely annotated `@JavAIVectorizable` (the augmentation only activates for that exact
annotation -- see `doc/spec/intellij-plugin.md` in the main `javai` repository for what is and isn't
covered).
