import com.google.protobuf.gradle.id

plugins {
    `java-library`
    `maven-publish`
    id("com.google.protobuf")
}

java { withSourcesJar() }

dependencies {
    api(platform("io.grpc:grpc-bom:${property("grpcVersion")}"))
    api("io.grpc:grpc-protobuf")
    api("io.grpc:grpc-stub")
    api("com.google.protobuf:protobuf-java:${property("protobufVersion")}")
}

protobuf {
    protoc { artifact = "com.google.protobuf:protoc:${property("protobufVersion")}" }
    plugins {
        id("grpc") { artifact = "io.grpc:protoc-gen-grpc-java:${property("grpcVersion")}" }
    }
    generateProtoTasks {
        all().configureEach { plugins { id("grpc") {} } }
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
