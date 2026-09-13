plugins {
    `java-library`
    `maven-publish`
    kotlin("jvm")
    kotlin("plugin.spring")
}

java {
    withSourcesJar()
}

dependencies {
    val lombokVersion = "1.18.48"
    compileOnly("org.projectlombok:lombok:$lombokVersion")
    annotationProcessor("org.projectlombok:lombok:$lombokVersion")

    api(project(":featurify-grpc-client"))
    // 2.x supports Java 8; use APIs shared with the 3.x managed by modern Boot applications.
    implementation("com.github.ben-manes.caffeine:caffeine:2.9.3")
    // Spring is provided by the host application. Never bring the server's Boot BOM into clients.
    compileOnly("org.springframework.boot:spring-boot-autoconfigure:2.0.9.RELEASE")
    testImplementation(kotlin("stdlib"))
    testImplementation(platform("org.springframework.boot:spring-boot-dependencies:4.1.1"))
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("io.grpc:grpc-netty-shaded")
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
