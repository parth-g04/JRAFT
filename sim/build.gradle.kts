dependencies {
    implementation(project(":raft-core"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testImplementation("org.assertj:assertj-core:3.27.3")
}

val mainSourceSet = sourceSets.named("main")

tasks.register<JavaExec>("chaos") {
    group = "verification"
    description = "Runs seeded deterministic Raft simulation workloads."
    classpath = mainSourceSet.get().runtimeClasspath
    mainClass.set("jraft.sim.ChaosTest")
    args("--seed", providers.gradleProperty("seed").orElse("1").get(),
        "--seeds", providers.gradleProperty("seeds").orElse("1").get())
}

tasks.register<JavaExec>("quickChaos") {
    group = "verification"
    description = "Runs 50 deterministic chaos seeds."
    classpath = mainSourceSet.get().runtimeClasspath
    mainClass.set("jraft.sim.ChaosTest")
    args("--seed", "1", "--seeds", "50")
}
