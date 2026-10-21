import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.feedback.demo"
    compileSdk = 36
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = providers.gradleProperty("RELEASE_APPLICATION_ID").orElse("com.feedback.demo").get()
        minSdk = 24
        targetSdk = 36
        versionCode = providers.gradleProperty("RELEASE_VERSION_CODE").orElse("1").get().toInt()
        versionName = providers.gradleProperty("RELEASE_VERSION_NAME").orElse("1.0").get()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "SDK_SERVER_URL", "\"${providers.gradleProperty("SDK_SERVER_URL").orElse("http://10.0.2.2:18080").get()}\"")
        buildConfigField("String", "COURSE_SDK_SECRET", "\"${providers.gradleProperty("COURSE_SDK_SECRET").orElse("set-course-secret-in-local-properties").get()}\"")
        buildConfigField("String", "QUESTION_BANK_SDK_SECRET", "\"${providers.gradleProperty("QUESTION_BANK_SDK_SECRET").orElse("set-question-bank-secret-locally").get()}\"")
        buildConfigField("String", "WRONG_BOOK_SDK_SECRET", "\"${providers.gradleProperty("WRONG_BOOK_SDK_SECRET").orElse("set-wrong-book-secret-locally").get()}\"")
        buildConfigField("String", "AI_TUTOR_SDK_SECRET", "\"${providers.gradleProperty("AI_TUTOR_SDK_SECRET").orElse("set-ai-tutor-secret-locally").get()}\"")
        buildConfigField("String", "APP_STORE_SDK_SECRET", "\"${providers.gradleProperty("APP_STORE_SDK_SECRET").orElse("set-app-store-secret-locally").get()}\"")
        buildConfigField("String", "UNIVERSAL_FEEDBACK_SDK_SECRET", "\"${providers.gradleProperty("UNIVERSAL_FEEDBACK_SDK_SECRET").orElse("set-universal-feedback-secret-locally").get()}\"")
        buildConfigField("String", "SDK_SERVER_PUBLIC_KEY_BASE64", "\"${providers.gradleProperty("SDK_SERVER_PUBLIC_KEY_BASE64").orElse("").get()}\"")
        manifestPlaceholders["feedbackTaskAffinity"] = "${applicationId}.feedback"
    }

    signingConfigs {
        val storePath = providers.environmentVariable("FEEDBACK_RELEASE_STORE_FILE").orNull
        val storePass = providers.environmentVariable("FEEDBACK_RELEASE_STORE_PASSWORD").orNull
        val keyAliasValue = providers.environmentVariable("FEEDBACK_RELEASE_KEY_ALIAS").orNull
        val keyPass = providers.environmentVariable("FEEDBACK_RELEASE_KEY_PASSWORD").orNull
        if (listOf(storePath, storePass, keyAliasValue, keyPass).all { !it.isNullOrBlank() }) {
            create("release") {
                storeFile = file(storePath!!)
                storePassword = storePass
                keyAlias = keyAliasValue
                keyPassword = keyPass
            }
        }
    }

    buildFeatures { buildConfig = true }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

}

kotlin { compilerOptions { jvmTarget = JvmTarget.JVM_17 } }

val releaseRequested = gradle.startParameter.taskNames.any {
    it.contains("release", ignoreCase = true) &&
        (it.contains("assemble", ignoreCase = true) || it.contains("bundle", ignoreCase = true))
}

if (releaseRequested) {
    val requiredProperties = listOf(
        "RELEASE_APPLICATION_ID", "RELEASE_VERSION_CODE", "RELEASE_VERSION_NAME",
        "SDK_SERVER_URL", "SDK_SERVER_PUBLIC_KEY_BASE64",
        "COURSE_SDK_SECRET", "QUESTION_BANK_SDK_SECRET", "WRONG_BOOK_SDK_SECRET",
        "AI_TUTOR_SDK_SECRET", "APP_STORE_SDK_SECRET", "UNIVERSAL_FEEDBACK_SDK_SECRET"
    )
    val missingProperties = requiredProperties.filter { providers.gradleProperty(it).orNull.isNullOrBlank() }
    val requiredEnvironment = listOf(
        "FEEDBACK_RELEASE_STORE_FILE", "FEEDBACK_RELEASE_STORE_PASSWORD",
        "FEEDBACK_RELEASE_KEY_ALIAS", "FEEDBACK_RELEASE_KEY_PASSWORD"
    )
    val missingEnvironment = requiredEnvironment.filter { providers.environmentVariable(it).orNull.isNullOrBlank() }
    require(missingProperties.isEmpty()) { "Release build missing Gradle properties: ${missingProperties.joinToString()}" }
    require(missingEnvironment.isEmpty()) { "Release build missing signing environment: ${missingEnvironment.joinToString()}" }
    require(providers.gradleProperty("RELEASE_APPLICATION_ID").get() != "com.feedback.demo") {
        "Release applicationId must be formally approved and cannot use com.feedback.demo"
    }
    require(providers.gradleProperty("SDK_SERVER_URL").get().startsWith("https://")) {
        "Release SDK server URL must use HTTPS"
    }
    require(providers.gradleProperty("SDK_SERVER_PUBLIC_KEY_BASE64").get().length >= 128) {
        "Release SDK RSA public key is missing or implausibly short"
    }
}

dependencies {
    implementation(project(":sdk"))
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    testImplementation("junit:junit:4.13.2")
}
