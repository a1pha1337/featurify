import io.spring.gradle.dependencymanagement.dsl.DependencyManagementExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension
import org.springframework.boot.gradle.plugin.SpringBootPlugin

plugins {
    base
    kotlin("jvm") version "2.3.21" apply false
    kotlin("plugin.spring") version "2.3.21" apply false
    id("org.springframework.boot") version "4.1.1" apply false
    id("io.spring.dependency-management") version "1.1.7" apply false
    id("com.vaadin") version "25.2.6" apply false
    id("com.google.protobuf") version "0.10.0" apply false
    id("org.jlleitschuh.gradle.ktlint") version "14.2.0" apply false
}

allprojects {
    group = "ru.a1pha1337"
    version = "0.0.1-SNAPSHOT"
    repositories {
        mavenCentral()
    }
}

subprojects {
    apply(plugin = "java")
    apply(plugin = "org.jlleitschuh.gradle.ktlint")

    plugins.withId("io.spring.dependency-management") {
        extensions.configure<DependencyManagementExtension> {
            imports {
                mavenBom(SpringBootPlugin.BOM_COORDINATES)
            }
        }
    }
    extensions.configure<JavaPluginExtension> {
        toolchain.languageVersion = JavaLanguageVersion.of(25)
    }
    plugins.withId("org.jetbrains.kotlin.jvm") {
        extensions.configure<KotlinJvmProjectExtension> {
            compilerOptions {
                freeCompilerArgs.addAll("-Xjsr305=strict", "-Xannotation-default-target=param-property")
            }
        }
    }
    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
    }

    if (name.startsWith("featurify-grpc-")) {
        plugins.withId("maven-publish") {
            extensions.configure<PublishingExtension> {
                repositories {
                    maven {
                        name = "compatibility"
                        url = rootProject.layout.buildDirectory.dir("compatibility-repository").get().asFile.toURI()
                    }
                }
            }
        }
        tasks.named<JavaCompile>("compileJava") {
            options.release = 8
        }
        plugins.withId("org.jetbrains.kotlin.jvm") {
            tasks.named<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>("compileKotlin") {
                compilerOptions.jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_1_8
            }
        }
    }
}

tasks.named("assemble") {
    dependsOn(subprojects.map { "${it.path}:assemble" })
}
tasks.named("check") {
    dependsOn(subprojects.map { "${it.path}:check" })
}
tasks.named("clean") {
    dependsOn(subprojects.map { "${it.path}:clean" })
}

tasks.register<GradleBuild>("verifyClientCompatibility") {
    group = "verification"
    description = "Tests published client jars in independent Boot 2.0, 2.6, 2.7, 3.5 and 4.1 applications. Requires JDK 8, 21 and 25."
    dependsOn(
        ":featurify-grpc-api:publishMavenJavaPublicationToCompatibilityRepository",
        ":featurify-grpc-client:publishMavenJavaPublicationToCompatibilityRepository",
        ":featurify-grpc-starter:publishMavenJavaPublicationToCompatibilityRepository",
    )
    dir = file("compatibility-tests")
    tasks = listOf("check")
    startParameter.projectProperties = mapOf("featurifyVersion" to project.version.toString())
}
