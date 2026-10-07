plugins { id("com.android.application"); kotlin("android"); kotlin("kapt") }
android {
    namespace = "dev.localphone.agent"
    compileSdk = 35
    defaultConfig {
        applicationId = "dev.localphone.agent"
        minSdk = 26
        targetSdk = 35
        versionCode = providers.gradleProperty("releaseVersionCode").getOrElse("5").toInt()
        versionName = providers.gradleProperty("releaseVersionName").getOrElse("0.5.0")
        val updateRepository = providers.gradleProperty("updateRepository").getOrElse("viennaru-prg/local-phone-agent")
        require(Regex("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+").matches(updateRepository))
        buildConfigField("String", "UPDATE_REPOSITORY", "\"$updateRepository\"")
        buildConfigField("boolean", "MEDIA_SESSION_CONTROL_AVAILABLE", "true")
        buildConfigField("boolean", "UI_AUTOMATION_AVAILABLE", "true")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    buildFeatures { buildConfig = true }
    buildTypes {
        release { isMinifyEnabled = false }
        create("install") {
            initWith(getByName("debug"))
            isDebuggable = false
            versionNameSuffix = "-install"
            buildConfigField("boolean", "MEDIA_SESSION_CONTROL_AVAILABLE", "false")
            buildConfigField("boolean", "UI_AUTOMATION_AVAILABLE", "false")
            matchingFallbacks += listOf("release", "debug")
        }
        create("automation") {
            initWith(getByName("debug"))
            isDebuggable = false
            versionNameSuffix = "-automation"
            matchingFallbacks += listOf("release", "debug")
        }
    }
    testBuildType = providers.gradleProperty("testBuildType").getOrElse("debug")
    sourceSets {
        getByName("debug").java.srcDir("src/media/java")
        getByName("release").java.srcDir("src/media/java")
        listOf("debug", "release", "automation").forEach { name ->
            getByName(name).java.srcDir("src/automation/java")
            getByName(name).manifest.srcFile("src/automation/AndroidManifest.xml")
        }
        getByName("automation").java.srcDir("src/media/java")
        if (testBuildType == "install") getByName("androidTest").java.srcDir("src/installTest/java")
    }
    packaging { resources.excludes += setOf("META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*") }
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
dependencies {
    implementation(project(":core"))
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("androidx.room:room-runtime:2.7.2")
    implementation("androidx.room:room-ktx:2.7.2")
    kapt("androidx.room:room-compiler:2.7.2")
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.10.2")
    implementation(files("libs/sherpa-onnx-1.12.20.aar"))
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:core-ktx:1.6.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation("androidx.test.espresso:espresso-intents:3.6.1")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
}
