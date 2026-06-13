import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// Load API credentials from local.properties (NOT committed) so keys never enter VCS.
// Example local.properties entries:
//   XFYUN_APP_ID=xxxx
//   XFYUN_API_KEY=xxxx
//   DASHSCOPE_API_KEY=sk-xxxx
//   VOLC_APP_ID=xxxx
//   VOLC_ACCESS_KEY=xxxx
//   VOLC_RESOURCE_ID=volc.bigasr.sauc.duration
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun secret(key: String): String = (localProps.getProperty(key) ?: "")

android {
    namespace = "com.overmind.meetingscribe"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.overmind.meetingscribe"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        vectorDrawables { useSupportLibrary = true }

        ndk {
            // sherpa-onnx ships these ABIs. x86_64 kept for emulator testing.
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }

        // Compile-time defaults for online credentials. Empty unless set in local.properties.
        buildConfigField("String", "XFYUN_APP_ID", "\"${secret("XFYUN_APP_ID")}\"")
        buildConfigField("String", "XFYUN_API_KEY", "\"${secret("XFYUN_API_KEY")}\"")
        buildConfigField("String", "DASHSCOPE_API_KEY", "\"${secret("DASHSCOPE_API_KEY")}\"")
        buildConfigField("String", "VOLC_APP_ID", "\"${secret("VOLC_APP_ID")}\"")
        buildConfigField("String", "VOLC_ACCESS_KEY", "\"${secret("VOLC_ACCESS_KEY")}\"")
        buildConfigField("String", "VOLC_RESOURCE_ID", "\"${secret("VOLC_RESOURCE_ID").ifEmpty { "volc.bigasr.sauc.duration" }}\"")
    }

    buildTypes {
        release {
            // R8 code shrinking + obfuscation. Keep rules for the sherpa-onnx JNI bridge already
            // live in proguard-rules.pro; verify the release build on-device, since this project
            // has not been compiled in this environment.
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/DEPENDENCIES",
                "/META-INF/INDEX.LIST",
                "META-INF/*.kotlin_module",
            )
        }
        jniLibs {
            useLegacyPackaging = false
            pickFirsts += setOf("**/libc++_shared.so")
        }
    }
}

dependencies {
    // --- sherpa-onnx (on-device ASR + VAD + speaker diarization) ---
    // Primary path: prebuilt .aar dropped into app/libs/ (see README "模型与依赖").
    // fileTree tolerates an empty/absent libs/ folder, so Gradle sync never breaks.
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar", "*.jar"))))
    // If/when the official Maven artifact is preferred, replace the line above with:
    //   implementation("com.k2-fsa:sherpa-onnx:<version>")
    // (confirm the exact coordinate/version on the sherpa-onnx releases page).

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)
    implementation(libs.commons.compress)

    testImplementation(libs.junit)
}
