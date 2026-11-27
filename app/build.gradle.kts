plugins {
    id("com.android.application")
}

android {
    namespace = "com.slicetechnologies.slice"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.slicetechnologies.slice"
        minSdk = 23
        targetSdk = 35
        versionCode = 3
        versionName = "1.2.0"
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
