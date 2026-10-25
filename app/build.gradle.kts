plugins {
    id("com.android.application")
}

android {
    namespace = "com.slicetechnologies.slice"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.slicetechnologies.slice"
        minSdk = 23
        // Target 32 while this legacy WebView wrapper is being replaced; Android 14's stricter
        // MediaProjection foreground-service requirements apply when targeting API 34+.
        targetSdk = 32
        versionCode = 2
        versionName = "1.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("androidx.activity:activity:1.9.3")
}