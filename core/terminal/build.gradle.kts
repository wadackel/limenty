plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api(project(":core:common"))

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)
}

tasks.withType<Test>().configureEach {
    // The throughput tests report their measured rates; without this the numbers are invisible.
    testLogging { showStandardStreams = true }

    // A way out for the benchmark, decided before it is needed rather than under pressure.
    //
    // `TerminalThroughputTest` asserts absolute rates and a heap ceiling. Those hold comfortably
    // on a machine that is not doing anything else, and a shared runner is not that machine. If it
    // starts failing on timing rather than on a regression, this is the switch — and the answer is
    // never to lower the floors, which would leave the test running and no longer measuring.
    //
    // JUnit 4 has no tags to exclude it by, so it is excluded by name.
    if (providers.gradleProperty("skipPerfTests").isPresent) {
        exclude("**/TerminalThroughputTest*")
    }
}
