import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Pure Kotlin/JVM: M3U, Xtream, XMLTV parsing and channel-name normalization.
// Kept free of Android so the (large) test suite runs fast on the JVM.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    api(project(":core:model"))
    implementation(libs.kotlinx.serialization.json)
    // org.xmlpull API is part of the Android framework; only needed to compile here.
    compileOnly(libs.xmlpull)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.kxml2)
}
