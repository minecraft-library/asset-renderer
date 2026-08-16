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

repositories {
    mavenCentral()
    maven(url = "https://central.sonatype.com/repository/maven-snapshots")
    maven(url = "https://jitpack.io")
}

dependencies {
    // The typed Feign contract for Mojang's launcher / Piston / textures endpoints, and the HTTP
    // client behind it. This module is the one place in the repo that reaches the network.
    api("com.github.simplified-api:mojang") { version { strictly("d678198") } }
    api("com.github.simplified-dev:client") { version { strictly("1ca9934") } }
    api("com.github.simplified-dev:utils") { version { strictly("821499b") } }
    api("com.github.simplified-dev:gson-extras") { version { strictly("f143dc1") } }
    api(libs.gson)

    // The @Parity vocabulary, resolved through the included build. `compileOnly` because retention
    // is SOURCE: javac needs the types to resolve a declaration and drops the descriptor before it
    // writes the class file, so neither dependent build inherits a type from it.
    compileOnly("lib.minecraft:asset-renderer-parity:0.1.0")

    // Simplified Annotations
    implementation(libs.simplified.annotations)
    annotationProcessor(libs.simplified.annotations)
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}
