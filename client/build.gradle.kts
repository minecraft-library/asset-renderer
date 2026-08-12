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
    api("com.github.simplified-api:mojang") { version { strictly("5c2bda6") } }
    api("com.github.simplified-dev:client") { version { strictly("3d87a03") } }
    api("com.github.simplified-dev:utils") { version { strictly("7c2feb7") } }
    api("com.github.simplified-dev:gson-extras") { version { strictly("2ba8143") } }
    api(libs.gson)

    compileOnly(libs.lombok)
    annotationProcessor(libs.lombok)
    annotationProcessor(libs.simplified.annotations)
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}
