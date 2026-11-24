plugins {
    alias(libs.plugins.library)
    alias(libs.plugins.ksp)
    alias(libs.plugins.serialization)
}
android {
    namespace = "dev.lanchatshare.connectivity"
    compileSdk { version = release(37) { minorApiLevel = 1 } }
    defaultConfig {
        minSdk = 30
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
}
ksp { arg("room.schemaLocation", file("schemas").absolutePath) }
dependencies {
    implementation(project(":core:data"))
    implementation(libs.json)
    implementation(libs.room)
    ksp(libs.roomCompiler)
    androidTestImplementation(libs.runner)
    androidTestImplementation(libs.ext)
    testImplementation(libs.junit)
}

