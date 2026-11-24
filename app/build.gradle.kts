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
    }

    // Release key: the private key from the SB_SIGNING repository secret (decoded by CI into
    // SB_KEYSTORE_FILE / SB_KEYSTORE_PASSWORD). It is never stored in the repository.
    // Until that secret exists, builds fall back to the old public key so they still install.
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

    // Two editions from the same code:
    //  • sideload – the APK installed with Downloader; updates itself from GitHub, includes the built-in login.
    //  • play     – the Android App Bundle (.aab) for Google Play; no self-updater, no built-in login,
    //               no install-other-apps or exact-alarm permissions (Play policy).
    flavorDimensions += "store"
    productFlavors {
        create("sideload") {
            dimension = "store"
            isDefault = true
            buildConfigField("boolean", "SELF_UPDATE", "true")
        }
        create("play") {
            dimension = "store"
            buildConfigField("boolean", "SELF_UPDATE", "false")
        }
    }
    bundle {
        // Keep every language in the download: the app switches Arabic/English itself.
        language { enableSplit = false }
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
