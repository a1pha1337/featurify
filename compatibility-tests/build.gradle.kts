plugins { java }

repositories {
    maven { url = uri("../build/compatibility-repository") }
    mavenCentral()
}

val featurifyVersion = providers.gradleProperty("featurifyVersion").getOrElse("0.0.1-SNAPSHOT")

java {
    toolchain.languageVersion = JavaLanguageVersion.of(25)
}
tasks.withType<JavaCompile>().configureEach { options.release = 8 }

dependencies {
    compileOnly("ru.a1pha1337:featurify-grpc-starter:$featurifyVersion")
    compileOnly("org.springframework.boot:spring-boot-autoconfigure:2.0.9.RELEASE")
    compileOnly("io.grpc:grpc-netty-shaded:1.83.1")
}

// Each runtime has only the dependencies of an independent consuming application.
// No project dependencies, Kotlin, server BOM, or test framework can mask linkage errors.
val versions = mapOf("boot20" to "2.0.9.RELEASE", "boot26" to "2.6.15", "boot27" to "2.7.18", "boot35" to "3.5.16", "boot41" to "4.1.1")
versions.forEach { (name, version) ->
    val runtime = configurations.create(name) {
        isCanBeConsumed = false
        attributes {
            attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
            attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.LIBRARY))
        }
    }
    dependencies {
        add(name, platform("org.springframework.boot:spring-boot-dependencies:$version"))
        add(name, "org.springframework.boot:spring-boot-starter")
        add(name, "ru.a1pha1337:featurify-grpc-starter:$featurifyVersion")
    }
    val verify = tasks.register<JavaExec>(name) {
        dependsOn(tasks.classes)
        classpath = sourceSets.main.get().output + runtime
        mainClass = "compatibility.CompatibilitySmoke"
        args(version)
        javaLauncher = javaToolchains.launcherFor {
            languageVersion = JavaLanguageVersion.of(if (name.startsWith("boot2")) 8 else if (name == "boot41") 25 else 21)
        }
    }
    tasks.check { dependsOn(verify) }
}
