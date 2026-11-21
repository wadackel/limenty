plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api(project(":core:common"))

    // SSHJ is the only place an SSH library is named; everything above TerminalTransport is
    // written against the interface so the choice stays replaceable.
    implementation(libs.sshj)
    // Android ships a cut-down BouncyCastle that lacks the curve25519 and ed25519 algorithms
    // modern OpenSSH negotiates, so the full provider is bundled and registered explicitly.
    implementation(libs.bouncycastle.prov)
    implementation(libs.bouncycastle.pkix)
    implementation(libs.slf4j.api)

    // A real SSH server, in-process, so authentication, PTY allocation, window-change and host
    // key handling are exercised against the protocol rather than against a mock of it.
    testImplementation(libs.mina.sshd.core)
    testImplementation(libs.slf4j.simple)
    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)
}

tasks.withType<Test>().configureEach {
    // The embedded server's own diagnostics are the only way to see why it rejected a request.
    systemProperty("org.slf4j.simpleLogger.defaultLogLevel", providers.gradleProperty("sshLogLevel").getOrElse("warn"))
    testLogging { showStandardStreams = false }
}

// Starts the embedded SSH server on a fixed port for device-side verification:
//   ./gw :core:transport:runTestSshServer -PsshPort=2222
tasks.register<JavaExec>("runTestSshServer") {
    group = "verification"
    description = "Runs the embedded test SSH server so an emulator or device can connect to it."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("dev.wadackel.limenty.core.transport.TestSshServerMain")
    args(providers.gradleProperty("sshPort").getOrElse("2222"))
}
