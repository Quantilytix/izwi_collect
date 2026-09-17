import java.util.Properties
import java.io.FileInputStream

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) load(FileInputStream(f))
}

// The relay holds the Hugging Face write token; this app only ever carries
// the narrowly scoped upload key below, matching the security requirement
// in PLAN_B_officer_voice_capture_app.md. Rotate by updating the relay's
// RELAY_API_KEY secret and this value together.
val relayBaseUrl = localProps.getProperty("relay.baseUrl") ?: "https://rairo-smart-q-voice-relay.hf.space"
val relayApiKey = localProps.getProperty("relay.apiKey") ?: ""

android {
    namespace = "com.quantilytix.izwi"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.quantilytix.izwi"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1"

        buildConfigField("String", "RELAY_BASE_URL", "\"$relayBaseUrl\"")
        buildConfigField("String", "RELAY_API_KEY", "\"$relayApiKey\"")
    }

    buildFeatures {
        buildConfig = true
        viewBinding = true
    }

    buildTypes {
        release { isMinifyEnabled = false }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.activity:activity-ktx:1.9.2")

    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Multipart batch upload to the relay — same rationale as Injini's
    // HfSync: hand-rolled multipart over HttpURLConnection is easy to get
    // subtly wrong for real training data uploads.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
