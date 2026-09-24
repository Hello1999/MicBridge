plugins { id("com.android.application") version "9.4.0" }

android {
    namespace = "com.jack.micbridge.capturehook"
    compileSdk = 37
    defaultConfig {
        applicationId = "com.jack.micbridge.capturehook"
        minSdk = 31
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies { compileOnly("de.robv.android.xposed:api:82") }
