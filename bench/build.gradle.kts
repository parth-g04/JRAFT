// Storage microbenchmark introduced for Phase 3; system benchmarks follow in Phase 8.
dependencies {
    implementation(project(":raft-core"))
    implementation(project(":raft-storage"))
}

tasks.register<JavaExec>("storageBenchmark") {
    group = "benchmark"
    description = "Measures WAL batch force behavior for 0, 1, 2, and 5 ms group commit windows."
    classpath = sourceSets.named("main").get().runtimeClasspath
    mainClass.set("jraft.bench.StorageBenchmark")
}
