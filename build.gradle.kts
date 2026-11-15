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
    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        // `release` pins the Java 21 API and bytecode while allowing any JDK >= 21
        // to run the build (CI uses 21; local machines may have a newer JDK).
        options.release.set(21)
    }
    dependencies {
        // Gradle 9 no longer puts the JUnit Platform launcher on the test classpath
        // implicitly; without it every test task fails with "Failed to load JUnit Platform".
        "testImplementation"(platform("org.junit:junit-bom:5.11.4"))
        "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
    }
    tasks.withType<Test>().configureEach { useJUnitPlatform() }
}

tasks.named<Test>("test") {
    dependsOn(":sim:quickChaos")
    dependsOn(subprojects.map { "${it.path}:test" })
}
