plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.agentcli.terminal"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.agentcli.terminal"
        // minSdk 26 (Android 8.0): cubre de sobra un Poco X6 Pro.
        minSdk = 26
        // targetSdk 28 A PROPÓSITO: con targetSdk <= 28, Android concede permiso
        // de ejecución sobre los binarios de la carpeta de datos de la app. Es lo
        // que necesita el motor embebido (proot + rootfs) para ejecutar node/ttyd
        // extraídos en filesDir. Con targetSdk >= 29 el kernel/SELinux lo bloquea.
        // Contrapartida: la app no es publicable en Google Play (distribución por
        // sideload/APK). Ver docs/DESIGN-embedded-linux.md §2.1.
        targetSdk = 28
        versionCode = 3
        versionName = "0.2.1-beta"

        // Solo arm64 (el Poco X6 Pro lo es). Aquí irán proot + su loader y, más
        // adelante, no empaquetamos otras ABIs para no inflar la APK.
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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
        // ViewBinding en lugar de findViewById: menos boilerplate, sin Compose.
        viewBinding = true
    }

    androidResources {
        // El rootfs Debian ya viene gzip; no re-comprimir (se guarda tal cual).
        noCompress += listOf("tarball")
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    // WebKit de AndroidX: API moderna sobre WebView (útil a futuro).
    implementation("androidx.webkit:webkit:1.11.0")
    // Extracción robusta de tar (rootfs Alpine trae symlinks/hardlinks).
    implementation("org.apache.commons:commons-compress:1.26.2")
}
