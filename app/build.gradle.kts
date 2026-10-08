import groovy.json.JsonSlurper
import java.security.MessageDigest

plugins { id("com.android.application"); kotlin("android"); kotlin("kapt") }
android {
    namespace = "dev.localphone.agent"
    compileSdk = 35
    ndkVersion = "28.2.13676358"
    defaultConfig {
        applicationId = "dev.localphone.agent"
        minSdk = 26
        targetSdk = 35
        versionCode = providers.gradleProperty("releaseVersionCode").getOrElse("12").toInt()
        versionName = providers.gradleProperty("releaseVersionName").getOrElse("0.7.3")
        val updateRepository = providers.gradleProperty("updateRepository").getOrElse("viennaru-prg/local-phone-agent")
        require(Regex("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+").matches(updateRepository))
        buildConfigField("String", "UPDATE_REPOSITORY", "\"$updateRepository\"")
        buildConfigField("boolean", "MEDIA_SESSION_CONTROL_AVAILABLE", "true")
        buildConfigField("boolean", "UI_AUTOMATION_AVAILABLE", "true")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
        externalNativeBuild { cmake { cppFlags += "-std=c++17" } }
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
    androidResources { noCompress += listOf("litertlm", "gguf") }
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" } }
}
val modelManifest = rootProject.file("agent-models.json")
val embeddedModels = (JsonSlurper().parse(modelManifest) as Map<*, *>)["models"] as List<Map<String, Any>>
val verifyEmbeddedAgentModels by tasks.registering {
    inputs.file(modelManifest)
    inputs.files(embeddedModels.map { file("src/main/assets/${it["asset"]}") })
    val notices = listOf("Gemma-Terms.txt", "Gemma-Prohibited-Use.txt", "Gemma-NOTICE.txt", "Qwen-Apache-2.0.txt", "Qwen-quantization-card.md", "llama.cpp-MIT.txt")
    inputs.files(notices.map { file("src/main/assets/licenses/$it") })
    doLast {
        notices.forEach { require(file("src/main/assets/licenses/$it").isFile) { "Required model license missing: $it" } }
        embeddedModels.forEach { entry ->
            val weight = file("src/main/assets/${entry["asset"]}")
            require(weight.isFile && weight.length() == (entry["bytes"] as Number).toLong()) {
                "Embedded weight missing/wrong size: ${entry["id"]}. See scripts/prepare-agent-models.py; a weights-free APK is forbidden."
            }
            val digest = MessageDigest.getInstance("SHA-256")
            weight.inputStream().buffered().use { input ->
                val buffer = ByteArray(1024 * 1024)
                while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
            }
            require(digest.digest().joinToString("") { "%02x".format(it) } == entry["sha256"]) {
                "Embedded model SHA-256 mismatch: ${entry["id"]}"
            }
        }
    }
}
val copyAgentModelManifest by tasks.registering(Copy::class) {
    from(modelManifest); into(layout.buildDirectory.dir("generated/agentAssets"))
}
android.sourceSets.getByName("main").assets.srcDir(layout.buildDirectory.dir("generated/agentAssets"))
tasks.named("preBuild") { dependsOn(verifyEmbeddedAgentModels, copyAgentModelManifest) }
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
