plugins {
    id("java-library")
}

group = "lib.minecraft"
version = "0.1.0"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

// No repositories and no dependencies: the five types import java.lang.annotation and nothing else.
// The Vector API module is absent for the same reason - it belongs where a lane is read, and nothing
// here reads one.

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}
