import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.kapt")
}

val localProperties = Properties().apply {
    val localFile = rootProject.file("local.properties")
    if (localFile.isFile) localFile.inputStream().use(::load)
}
val localAmapKey = localProperties.getProperty("amap.key", "")
val amapKey = providers.gradleProperty("AMAP_KEY").orNull ?: localAmapKey
val internalSigningReady = listOf("storeFile", "storePassword", "keyAlias", "keyPassword")
    .all { !localProperties.getProperty(it).isNullOrBlank() }

android {
    namespace = "com.trail.app"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.trail.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 12
        versionName = "0.5.0-beta"
        manifestPlaceholders["amapKey"] = amapKey
        buildConfigField("String", "AMAP_KEY", "\"${amapKey.replace("\\", "\\\\").replace("\"", "\\\"")}\"")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
    // 内测签名只从未跟踪的 local.properties 读取口令；没有本地配置时仍可构建未签名 release。
    if (internalSigningReady) signingConfigs {
        create("internal") {
            storeFile = rootProject.file(localProperties.getProperty("storeFile"))
            storePassword = localProperties.getProperty("storePassword")
            keyAlias = localProperties.getProperty("keyAlias")
            keyPassword = localProperties.getProperty("keyPassword")
        }
    }
    buildTypes {
        getByName("release") {
            if (internalSigningReady) signingConfig = signingConfigs.getByName("internal")
        }
    }
    sourceSets.getByName("androidTest").assets.srcDir("$projectDir/schemas")
}
kapt { arguments { arg("room.schemaLocation", "$projectDir/schemas") } }
dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.11.01"))
    androidTestImplementation(platform("androidx.compose:compose-bom:2025.11.01"))
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.room:room-runtime:2.8.4")
    implementation("androidx.room:room-ktx:2.8.4")
    kapt("androidx.room:room-compiler:2.8.4")
    implementation("org.maplibre.gl:android-sdk:11.11.0")
    implementation("com.amap.api:3dmap:10.0.600")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test:rules:1.7.0")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    // Compose 的旧传递版本使用已移除的 InputManager 反射方法，显式匹配新版 AndroidX Test。
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
