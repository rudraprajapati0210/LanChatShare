plugins {
    alias(libs.plugins.library)
    alias(libs.plugins.ksp)
    alias(libs.plugins.serialization)
}
android {
    packaging.resources {
        merges += setOf("META-INF/license/**", "META-INF/LICENSE*", "META-INF/NOTICE*", "META-INF/services/**", "META-INF/native-image/**")
        excludes += setOf("META-INF/INDEX.LIST", "META-INF/io.netty.versions.properties", "META-INF/DEPENDENCIES")
    }
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
    implementation(libs.media.transformer)
    implementation(libs.media.effect)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.websockets)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.websockets)
    implementation(libs.x509)
    implementation(libs.json)
    implementation(libs.room)
    ksp(libs.roomCompiler)
    androidTestImplementation(libs.runner)
    androidTestImplementation(libs.ext)
    testImplementation(libs.junit)
}





