plugins {
    application
    id("io.spring.dependency-management")
}

dependencyManagement {
    imports {
        mavenBom("io.fabric8:kubernetes-client-bom:7.9.0")
    }
}

dependencies {
    implementation("io.javaoperatorsdk:operator-framework-spring-boot-starter:6.7.0") {
        exclude(group = "org.springframework.boot", module = "spring-boot-starter-web")
    }
    // Starter 6.7.0 defaults to JOSDK 5.5.0; keep the SDK and Fabric8 current together.
    implementation("io.javaoperatorsdk:operator-framework:5.6.1")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("io.fabric8:kubernetes-server-mock")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application {
    mainClass = "ru.a1pha1337.featurify.operator.OperatorMain"
}

tasks.startScripts {
    doLast {
        // The expanded dependency list exceeds cmd.exe's command-line limit on Windows.
        windowsScript.writeText(
            windowsScript.readText().replace(Regex("(?m)^set CLASSPATH=.*$")) {
                "set CLASSPATH=%APP_HOME%\\lib\\*"
            },
        )
    }
}
