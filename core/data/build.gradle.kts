plugins {
    alias(libs.plugins.library)
    
    
}
android {
    namespace = "dev.lanchatshare.data"
    compileSdk { version = release(37) { minorApiLevel = 1 } }
    defaultConfig {
        minSdk = 30
        
    }
    
}
dependencies {
    
    api(libs.store)
    testImplementation(libs.junit)
    
}
