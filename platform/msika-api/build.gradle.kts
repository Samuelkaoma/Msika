// A Spring Boot app. Versions of Spring libraries come from the Boot platform (BOM), so none are
// written here.
import org.springframework.boot.gradle.plugin.SpringBootPlugin

plugins {
    id("org.springframework.boot")
}

dependencies {
    implementation(platform(SpringBootPlugin.BOM_COORDINATES))
    implementation(project(":msika-core"))
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-security-oauth2-resource-server")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.apache.pdfbox:pdfbox:3.0.8")
    implementation("com.google.zxing:core:3.5.4")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    runtimeOnly("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.boot:spring-boot-starter-security-test")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testImplementation("com.tngtech.archunit:archunit:1.5.1")
    testImplementation("com.google.zxing:javase:3.5.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// Prints the java program and classpath that scripts/verify-seal runs. The script runs java itself instead of
// going through Gradle (a JavaExec task), because Gradle turns every non-zero exit into 1 and the verifier
// needs to tell "the ledger was altered" (1) from "could not run" (2).
val verifyLauncher = project.extensions.getByType<JavaToolchainService>().launcherFor {
    languageVersion.set(JavaLanguageVersion.of(21))
}
val verifyClasspath = sourceSets["main"].runtimeClasspath

tasks.register("printVerifySealsCommand") {
    group = "help"
    description = "Prints the java executable and runtime classpath used by scripts/verify-seal."
    dependsOn("classes")
    val launcher = verifyLauncher
    val classpath = verifyClasspath
    doLast {
        println(launcher.get().executablePath.asFile.absolutePath)
        println(classpath.asPath)
    }
}
