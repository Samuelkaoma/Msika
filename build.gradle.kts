// Root build: settings shared by every module. Each module's own build.gradle.kts adds the rest.
// The Spring Boot plugin is declared here (version pinned exactly) and applied only in the apps.
plugins {
    id("org.springframework.boot") version "4.2.0" apply false
}

subprojects {
    apply(plugin = "java")

    group = "zm.msika"
    version = "0.1.0-SNAPSHOT"

    repositories {
        mavenCentral()
    }

    extensions.configure<JavaPluginExtension> {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(21))
        }
    }

    // Lint: the compiler's own warnings are errors. (-processing is off because annotation
    // processors we do not control may warn.)
    tasks.withType<JavaCompile> {
        options.compilerArgs.addAll(listOf("-Xlint:all,-processing", "-Werror"))
    }

    // `test` is the fast unit-test task. Tests tagged @Tag("integration") (they need Docker) are
    // left out of it and run by `integrationTest`, which `check` (and so `build`) includes.
    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
    }
    tasks.named<Test>("test") {
        useJUnitPlatform {
            excludeTags("integration")
        }
    }
    val integrationTest = tasks.register<Test>("integrationTest") {
        description = "Runs tests tagged 'integration' (need Docker)."
        group = "verification"
        val sourceSets = project.extensions.getByType<SourceSetContainer>()
        testClassesDirs = sourceSets["test"].output.classesDirs
        classpath = sourceSets["test"].runtimeClasspath
        useJUnitPlatform {
            includeTags("integration")
        }
        shouldRunAfter(tasks.named("test"))
    }
    tasks.named("check") {
        dependsOn(integrationTest)
    }
}
