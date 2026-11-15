// Shard controller (Phase 7): replicated configuration service. Pure state machine, no I/O.
plugins { `java-library` }

dependencies {
    api(project(":raft-core"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testImplementation("org.assertj:assertj-core:3.27.3")
}
