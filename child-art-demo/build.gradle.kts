import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.feedback.childartdemo"
    compileSdk = 36
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "com.feedback.childartdemo"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
        buildConfigField("String", "SDK_APP_ID", "\"com.feedback.childartdemo\"")
        buildConfigField("String", "SDK_APP_KEY", "\"child-art-demo\"")
        val secret = providers.environmentVariable("CHILD_ART_DEMO_SDK_SECRET")
            .orElse(providers.gradleProperty("CHILD_ART_DEMO_SDK_SECRET"))
            .orElse("set-child-art-demo-secret-locally")
        val publicKey = providers.environmentVariable("SDK_SERVER_PUBLIC_KEY_BASE64")
            .orElse(providers.gradleProperty("SDK_SERVER_PUBLIC_KEY_BASE64"))
            .orElse("")
        buildConfigField("String", "SDK_APP_SECRET", "\"${secret.get()}\"")
        buildConfigField("String", "SDK_SERVER_URL", "\"${providers.gradleProperty("SDK_SERVER_URL").orElse("http://10.0.2.2:18080").get()}\"")
        buildConfigField("String", "SDK_SERVER_PUBLIC_KEY_BASE64", "\"${publicKey.get()}\"")
    }
    buildFeatures { buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin { compilerOptions { jvmTarget = JvmTarget.JVM_17 } }

dependencies {
    implementation(project(":sdk"))
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
}
