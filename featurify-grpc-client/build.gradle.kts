plugins {
    `java-library`
    `maven-publish`
    kotlin("jvm") // Kotlin is used only for tests, not in the published library.
}

java { withSourcesJar() }

dependencies {
    api(project(":featurify-grpc-api"))
    implementation("io.grpc:grpc-netty-shaded")
    testImplementation(kotlin("stdlib"))
    testImplementation(platform("org.junit:junit-bom:5.14.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.assertj:assertj-core:3.27.7")
    testImplementation("io.grpc:grpc-inprocess")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])
            versionMapping {
                usage("java-api") { fromResolutionOf("runtimeClasspath") }
                usage("java-runtime") { fromResolutionResult() }
            }
        }
    }
}
