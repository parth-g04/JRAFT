plugins {
    java
}

allprojects {
    group = "jraft"
    version = "0.1.0-SNAPSHOT"
    repositories { mavenCentral() }
}

subprojects {
    apply(plugin = "java")
    extensions.configure<JavaPluginExtension> {
        toolchain.languageVersion.set(JavaLanguageVersion.of(21))
    }
    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.release.set(21)
    }
    tasks.withType<Test>().configureEach { useJUnitPlatform() }
}

tasks.named<Test>("test") {
    dependsOn(":sim:quickChaos")
    dependsOn(subprojects.map { "${it.path}:test" })
}
