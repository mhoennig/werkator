import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.springframework.boot") version "4.0.6"
    id("io.spring.dependency-management") version "1.1.7"
    id("org.jlleitschuh.gradle.ktlint") version "14.2.0"
    kotlin("jvm") version "2.2.0"
    kotlin("plugin.spring") version "2.2.0"
}

group = "de.hoennig"
version = "0.9.0"

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

tasks.withType<Test> {
    useJUnitPlatform()
}
