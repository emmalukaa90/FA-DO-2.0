import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.fado.karaoke"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.fado.karaoke"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
    }

    buildTypes {
        release {
            // Bez minifikacji: aplikacja jest mała, a kod nie wymaga reguł R8.
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Wersja "release" podpisana kluczem debug, żeby dało się ją od razu
            // zainstalować z pliku APK. Do Sklepu Play podmień na własny keystore.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
    }

    lint {
        // Ostrzeżenia lint nie blokują budowy APK.
        abortOnError = false
        checkReleaseBuilds = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // Jedyna zależność zewnętrzna: Android for Cars App Library (Android Auto).
    // Sieć, cache i renderowanie korzystają wyłącznie z API systemu Android.
    implementation("androidx.car.app:app:1.7.0")
}
