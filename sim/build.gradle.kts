dependencies {
    implementation(project(":raft-core"))
    implementation(project(":raft-storage"))
    implementation(project(":kv"))          // sharded KV state machine and server logic (Phase 7)
    implementation(project(":shardctrler")) // shard controller state machine (Phase 7)
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testImplementation("org.assertj:assertj-core:3.27.3")
}

tasks.test {
    // The Porcupine integration test needs tools/porcupine-check, which lives at the repo root.
    systemProperty("jraft.repoRoot", rootDir.absolutePath)
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

tasks.register<JavaExec>("shardChaos") {
    group = "verification"
    description = "Runs seeded sharded-store chaos (controller + 3 shard groups, joins/leaves under faults)."
    classpath = mainSourceSet.get().runtimeClasspath
    mainClass.set("jraft.sim.ShardedChaosTest")
    args("--seed", providers.gradleProperty("seed").orElse("1").get(),
        "--seeds", providers.gradleProperty("seeds").orElse("1").get())
}

tasks.register<JavaExec>("quickShardChaos") {
    group = "verification"
    description = "Runs 20 sharded chaos seeds."
    classpath = mainSourceSet.get().runtimeClasspath
    mainClass.set("jraft.sim.ShardedChaosTest")
    args("--seed", "1", "--seeds", "20")
}

tasks.register<JavaExec>("quickChaos") {
    group = "verification"
    description = "Runs 50 deterministic chaos seeds."
    classpath = mainSourceSet.get().runtimeClasspath
    mainClass.set("jraft.sim.ChaosTest")
    args("--seed", "1", "--seeds", "50")
}
