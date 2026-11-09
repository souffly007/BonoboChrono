plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
dependencies {
    testImplementation("junit:junit:4.13.2")
}

android {
    namespace = "fr.bonobo.chrono"
    compileSdk = 34
    defaultConfig {
        applicationId = "fr.bonobo.chrono"
        minSdk = 21
        targetSdk = 34
        versionCode = 6
        versionName = "1.5"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
// JUnit sert uniquement aux tests JVM ; l'application utilise le SDK Android.
