import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Signing key lives outside git (this repo is public). Put keystore.properties next to
// settings.gradle.kts — see README. Without it, builds fall back to the debug key.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "sk.freelancegroup.dslsk"
    compileSdk = 35

    defaultConfig {
        applicationId = "sk.freelancegroup.dslsk"
        minSdk = 26          // Android 8.0
        targetSdk = 35       // Android 15
        versionCode = 5      // the 2012 app was versionCode 2 / 1.1
        versionName = "2.2"
    }

    signingConfigs {
        if (keystoreProps.isNotEmpty()) {
            create("dslsk") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("dslsk") ?: signingConfigs.getByName("debug")
        }
        debug {
            signingConfigs.findByName("dslsk")?.let { signingConfig = it }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // The only dependency: an HTML parser. The UI uses plain Android framework classes.
    implementation("org.jsoup:jsoup:1.21.2")

    testImplementation("junit:junit:4.13.2")
}
