import com.google.protobuf.gradle.id

plugins {
    `java-library`
    `maven-publish`
    kotlin("jvm")
    id("io.spring.dependency-management")
    id("com.google.protobuf")
}

java {
    withSourcesJar()
}

dependencies {
    api("jakarta.validation:jakarta.validation-api")
    api("com.google.protobuf:protobuf-java")
    api("io.grpc:grpc-protobuf")
    api("io.grpc:grpc-stub")
    testImplementation("org.assertj:assertj-core")
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// Generate the contract once, using the same dependency versions as the service.
protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:${dependencyManagement.importedProperties["protobuf-java.version"]}"
    }
    plugins {
        id("grpc") {
            artifact = "io.grpc:protoc-gen-grpc-java:${dependencyManagement.importedProperties["grpc-java.version"]}"
        }
    }
    generateProtoTasks {
        all().configureEach {
            plugins { id("grpc") {} }
        }
    }
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
