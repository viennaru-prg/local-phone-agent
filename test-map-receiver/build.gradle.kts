plugins { id("com.android.application"); kotlin("android") }
android {
    namespace = "dev.localphone.testmap"
    compileSdk = 35
    defaultConfig {
        // A protocol test fixture, installed ONLY on the dedicated emulator.
        applicationId = "com.nhn.android.nmap"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "TEST-FIXTURE"
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
