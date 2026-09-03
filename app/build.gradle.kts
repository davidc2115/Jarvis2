plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.jarvis2.app"
    // compileSdk 36 requis par com.llamatik:library-android:1.10.1 (voir AAR metadata
    // check en CI) -- targetSdk reste a 35 pour l'instant, cette bascule est independante
    // (voir note officielle AGP : compileSdk peut monter sans toucher targetSdk/minSdk).
    compileSdk = 36

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
        // Keystore de debug FIXE, committe dans le repo (app/debug.keystore).
        // Ce n'est pas un secret : c'est exactement equivalent au
        // ~/.android/debug.keystore qu'AGP genere automatiquement en local,
        // sauf qu'ici il est identique a chaque build au lieu d'etre
        // regenere aleatoirement a chaque run CI. Sans ca, chaque APK debug
        // telecharge depuis Actions a une signature differente et Android
        // refuse l'installation par-dessus l'ancienne (obligation de
        // desinstaller avant chaque nouvelle version). Mot de passe standard
        // "android" -- identique a celui du debug.keystore par defaut d'AGP.
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }

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
            signingConfig = signingConfigs.getByName("debug")
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
            // Exclusions generiques pour les fichiers META-INF (NOTICE/LICENSE)
            // qui apparaissent en double entre plusieurs dependances et font
            // echouer mergeDebugJavaResource sans ca.
            excludes += "/META-INF/NOTICE.md"
            excludes += "/META-INF/LICENSE.md"
            excludes += "/META-INF/NOTICE"
            excludes += "/META-INF/NOTICE.txt"
            excludes += "/META-INF/LICENSE"
            excludes += "/META-INF/LICENSE.txt"
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

    // Llamatik: AAR pre-construite localement (app/libs/llamatik-bonsai-release.aar)
    // depuis les SOURCES de Llamatik v1.10.1, mais avec son sous-module
    // llama.cpp pointant sur le fork PrismML-Eng/llama.cpp (branche "prism")
    // au lieu de l'upstream officiel -- voir .github/workflows/build-llamatik-bonsai.yml
    // et task #323/#324. Necessaire pour que Bonsai 27B (format GGUF
    // Q1_0_g128, 1-bit, voir LocalModelCatalog.kt) beneficie d'un noyau
    // CPU/NEON optimise au lieu de retomber sur une dequantification
    // generique lente (ou d'echouer a charger) avec la version Maven
    // upstream de Llamatik. Meme surface d'API (com.llamatik.library.platform.*,
    // meme version source 1.10.1) donc drop-in replacement pour SmolVlmEngine.kt
    // et SelectableLlmEngine.kt -- aucun autre moteur (Qwen/Phi/Dolphin/Gemma4/LFM)
    // n'est affecte, ils utilisent tous la meme lib native partagee, juste
    // recompilee contre un backend different.
    //
    // implementation(libs.llamatik) -- ancienne dependance Maven (upstream),
    // desactivee pour eviter un conflit de classes com.llamatik.* en double
    // sur le classpath (memes noms de classes que l'AAR locale ci-dessous).
    implementation(files("libs/llamatik-bonsai-release.aar"))

    // Google Identity Services -- Authorization API (jeton d'acces Gmail
    // scope, voir integrations/GoogleAuthController.kt et MailReader.kt).
    // Remplace l'ancienne integration IMAP (com.sun.mail) a la demande de
    // l'utilisateur, qui dispose deja d'un projet Google Cloud + Client ID
    // OAuth Web configures.
    implementation(libs.play.services.auth)

    // --- Local AI engines -------------------------------------------------
    // AICore (Gemini Nano) client SDK. This artifact is still labelled
    // experimental by Google and its group/artifact id or API surface may
    // have moved by the time you build this -- if `com.google.ai.edge.aicore`
    // fails to resolve, check https://developer.android.com/ai/gemini-nano
    // for the current coordinates and adjust AiCoreEngine.kt accordingly.
    // The app is fully functional without it (falls back to SmolVLM2/GGUF,
    // see ai/AiEngineManager.kt -- the old MediaPipe .task fallback was
    // retired along with Gemma, its only real-world user).
    implementation(libs.aicore)

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation(platform(libs.compose.bom))
}
