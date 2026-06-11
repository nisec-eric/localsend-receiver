plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.localsend.receiver"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.localsend.receiver"
        minSdk = 17
        targetSdk = 28
        versionCode = 1
        versionName = "1.0.0"
        multiDexEnabled = true
    }

    signingConfigs {
        create("release") {
            storeFile = file("../localsend-release.jks")
            storePassword = "localsend123"
            keyAlias = "localsend"
            keyPassword = "localsend123"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    lint {
        abortOnError = false
        disable += "ExpiredTargetSdkVersion"
    }
}

dependencies {
    implementation("org.nanohttpd:nanohttpd:2.3.1")
    implementation("com.google.code.gson:gson:2.8.9")
    implementation("androidx.core:core:1.5.0")
    implementation("androidx.appcompat:appcompat:1.4.2")
    implementation("androidx.annotation:annotation:1.5.0")
    implementation("org.jetbrains.kotlin:kotlin-stdlib:1.9.22")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.0.4")
}
