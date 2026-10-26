plugins {
    id("com.android.application")
}

android {
    namespace = "dev.campus.todo"
    compileSdk = 36
    // 必须显式指定：AGP 8.13 默认去找 Build Tools 35.0.0，本机装的是 36.0.0。
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "dev.campus.todo"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0-demo"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    lint {
        abortOnError = false
    }
}

dependencies {
    // Test-only. Neither of these ships in the APK; the app itself uses nothing
    // but framework APIs. org.json is part of the Android framework, but the JVM
    // unit tests need a real implementation of it.
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
