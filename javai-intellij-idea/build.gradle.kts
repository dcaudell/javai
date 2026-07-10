plugins {
    id("java")
    id("org.jetbrains.intellij.platform") version "2.18.0"
}

group = "dev.xtrafe.javai"
version = "0.1.0"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        create("IC", "2024.2")
        bundledPlugin("com.intellij.java")
    }
}

intellijPlatform {
    pluginConfiguration {
        id.set("dev.xtrafe.javai.intellij")
        name.set("JavAI Extensions Support")
        version.set(project.version.toString())
        ideaVersion {
            sinceBuild.set("242")
        }
    }
}

java {
    // Matches the rest of the reactor's own maven.compiler.release=21 (see root pom.xml); IntelliJ
    // Platform 2024.2 also requires 21 as a floor regardless. Uses a Gradle toolchain rather than assuming
    // whatever JDK happens to be on PATH is 21 -- see doc/manual-install.md's "Building from source" section
    // if Gradle can't find a JDK 21 on your machine (its auto-detection doesn't always find a keg-only
    // Homebrew install, for example -- registering it in ~/.gradle/gradle.properties fixes that).
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}
