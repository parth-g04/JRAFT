plugins { `java-library` }

dependencies {
    implementation(project(":raft-core"))
    // raft_fsync_seconds histogram (ARCHITECTURE.md section 8).
    api("io.micrometer:micrometer-core:1.14.5")
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testImplementation("org.assertj:assertj-core:3.27.3")
}
