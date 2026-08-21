import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlinSerialization)
}

// --- SIGNING ---
//
// Two sources, checked in this order: a local `jks.properties` at the repo root (git-ignored), or
// the CI environment variables the release workflow exports. Neither is required to build.
val keystorePropertiesFile: File = rootProject.file("jks.properties")

// The four values a release signingConfig needs, resolved once from whichever source has them,
// or null when this machine cannot sign at all. Single-sourced because the `signingConfigs`
// block and the `release` build type both need the same answer — see the comment on
// `signingConfig` below for why the build type has to ask rather than just assign.
//
// All four together, as ONE credential set, because a partial set is not weaker signing — it is
// a build that fails later and somewhere else. A `jks.properties` missing one key used to reach
// `keystoreProperties["keyPassword"] as String` and throw an NPE naming the cast rather than the
// key; an environment with only some of the variables set used to assign a config with an empty
// keyAlias and then die in the signing step itself, which names neither the source nor the value.
//
// Not `validateSigningRelease`, incidentally — that task checks only that a keystore file is set
// and present, so it passes with credentials that cannot open it. Wrong credentials survive all
// the way to `:app:packageRelease`, which reports "Failed to read key <alias> from store <path>:
// keystore password was incorrect". Failing at configuration time, as this block does, is the
// whole point: an R8 run happens in between.
//
// Blank counts as absent, not present. `${{ secrets.KEY_ALIAS }}` expands to the EMPTY STRING
// when the secret does not exist, so a `!= null` test would report credentials on a repository
// that has none — which is the state Loki is actually in until the four signing secrets are added.
val signingCredentials: Map<String, String>? = run {
    fun require(source: String, values: Map<String, String?>): Map<String, String> {
        val missing = values.filterValues { it.isNullOrBlank() }.keys
        if (missing.isNotEmpty()) {
            throw GradleException(
                "Signing is partially configured. $source supplies " +
                    "${(values.keys - missing).joinToString(", ")}, but " +
                    "${missing.joinToString(", ")} is missing or blank. All four are one " +
                    "credential set — fill in the rest, or remove the lot to build unsigned. " +
                    "See docs/branching-and-releases.md."
            )
        }
        return values.mapValues { (_, value) -> value!! }
    }

    // A jks.properties that exists is a statement of intent to sign, so a hole in it is an
    // error rather than a reason to look at the environment: silently falling through would
    // produce an unsigned APK from a machine that plainly meant to sign one.
    if (keystorePropertiesFile.exists()) {
        val props = Properties()
        FileInputStream(keystorePropertiesFile).use { props.load(it) }
        return@run require(
            "jks.properties",
            mapOf(
                "keyAlias" to props.getProperty("keyAlias"),
                "keyPassword" to props.getProperty("keyPassword"),
                "storePassword" to props.getProperty("storePassword"),
                "storeFile" to props.getProperty("storeFile"),
            ),
        )
    }

    // CI (GitHub Actions) — the release workflow decodes the keystore and exports these.
    val env = mapOf(
        "keyAlias" to System.getenv("KEY_ALIAS"),
        "keyPassword" to System.getenv("KEY_PASSWORD"),
        "storePassword" to System.getenv("KEYSTORE_PASSWORD"),
        "storeFile" to System.getenv("KEYSTORE_FILE_PATH"),
    )
    if (env.values.all { it.isNullOrBlank() }) null else require("the environment", env)
}

val hasSigningCredentials: Boolean = signingCredentials != null

// --- VERSIONING ---
//
// `versionCode` in gradle.properties is the only number a human edits. Both values below are
// derived from it, so the APK, the git tag, the GitHub release and the release-notes directory
// cannot disagree about what version this is.

private fun resolveVersionCode(): Int =
    providers.gradleProperty("versionCode").orNull?.toIntOrNull()
        ?: throw GradleException(
            "Required 'versionCode' missing or non-numeric in gradle.properties. " +
                "It is the single source of truth for the version — see " +
                "docs/branching-and-releases.md."
        )

// 10000 -> 1.0.0. Two digits per segment; the scheme and the reason for it are documented on
// `versionCode` in gradle.properties. .github/scripts/detect-version-bump.sh reproduces this exact
// arithmetic for CI, and .github/scripts/test/test-detect-version-bump.sh pins the pair together.
private fun calculateVersionName(code: Int): String {
    val major = code / 10000
    val minor = (code % 10000) / 100
    val patch = code % 100
    return "$major.$minor.$patch"
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
        optIn.add("kotlin.RequiresOptIn")
        optIn.add("kotlin.time.ExperimentalTime")
        optIn.add("androidx.compose.material3.ExperimentalMaterial3ExpressiveApi")
    }
}

android {

    namespace = "com.valhalla.loki"

    // 37, not 36, because the Compose BOM pinned in gradle/libs.versions.toml
    // (2026.08.00 / material3 1.5.0-alpha26) publishes AAR metadata demanding API 37 of
    // anything that depends on it. At 36 the build died in `checkDebugAarMetadata` with 17
    // such issues before compiling a line — so `assembleDebug` did not work on a clean
    // checkout, and no CI check could have been green.
    //
    // compileSdk only widens the API surface available at compile time; `targetSdk` below
    // stays at 36 deliberately. Raising THAT opts the app in to new runtime behaviour and
    // needs testing, which is a separate change from making the build run at all.
    compileSdk = 37

    defaultConfig {
        applicationId = "com.valhalla.loki"
        minSdk = 24
        targetSdk = 36

        val code = resolveVersionCode()
        versionCode = code
        versionName = calculateVersionName(code)

        vectorDrawables.useSupportLibrary = true
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            val credentials = signingCredentials
            if (credentials != null) {
                keyAlias = credentials.getValue("keyAlias")
                keyPassword = credentials.getValue("keyPassword")
                storePassword = credentials.getValue("storePassword")
                // rootProject.file, not file. This script's `file()` resolves a relative path
                // against app/, but jks.properties sits at the REPO ROOT — so `storeFile=loki.jks`,
                // which is what anyone writes when the key is sitting next to the properties file
                // that names it, would resolve to app/loki.jks and fail with a missing-file error
                // pointing at a directory the key was never in. Absolute paths are unaffected,
                // which is what CI passes via KEYSTORE_FILE_PATH.
                storeFile = rootProject.file(credentials.getValue("storeFile"))
            } else {
                logger.lifecycle(
                    "No jks.properties and no signing credentials in the environment — the " +
                        "release APK will be UNSIGNED. That is the correct result for a source " +
                        "rebuild; signing happens downstream."
                )
            }
        }
    }

    // Strips the dependency-metadata blob AGP otherwise embeds in the APK. It is signed with a
    // Google key and is not reproducible from source, so an F-Droid/IzzyOnDroid rebuild of the same
    // commit produces a different file with it present. Kept in the bundle, where Play wants it.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = true
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro"
            )
            // Assign the config only when credentials actually exist. The `release` config above
            // leaves every field unset in the no-credentials case, and an assigned-but-empty config
            // fails `validateSigningRelease` with "Keystore file not set for signing config
            // release" — so a fresh clone, and anyone rebuilding from source, could not produce a
            // release APK at all. Unsigned output is the right answer there. CI and local signing
            // are unaffected because both satisfy the condition.
            signingConfig = signingConfigs.getByName("release").takeIf { hasSigningCredentials }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    buildFeatures {
        buildConfig = true
        compose = true
    }
}

dependencies {

    /// Core
    implementation(libs.androidx.core.ktx)

    /// Splash Screen
    implementation(libs.androidx.splashscreen)

    /// Compose
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)

    /// Lifecycle
    implementation(libs.androidx.lifecycle.runtime.ktx)

    /// Test
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)

    /// Kotlinx Serialization
    implementation(libs.kotlinx.serialization.json)

    /// Drawable Painter
    implementation(libs.accompanist.drawablepainter)

    /// Root shell — Odin (published com.trinadhthatakula:odin, replaced libsu 6.0.0)
    implementation(libs.odin)

    /// Coroutines
    // Loki imports kotlinx.coroutines all over `:app` without declaring it — until now the version
    // arrived transitively from lifecycle/compose (1.9.0), and adding Odin would have silently
    // bumped it to whatever Odin's `api(kotlinx-coroutines-android)` ships. Declared here so the
    // version is Loki's decision, and so coroutines-test stays on the same one.
    implementation(libs.kotlinx.coroutines.android)

    /// Koin
    implementation(libs.koin.android)
    implementation(libs.koin.androidx.compose)

    /// Shizuku
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)

}