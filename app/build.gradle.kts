plugins { id("com.android.application"); kotlin("android") }

android {
    namespace = "dev.localphone.agent"
    compileSdk = 35
    ndkVersion = "28.2.13676358"
    defaultConfig {
        applicationId = "dev.localphone.agent"
        minSdk = 30
        targetSdk = 35
        versionCode = 102
        versionName = "1.0.2"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        testApplicationId = "dev.localphone.agent.verification"
        ndk { abiFilters += listOf("arm64-v8a") }
        externalNativeBuild { cmake { cppFlags += "-std=c++17"; arguments += listOf("-DCMAKE_BUILD_TYPE=Release") } }
    }
    buildTypes {
        // Debug builds accept a typed goal from `adb shell am start ... --es goal "..."` for testing.
        debug { buildConfigField("boolean", "ADB_GOALS", "true") }
        release {
            isMinifyEnabled = false
            buildConfigField("boolean", "ADB_GOALS", "false")
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    signingConfigs.getByName("debug") {
        // Use the locally held key matching the installed 1.0.0; never commit a private key.
        System.getenv("LPA_SIGNING_STORE")?.let { storeFile = file(it) }
    }
    buildFeatures { buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" } }
    packaging {
        resources.excludes += setOf("META-INF/*.kotlin_module")
        jniLibs.excludes += setOf("**/libOpenCL.so") // the phone's vendor driver is used instead
    }
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
    implementation(project(":core"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.google.code.gson:gson:2.13.2")
    implementation("androidx.core:core:1.15.0")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
