plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "uk.noammm.kav"
    compileSdk = 36

    defaultConfig {
        applicationId = "uk.noammm.kav"
        minSdk = 26
        targetSdk = 35
        // CI builds count up from 1000 so every build installs as an update.
        val ci = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull()
        versionCode = if (ci != null) 1000 + ci else 19
        // Builds from beta/ branches: own version label and their own update channel.
        val beta = System.getenv("KAV_BETA") == "true"
        versionName = if (ci != null) (if (beta) "2.0-beta.$ci" else "2.0-gigi.$ci") else "2.0"
        buildConfigField("boolean", "BETA", beta.toString())
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // MapLibre's renderer is native code. Every phone Kav can reach is arm64;
        // x86_64 stays so the release APK still installs on the emulator.
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }

    // A fixed key in the repo, so every CI build installs over the last one.
    val kavKey = rootProject.file("../signing/debug.keystore")
    signingConfigs {
        create("kav") {
            storeFile = kavKey
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            // Signed with the local debug key: there is no store listing, and an update
            // only installs over the previous one if both carry the same signature.
            signingConfig = signingConfigs.getByName(if (kavKey.exists()) "kav" else "debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }

    packaging { resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" } }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("org.maplibre.gl:android-sdk:12.3.1")
    debugImplementation("androidx.compose.ui:ui-tooling")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
