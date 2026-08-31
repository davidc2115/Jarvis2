plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.jarvis2.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.jarvis2.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0-foundations"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }
    }

    // Release signing sourced from environment variables so a real keystore
    // never has to be committed to the repo. Locally these env vars are
    // simply unset and the release build stays unsigned (still compiles —
    // useful to check minification/shrinking works — just not installable
    // as-is). In CI, .github/workflows/android-ci.yml decodes the
    // RELEASE_KEYSTORE_BASE64 secret to this path and exports the rest,
    // so a tagged release build comes out signed and installable.
    signingConfigs {
        val releaseStoreFile = System.getenv("RELEASE_STORE_FILE")
        if (!releaseStoreFile.isNullOrBlank() && file(releaseStoreFile).exists()) {
            create("release") {
                storeFile = file(releaseStoreFile)
                storePassword = System.getenv("RELEASE_STORE_PASSWORD")
                keyAlias = System.getenv("RELEASE_KEY_ALIAS")
                keyPassword = System.getenv("RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
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
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(libs.core.ktx)
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.activity.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.navigation.compose)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    implementation(libs.koin.android)
    implementation(libs.koin.androidx.compose)

    implementation(libs.datastore.preferences)
    implementation(libs.security.crypto)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.work.runtime.ktx)
    implementation(libs.accompanist.permissions)
    implementation(libs.okhttp)
    implementation(libs.documentfile)

    // Llamatik: AAR pre-construit (llama.cpp texte + libmtmd/CLIP vision) --
    // voir ai/smolvlm/SmolVlmEngine.kt pour le moteur local par defaut
    // (SmolVLM2, telecharge automatiquement, aucun compte requis).
    implementation(libs.llamatik)

    // --- Local AI engines -------------------------------------------------
    // MediaPipe LLM Inference API: stable, well-documented, runs any bundled
    // .task model (e.g. Gemma) fully offline. Used as the universal fallback
    // on every device, and as the only engine on phones without AICore.
    implementation(libs.mediapipe.genai)

    // AICore (Gemini Nano) client SDK. This artifact is still labelled
    // experimental by Google and its group/artifact id or API surface may
    // have moved by the time you build this — if `com.google.ai.edge.aicore`
    // fails to resolve, check https://developer.android.com/ai/gemini-nano
    // for the current coordinates and adjust AiCoreEngine.kt accordingly.
    // The app is fully functional without it (falls back to MediaPipe).
    implementation(libs.aicore)

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation(platform(libs.compose.bom))
}
