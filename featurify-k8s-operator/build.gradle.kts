plugins {
    application
}

dependencies {
    implementation("io.javaoperatorsdk:operator-framework:5.6.0")
    runtimeOnly("org.slf4j:slf4j-simple:2.0.17")
    testImplementation(platform("org.junit:junit-bom:5.13.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.assertj:assertj-core:3.27.6")
    testImplementation("org.mockito:mockito-core:5.18.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application {
    mainClass = "ru.a1pha1337.featurify.operator.OperatorMain"
}
