// v1 server (default package) plus the Phase 7 sharded KV state machine and server logic (jraft.kv).
plugins { `java-library` }

dependencies {
    api(project(":raft-core"))
    api(project(":shardctrler"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testImplementation("org.assertj:assertj-core:3.27.3")
}
