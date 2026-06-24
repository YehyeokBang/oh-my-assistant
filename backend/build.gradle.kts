import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.3.0"
    kotlin("plugin.spring") version "2.3.0"
    id("org.springframework.boot") version "4.1.0"
    id("io.spring.dependency-management") version "1.1.7"
    application
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    // JVM엔 내장 JSON이 없다. parseToJsonElement만 쓰므로 컴파일러 플러그인 불필요(런타임 의존성 1개).
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-reactor:1.11.0")

    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
}

kotlin {
    jvmToolchain(25)
    compilerOptions {
        // 확정 버전 검증: 25가 24로 조용히 폴백되면 빌드가 깨진다(Kotlin 2.2.x 금지 사유).
        jvmTarget.set(JvmTarget.JVM_25)
    }
}

application {
    mainClass.set("omabang.engine.MainKt")
}

springBoot {
    mainClass.set("omabang.web.OmabangApplicationKt")
}

tasks.bootRun {
    mainClass.set("omabang.web.OmabangApplicationKt")
}

// 병렬 위임 콘솔 진입점(P5 수동). application의 기본 run(Phase 0 Main)과 별개. stdin 연결 필수.
tasks.register<JavaExec>("runOrchestrator") {
    group = "application"
    description = "병렬 위임 콘솔 진입점 (Phase 1)"
    mainClass.set("omabang.engine.orchestrate.OrchestratorMainKt")
    classpath = sourceSets["main"].runtimeClasspath
    standardInput = System.`in`
}

tasks.test {
    // 실제 claude 호출 통합 테스트는 기본 제외(구독 슬롯/시간 소모). 실행: ./gradlew test -Pintegration
    useJUnitPlatform {
        if (!project.hasProperty("integration")) {
            excludeTags("integration")
        }
    }
    testLogging {
        events("passed", "skipped", "failed")
    }
}
