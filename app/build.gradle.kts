import com.android.build.api.variant.BuildConfigField
import java.util.regex.Pattern

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.aboutlibraries)
    alias(libs.plugins.roborazzi)
}

android {
    namespace = "dev.wadackel.limenty"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.wadackel.limenty"
        minSdk = 30
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    lint {
        // A gate has to answer to the commit that ran it. These three checks compare the project
        // against whatever the world published this morning, so leaving them on means the build
        // turns red because someone else made a release — a verdict no change of ours can fix and
        // no change of ours caused.
        disable += setOf("GradleDependency", "NewerVersionAvailable", "AndroidGradlePluginVersion")
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            isMinifyEnabled = false
            buildConfigField("boolean", "VERBOSE_LOGGING", "true")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            buildConfigField("boolean", "VERBOSE_LOGGING", "false")
            // Distribution is sideloading to the author's own device, so
            // there is no store identity to protect and no upload key to manage yet.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "META-INF/DEPENDENCIES",
                "META-INF/INDEX.LIST",
            )
        }
    }

    testOptions {
        // Off for every instrumented run. On CI's software renderer a screen transition kept the
        // main thread drawing for about seven seconds, and Espresso waits until it stops, so each
        // test that opened a screen spent most of its time there. The tests wait for the screen
        // they expect rather than for a transition to end, so they check the same thing either way.
        animationsDisabled = true
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
        // The emulator CI's instrumentation job runs on: `./gw :app:ciDebugAndroidTest`. The same
        // profile as `tools/emu create`, so a test that passes on the AVD people iterate on meets
        // the same display in CI; the ATD image because it boots in about half the time.
        managedDevices {
            localDevices {
                create("ci") {
                    device = "7.6\" Fold-in with outer display"
                    apiLevel = 36
                    systemImageSource = "aosp-atd"
                }
            }
        }
    }
}

kotlin {
    jvmToolchain(17)
}

// The About screen names the commit a build came from, so a device can be matched to the tree it
// runs. Read lazily through the provider so the configuration cache still holds: git answers again
// on each build rather than the first answer being stored with the cached configuration.
val gitCommit = providers.exec {
    commandLine("git", "rev-parse", "--short=7", "HEAD")
    isIgnoreExitValue = true
}.standardOutput.asText.map { it.trim().ifEmpty { "unknown" } }

// A build from a tree with uncommitted changes is not the commit above, and `tools/dev run` builds
// exactly such trees onto the phone.
val gitDirty = providers.exec {
    commandLine("git", "status", "--porcelain")
    isIgnoreExitValue = true
}.standardOutput.asText.map { it.isNotBlank() }

/** Puts the repository's LICENSE in the APK, where the About screen reads it. */
abstract class OwnLicenseAsset : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val license: RegularFileProperty

    @get:OutputDirectory
    abstract val output: DirectoryProperty

    @TaskAction
    fun copy() {
        license.get().asFile.copyTo(output.get().file("licenses/Limenty-LICENSE.txt").asFile, overwrite = true)
    }
}

val ownLicenseAsset = tasks.register<OwnLicenseAsset>("ownLicenseAsset") {
    license.set(rootProject.layout.projectDirectory.file("LICENSE"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(ownLicenseAsset, OwnLicenseAsset::output)
        variant.buildConfigFields?.put("GIT_COMMIT", gitCommit.map { BuildConfigField("String", "\"$it\"", null) })
        variant.buildConfigFields?.put("GIT_DIRTY", gitDirty.map { BuildConfigField("boolean", "$it", null) })
    }
}

aboutLibraries {
    // Offline because the plugin otherwise downloads each licence text from spdx.org during the
    // build and, when that request fails, ships the entry without its text and says nothing. The
    // texts live in aboutlibraries/licenses instead, and a unit test fails on any entry left empty.
    offlineMode = true
    collect {
        configPath = file("aboutlibraries")
    }
    export {
        excludeFields.addAll("description", "developers", "funding", "scm", "organization")
    }
    library {
        mergePlatformArtifacts = true
        // A BOM only pins versions; nothing of it is in the APK.
        exclusionPatterns.add(Pattern.compile(".*-bom"))
    }
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:terminal"))
    implementation(project(":core:input"))
    implementation(project(":core:transport"))
    implementation(project(":core:session"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.window)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.roborazzi)

    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.truth)
    androidTestImplementation(libs.sshj)
    debugImplementation(libs.compose.ui.test.manifest)
}

// Robolectric fetches its Android jar from Maven Central itself, inside the test JVM, which puts it
// outside Gradle's cache and retries: CI downloaded it on every run and failed when the download
// did. Resolved here instead, it comes from the same cache as every other dependency.
val robolectricAndroidAll: Configuration by configurations.creating { isTransitive = false }

dependencies {
    robolectricAndroidAll(libs.robolectric.android.all)
}

val robolectricJars = tasks.register<Sync>("robolectricJars") {
    from(robolectricAndroidAll)
    into(layout.buildDirectory.dir("robolectric-jars"))
}

tasks.withType<Test>().configureEach {
    inputs.files(robolectricJars).withPropertyName("robolectricJars").withPathSensitivity(PathSensitivity.NAME_ONLY)
    systemProperty("robolectric.offline", "true")
    systemProperty("robolectric.dependency.dir", layout.buildDirectory.dir("robolectric-jars").get().asFile.absolutePath)
}
