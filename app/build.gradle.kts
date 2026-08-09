plugins {
    id("com.android.application")
}

android {
    namespace = "com.dnl.appenv.pro"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.dnl.appenv.pro"
        minSdk = 26
        targetSdk = 36
        versionCode = 2
        versionName = "1.0.1-dev"
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
    compileOnly("io.github.libxposed:api:101.0.1")
    implementation("io.github.libxposed:service:101.0.0")
}
