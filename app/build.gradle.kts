plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.streambox.tv"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.streambox.tv"
        minSdk = 23
        targetSdk = 36
        // CI build number = version, so the app can tell when a newer release exists.
        val build = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionCode = build
        versionName = "1.0.$build"
        buildConfigField("boolean", "SELF_UPDATE", "true")
    }

    // Signed with the same key as every earlier build, so each update installs over the last one.
    // (Optional: an SB_SIGNING repository secret switches to a private key.)
    val privateKey = System.getenv("SB_KEYSTORE_FILE")?.let { file(it) }?.takeIf { it.exists() }
    signingConfigs {
        getByName("debug") {
            storeFile = file("streambox.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        create("private") {
            if (privateKey != null) {
                storeFile = privateKey
                storeType = "pkcs12"
                storePassword = System.getenv("SB_KEYSTORE_PASSWORD")
                keyAlias = "streambox"
                keyPassword = System.getenv("SB_KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        // Release: optimized and shrunk with R8, not debuggable, signed with the same key as before
        // so it installs over existing copies without losing settings.
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            isDebuggable = false
            signingConfig = signingConfigs.getByName(if (privateKey != null) "private" else "debug")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        buildConfig = true
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    lint {
        // Real errors fail the build; style warnings don't.
        abortOnError = true
        checkReleaseBuilds = true
        checkDependencies = false
        textReport = true
        textOutput = file("stdout")
        explainIssues = false
        disable += setOf("MissingTranslation", "ExtraTranslation", "IconMissingDensityFolder", "ObsoleteSdkInt", "GradleDependency", "OldTargetApi")
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.5.1")
    implementation("androidx.media3:media3-exoplayer-dash:1.5.1")
    implementation("androidx.media3:media3-datasource-okhttp:1.5.1")
    implementation("androidx.media3:media3-ui:1.5.1")
    implementation("androidx.media3:media3-effect:1.5.1")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:okhttp-dnsoverhttps:4.12.0")
    implementation("io.coil-kt:coil:2.7.0")
    implementation("com.google.zxing:core:3.5.3")

    testImplementation("junit:junit:4.13.2")
}
