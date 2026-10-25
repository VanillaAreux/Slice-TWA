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

// Recent Kotlin stdlib versions include the JDK 7/8 APIs directly. Remove older
// split artifacts pulled in transitively so D8 does not see duplicate classes.
configurations.configureEach {
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib-jdk7")
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib-jdk8")
}

dependencies {
    implementation("androidx.activity:activity:1.9.3")
}
