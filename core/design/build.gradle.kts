plugins {
    alias(libs.plugins.library)
    alias(libs.plugins.compose)
    
}
android {
    namespace = "dev.lanchatshare.design"
    compileSdk { version = release(37) { minorApiLevel = 1 } }
    defaultConfig {
        minSdk = 30
        
    }
    buildFeatures { compose = true }
}
dependencies {
    implementation(platform(libs.bom))
    implementation(libs.material)
    
    
}

