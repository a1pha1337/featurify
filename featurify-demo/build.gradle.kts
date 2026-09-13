plugins {
    kotlin("jvm")
    kotlin("plugin.spring")
    id("io.spring.dependency-management")
    id("org.springframework.boot")
}

dependencies {
    implementation(project(":featurify-grpc-starter"))
    implementation(kotlin("stdlib"))
    implementation(kotlin("reflect"))
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("tools.jackson.module:jackson-module-kotlin")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("io.grpc:grpc-netty-shaded")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
