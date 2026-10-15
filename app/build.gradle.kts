plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}
android {
    namespace = "app.vocr"
    compileSdk = 36
    defaultConfig {
        applicationId = "app.scrollrsi"   // permanent Play package ID; the code namespace stays app.vocr
        minSdk = 29
        targetSdk = 36
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
        versionCode = 70
        versionName = "0.10.8-t1"
    }
    val ksPath = System.getenv("VOCR_KS_PATH")
    signingConfigs {
        if (ksPath != null) create("vocrRelease") {
            storeFile = file(ksPath); storePassword = System.getenv("VOCR_KS_PASSWORD")
            keyAlias = "vocr"; keyPassword = System.getenv("VOCR_KS_PASSWORD")
        }
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
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.22.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("com.microsoft.onnxruntime:onnxruntime:1.22.0")
}
tasks.withType<Test> { System.getProperty("nb")?.let { systemProperty("nb", it) } }
