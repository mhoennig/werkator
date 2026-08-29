import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.springframework.boot") version "4.0.6"
    id("io.spring.dependency-management") version "1.1.7"
    id("org.jlleitschuh.gradle.ktlint") version "14.2.0"
    kotlin("jvm") version "2.2.0"
    kotlin("plugin.spring") version "2.2.0"
}

group = "de.hoennig"
// bump at least the patch version for every deployment — and only then, not per commit —
// so the UI footer (BuildProperties), --version and the release notes identify what is
// actually running; a deployment bundles whatever was committed since the last one
version = "0.9.19"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter")
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-thymeleaf")
    implementation("org.springframework:spring-web")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("info.picocli:picocli-spring-boot-starter:4.7.6")
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    // @WebMvcTest lives in its own module since Spring Boot 4
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")

    // Kotest
    testImplementation("io.kotest:kotest-runner-junit5:6.1.5")
    testImplementation("io.kotest:kotest-assertions-core:6.1.5")
    testImplementation("io.kotest:kotest-extensions-spring:6.1.5")

    // MockK
    testImplementation("io.mockk:mockk:1.13.11")
    testImplementation("com.ninja-squad:springmockk:5.0.1")

    // WireMock
    testImplementation("org.wiremock.integrations:wiremock-spring-boot:4.2.1")

    // Testcontainers (versions managed by Spring Boot BOM)
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:testcontainers")
}

springBoot {
    // exposes the project version to the UI footer via the BuildProperties bean;
    // the volatile build time is excluded to keep builds repeatable
    buildInfo {
        properties {
            excludes.set(setOf("time"))
        }
    }
}

tasks.bootJar {
    // version-free jar name, so docs and scripts never contain the version;
    // the version itself stays available via BuildProperties (UI footer, --version)
    archiveFileName = "gittally.jar"
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict")
        jvmTarget = JvmTarget.JVM_21
    }
}

// Self-contained runtime bundle for hosts without a Java runtime (plan step 15, ADR 0006):
// a jlink-trimmed JRE plus gittally.jar plus the packaging/gittally launcher, packed as a tarball.
// The JDK module list below was computed from the exploded boot jar via
//   jdeps -q --ignore-missing-deps --multi-release 21 --print-module-deps \
//     --class-path 'BOOT-INF/lib/*' BOOT-INF/classes BOOT-INF/lib/*.jar
// plus runtime-only modules jdeps cannot detect: java.logging (Tomcat JULI),
// jdk.crypto.ec (TLS ECDHE), jdk.management (extended OS MXBeans), jdk.zipfs (nested jar access).
// Re-check the jdeps output when dependencies change.
val runtimeBundleModules =
    listOf(
        "java.base",
        "java.compiler",
        "java.desktop",
        "java.instrument",
        "java.logging",
        "java.management",
        "java.naming",
        "java.net.http",
        "java.prefs",
        "java.scripting",
        "java.security.jgss",
        "java.sql",
        "jdk.crypto.ec",
        "jdk.jfr",
        "jdk.management",
        "jdk.unsupported",
        "jdk.zipfs",
    ).joinToString(",")

val runtimeBundle by tasks.registering {
    group = "distribution"
    description = "Builds the self-contained runtime bundle (jlink JRE + jar + launcher) as a tar.gz"
    dependsOn(tasks.bootJar)

    val jdkHome = javaToolchains.launcherFor(java.toolchain).map { it.metadata.installationPath.asFile }
    val jarFile = tasks.bootJar.flatMap { it.archiveFile }
    val launcherFile = layout.projectDirectory.file("packaging/gittally").asFile
    val stagingDir =
        layout.buildDirectory
            .dir("runtime-bundle")
            .get()
            .asFile
    val tarballFile =
        layout.buildDirectory
            .file("distributions/gittally-runtime-linux-x64.tar.gz")
            .get()
            .asFile

    inputs.files(tasks.bootJar.map { it.outputs.files })
    inputs.file(launcherFile)
    inputs.property("modules", runtimeBundleModules)
    outputs.file(tarballFile)

    doLast {
        val bundleRoot = stagingDir.resolve("gittally")
        bundleRoot.deleteRecursively()
        bundleRoot.parentFile.mkdirs()

        val jlink = jdkHome.get().resolve("bin/jlink")
        val jlinkProcess =
            ProcessBuilder(
                jlink.absolutePath,
                "--add-modules",
                runtimeBundleModules,
                "--strip-debug",
                "--no-header-files",
                "--no-man-pages",
                "--compress",
                "zip-6",
                "--output",
                bundleRoot.resolve("jre").absolutePath,
            ).redirectErrorStream(true).start()
        val jlinkOutput = jlinkProcess.inputStream.bufferedReader().readText()
        check(jlinkProcess.waitFor() == 0) { "jlink failed:\n$jlinkOutput" }

        jarFile.get().asFile.copyTo(bundleRoot.resolve("lib/gittally.jar").also { it.parentFile.mkdirs() })
        val launcher = launcherFile.copyTo(bundleRoot.resolve("bin/gittally").also { it.parentFile.mkdirs() })
        check(launcher.setExecutable(true, false)) { "cannot make $launcher executable" }

        tarballFile.parentFile.mkdirs()
        // system tar preserves the execute bits of jre/bin/* and jre/lib/jspawnhelper
        val tarProcess =
            ProcessBuilder("tar", "-czf", tarballFile.absolutePath, "-C", stagingDir.absolutePath, "gittally")
                .redirectErrorStream(true)
                .start()
        val tarOutput = tarProcess.inputStream.bufferedReader().readText()
        check(tarProcess.waitFor() == 0) { "tar failed:\n$tarOutput" }
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
}
