import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Bind a human acoustic calibration to the exact production implementation and resources,
// not merely to a controller ID whose code may change across debug APK replacements.
val micBridgeBuildIdentity: String = run {
    val digest = MessageDigest.getInstance("SHA-256")
    val identityInputs = buildList<File> {
        addAll(fileTree("src/main").files)
        add(file("build.gradle.kts"))
        add(rootProject.file("build.gradle.kts"))
        add(rootProject.file("settings.gradle.kts"))
        add(rootProject.file("gradle/libs.versions.toml"))
    }.filter { it.isFile }.distinct().sortedBy {
        it.relativeTo(rootProject.projectDir).invariantSeparatorsPath
    }
    identityInputs.forEach { input ->
        digest.update(input.relativeTo(rootProject.projectDir).invariantSeparatorsPath.toByteArray())
        digest.update(0.toByte())
        digest.update(input.readBytes())
    }
    digest.digest().joinToString("") { byte: Byte -> "%02x".format(byte.toInt() and 0xff) }
}

android {
    namespace = "com.jack.micbridge"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.jack.micbridge"
        minSdk = 31
        targetSdk = 37
        versionCode = 2
        versionName = "0.2.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "MICBRIDGE_BUILD_ID", "\"$micBridgeBuildIdentity\"")
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)

    debugImplementation(libs.androidx.compose.ui.tooling)
}
