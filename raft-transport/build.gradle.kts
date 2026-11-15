dependencies {
    implementation(project(":raft-core"))
    implementation(project(":raft-storage"))
    implementation(project(":kv"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testImplementation("org.assertj:assertj-core:3.27.3")
}
