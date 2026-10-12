plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.berkay.oyunceviri"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.berkay.oyunceviri"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1"
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
    // Cihaz üstünde OCR (Latin alfabesi: İngilizce altyazılar için yeterli)
    implementation("com.google.mlkit:text-recognition:16.0.1")
    // Cihaz üstünde çeviri (İngilizce -> Türkçe, model ilk seferde indirilir)
    implementation("com.google.mlkit:translate:17.0.3")
    // TV'ye WebSocket ile altyazı göndermek için
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
