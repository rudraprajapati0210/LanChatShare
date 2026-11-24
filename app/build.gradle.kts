plugins {
    alias(libs.plugins.app)
    alias(libs.plugins.compose)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}
android {
    namespace = "dev.lanchatshare"
    compileSdk { version = release(37) { minorApiLevel = 1 } }
    defaultConfig {
        minSdk = 30
        applicationId = "dev.lanchatshare"
        targetSdk = 37
        versionCode = 2
        versionName = "0.2.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildFeatures { compose = true }
}
dependencies {
    implementation(platform(libs.bom))
    implementation(libs.material)
    
    implementation(project(":core:connectivity"))
    implementation(project(":core:data"))
    implementation(project(":core:design"))
    implementation(libs.hilt)
    ksp(libs.compiler)
    implementation(libs.activity)
    implementation(libs.splash)
    implementation(libs.lifecycle)
    implementation(libs.viewmodel)
    androidTestImplementation(platform(libs.bom))
    androidTestImplementation(libs.uitest)
    androidTestImplementation(libs.runner)
    androidTestImplementation(libs.ext)
}


