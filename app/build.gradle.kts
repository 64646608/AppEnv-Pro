plugins {
    id("com.android.application")
}

android {
    namespace = "com.dnl.appenv.pro"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.dnl.appenv.pro"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0-dev"
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            proguardFiles("proguard-rules.pro")
            signingConfig = signingConfigs["debug"]
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    packaging {
        resources {
            merges += "META-INF/xposed/*"
            excludes += setOf("META-INF/LICENSE*", "META-INF/NOTICE*")
        }
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = false
    }
}

dependencies {
    compileOnly("io.github.libxposed:api:102.0.0")
    implementation("io.github.libxposed:service:102.0.0")
}
