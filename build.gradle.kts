plugins {
    id("org.springframework.boot") version "3.5.16" apply false
    id("io.spring.dependency-management") version "1.1.6" apply false
    id("org.jetbrains.kotlin.jvm") version "2.2.21" apply false
    id("org.jetbrains.kotlin.plugin.spring") version "2.2.21" apply false
    id("com.android.library") version "8.13.2" apply false
    id("com.google.devtools.ksp") version "2.2.21-2.0.5" apply false
    id("org.jetbrains.kotlin.android") version "2.2.21" apply false
}

allprojects {
    repositories {
        mavenCentral()
        google()
    }
}
