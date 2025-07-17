import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlinSerialization)
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
    compileSdk = 36

    defaultConfig {
        applicationId = "com.valhalla.loki"
        minSdk = 24
        targetSdk = 36
        versionCode = 10000
        versionName = "1.00.00"
        vectorDrawables.useSupportLibrary = true
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro"
            )
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

    /// LibSU
    implementation(libs.topjohnwu.libsu.core)

    /// Koin
    implementation(libs.koin.android)
    implementation(libs.koin.androidx.compose)

    /// Shizuku
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)

}