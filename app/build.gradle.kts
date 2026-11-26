plugins {
    alias(libs.plugins.app)
    alias(libs.plugins.compose)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}
android {
    packaging.resources {
        merges += setOf("META-INF/license/**", "META-INF/LICENSE*", "META-INF/NOTICE*", "META-INF/services/**", "META-INF/native-image/**")
        excludes += setOf("META-INF/INDEX.LIST", "META-INF/io.netty.versions.properties", "META-INF/DEPENDENCIES")
    }
    namespace = "dev.lanchatshare"
    compileSdk { version = release(37) { minorApiLevel = 1 } }
    defaultConfig {
        minSdk = 30
        applicationId = "dev.lanchatshare"
        targetSdk = 37
        versionCode = 3
        versionName = "0.3.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildFeatures { compose = true }
}
dependencies {
    implementation(libs.paging.compose)
    implementation(platform(libs.bom))
    implementation(libs.material)
    
    implementation(project(":core:connectivity"))
    implementation(project(":core:data"))
    implementation(project(":core:design"))
    implementation(libs.work)
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





