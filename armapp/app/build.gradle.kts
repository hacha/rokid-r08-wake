plugins {
    id("com.android.application")
}

android {
    namespace = "com.hacha.r08wake"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.hacha.r08wake"
        minSdk = 31
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        // keep the bundled native daemon as-is (do not compress/alter)
        resources { excludes += "META-INF/versions/**" }
    }
    androidResources {
        noCompress += "r08waked"
    }
}

dependencies {
    implementation("com.flyfishxu:kadb:2.1.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
}
