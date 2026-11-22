buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        // Built-in Kotlin and Compose compiler plugins share the version catalog to avoid compiler version mismatches.
        classpath(libs.kotlin.gradle.plugin)
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
}
