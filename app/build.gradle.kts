plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}
android {
    namespace = "app.vocr"
    compileSdk = 36
    defaultConfig {
        applicationId = "app.scrollrsi.sphere"   // sphere prototype variant: installs beside frozen 109, can never replace it
        minSdk = 30
        targetSdk = 36
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
        versionCode = 2
        versionName = "0.2.0-f1"   // sphere v1 foundation slice 1: the sphere is the interface (brief v2)
    }
    val ksPath = System.getenv("VOCR_KS_PATH")
    signingConfigs {
        if (ksPath != null) create("vocrRelease") {
            storeFile = file(ksPath); storePassword = System.getenv("VOCR_KS_PASSWORD")
            keyAlias = "vocr"; keyPassword = System.getenv("VOCR_KS_PASSWORD")
        }
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
    buildTypes { release { isMinifyEnabled = false; isDebuggable = false
        signingConfig = if (ksPath != null) signingConfigs.getByName("vocrRelease") else signingConfigs.getByName("debug") } }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    lint { checkReleaseBuilds = false; abortOnError = false }
}
dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.dynamicanimation:dynamicanimation:1.1.0")
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.22.0")
    // Account service (scaffold): Firebase Auth + Google sign-in. No google-services plugin: the app stays
    // buildable and usable with NO configuration; Firebase initialises only if a local google-services.json exists.
    // pinned, not the latest BoM: firebase-auth 24+ needs Kotlin 2.1+ metadata; this project is on Kotlin 2.0.20.
    // play-services-auth 22 removed the legacy GoogleSignIn API this scaffold uses.
    implementation("com.google.firebase:firebase-auth:23.2.1")
    implementation("com.google.android.gms:play-services-auth:21.5.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("com.microsoft.onnxruntime:onnxruntime:1.22.0")
    testImplementation("org.robolectric:robolectric:4.14.1")
}
tasks.withType<Test> { System.getProperty("nb")?.let { systemProperty("nb", it) }; maxHeapSize = "768m" }   // the OCR tests load two ONNX models: cap the forked JVM so small dev boxes survive
