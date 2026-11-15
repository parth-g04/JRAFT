// Storage microbenchmarks (Phases 3 and 5); system benchmarks follow in Phase 8.
dependencies {
    implementation(project(":raft-core"))
    implementation(project(":raft-storage"))
    implementation(project(":sim"))   // KvStateMachine for the recovery benchmark
}

tasks.register<JavaExec>("storageBenchmark") {
    group = "benchmark"
    description = "Measures WAL batch force behavior for 0, 1, 2, and 5 ms group commit windows."
    classpath = sourceSets.named("main").get().runtimeClasspath
    mainClass.set("jraft.bench.StorageBenchmark")
}

tasks.register<JavaExec>("recoveryBenchmark") {
    group = "benchmark"
    description = "Recovery time for a 1M-entry log, with and without a snapshot (-Pentries=N to change)."
    classpath = sourceSets.named("main").get().runtimeClasspath
    mainClass.set("jraft.bench.RecoveryBenchmark")
    maxHeapSize = "3g"
    args(providers.gradleProperty("entries").orElse("1000000").get())
}
