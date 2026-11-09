plugins {
    // 26.3 is shipped unobfuscated: there is no remapping step, so the plain
    // `fabric-loom` plugin is used instead of `fabric-loom-remap`.
    id("net.fabricmc.fabric-loom") version "1.19.4"
    kotlin("jvm") version "2.4.21"
    `java-library`
}

version = "1.4.0"
group = "dev.iustitia"

base { archivesName.set("iustitia") }

tasks.processResources {
    inputs.property("version", project.version)
    filesMatching("fabric.mod.json") {
        expand("version" to project.version)
    }
}

repositories {
    maven("https://maven.isxander.dev/releases")   // YACL
    maven("https://maven.fabricmc.net")
}

dependencies {
    minecraft("com.mojang:minecraft:${property("minecraft_version")}")
    implementation("net.fabricmc:fabric-loader:${property("loader_version")}")
    implementation("net.fabricmc.fabric-api:fabric-api:${property("fabric_api_version")}")
    implementation("net.fabricmc:fabric-language-kotlin:${property("fabric_kotlin_version")}")
    implementation("dev.isxander:yet-another-config-lib:${property("yacl_version")}")

    // Pure-JVM unit tests (src/test) for config/preset logic that is separable from Minecraft
    // objects (docs/ai-assisted-development.md: add pure tests where the logic can be separated).
    // kotlin("test") resolves to its JUnit5 variant because the test task uses JUnitPlatform.
    testImplementation(kotlin("test"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}

java {
    toolchain { languageVersion.set(JavaLanguageVersion.of(25)) }
    withSourcesJar()
}

kotlin {
    jvmToolchain(25)
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_25)
    }
}
