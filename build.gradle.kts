plugins {
    java
    id("org.springframework.boot") version "3.5.16"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "com.maitmus"
version = "0.0.1-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    mavenCentral()
}

// 보안 패치 버전 고정 — Boot BOM 기본값에 CVE가 남은 라이브러리(2026-11-19 OSV 점검)
extra["jackson-bom.version"] = "2.21.8"
extra["logback.version"] = "1.5.38"
extra["log4j2.version"] = "2.26.1"
extra["httpclient5.version"] = "5.6.5"
extra["httpcore5.version"] = "5.4.4"

dependencies {
    implementation("org.springframework.boot:spring-boot-starter")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework:spring-web")

    // Discord
    implementation("net.dv8tion:JDA:5.2.1")

    // Anthropic
    implementation("com.anthropic:anthropic-java:2.30.0")

    constraints {
        implementation("com.google.protobuf:protobuf-java:3.25.9") { because("CVE-2024-7254") }
    }

    // JSON
    implementation("com.fasterxml.jackson.module:jackson-module-parameter-names")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")

    // Lombok (1.18.40+ required for Java 25 support)
    compileOnly("org.projectlombok:lombok:1.18.48")
    annotationProcessor("org.projectlombok:lombok:1.18.48")

    // Spring Config
    annotationProcessor("org.springframework.boot:spring-boot-configuration-processor")

    // Test
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.assertj:assertj-core")
    testImplementation("org.mockito:mockito-junit-jupiter")
    testImplementation("org.wiremock:wiremock-standalone:3.13.1")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> {
    useJUnitPlatform()
}

// Lombok + Java 24: open internal javac packages for annotation processing
tasks.withType<JavaCompile> {
    options.isFork = true
    options.forkOptions.jvmArgs = listOf(
        "--add-opens=jdk.compiler/com.sun.tools.javac.code=ALL-UNNAMED",
        "--add-opens=jdk.compiler/com.sun.tools.javac.comp=ALL-UNNAMED",
        "--add-opens=jdk.compiler/com.sun.tools.javac.tree=ALL-UNNAMED",
        "--add-opens=jdk.compiler/com.sun.tools.javac.util=ALL-UNNAMED"
    )
}
