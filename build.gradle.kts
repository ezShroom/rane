import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("net.fabricmc.fabric-loom") version "1.18-SNAPSHOT"
    kotlin("jvm") version "2.4.21"
    kotlin("plugin.serialization") version "2.4.21"
}

version = property("mod_version") as String
group = property("maven_group") as String

base {
    archivesName = "rane"
}

repositories {
    maven("https://maven.nucleoid.xyz/") { name = "Nucleoid" }
    maven("https://repo.bluecolored.de/releases") { name = "BlueColored" }
    mavenCentral()
}

dependencies {
    minecraft("com.mojang:minecraft:${property("minecraft_version")}")
    implementation("net.fabricmc:fabric-loader:${property("loader_version")}")
    implementation("net.fabricmc.fabric-api:fabric-api:${property("fabric_api_version")}")
    implementation("net.fabricmc:fabric-language-kotlin:${property("fabric_kotlin_version")}")

    implementation("eu.pb4:sgui:${property("sgui_version")}")
    include("eu.pb4:sgui:${property("sgui_version")}")
    implementation("me.lucko:fabric-permissions-api:${property("permissions_api_version")}")
    include("me.lucko:fabric-permissions-api:${property("permissions_api_version")}")

    compileOnly("de.bluecolored:bluemap-api:${property("bluemap_api_version")}")
    compileOnly("com.flowpowered:flow-math:1.0.3")
}

tasks.processResources {
    val version = project.version
    inputs.property("version", version)
    filesMatching("fabric.mod.json") {
        expand("version" to version)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 25
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_25
        freeCompilerArgs.add("-Xjvm-default=all")
    }
}

tasks.jar {
    from("LICENSE")
}

// The resource pack ships separately (players download it); build it next to the mod jar.
val resourcePackZip by tasks.registering(Zip::class) {
    archiveFileName = "rane-resourcepack.zip"
    destinationDirectory = layout.buildDirectory.dir("libs")
    from("resourcepack")
    from("LICENSE")
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

tasks.build {
    dependsOn(resourcePackZip)
}
