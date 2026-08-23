plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.yourdomain.deepseekwidget"
    compileSdk = 35

    signingConfigs {
        // Постоянный ключ подписи (лежит в репо) — все CI-сборки подписываются им.
        // Без этого GitHub Actions генерирует новый debug-ключ на каждую сборку,
        // и APK новой версии не устанавливается поверх старой.
        create("release") {
            storeFile = rootProject.file("keystore/deepseek-widget.keystore")
            storePassword = "deepseek123"
            keyAlias = "deepseek"
            keyPassword = "deepseek123"
        }
    }

    defaultConfig {
        applicationId = "com.yourdomain.deepseekwidget"
        minSdk = 26
        targetSdk = 35
        versionCode = 9
        versionName = "1.9"
        // Ключ Deepgram для встроенного STT. В CI подставляется из secrets,
        // локально — из env DEEPGRAM_API_KEY. В git ключ не попадает.
        val deepgramKey = System.getenv("DEEPGRAM_API_KEY") ?: ""
        buildConfigField("String", "DEEPGRAM_API_KEY", "\"$deepgramKey\"")
    }

    buildFeatures {
        // Required so VoiceInputActivity can use BuildConfig.APPLICATION_ID
        // for the FileProvider authority — keeps it in sync with applicationId.
        buildConfig = true
    }

    buildTypes {
        debug {
            isDebuggable = true
            signingConfig = signingConfigs.getByName("release")
        }
        release {
            // R8 full-mode: shrinks code and resources, reducing APK from ~4.5 MB to ~1 MB.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
}