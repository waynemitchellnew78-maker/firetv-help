import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val signing = Properties().apply {
    val f = rootProject.file("signing.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.firehelp.tvhelp"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.firehelp.tvhelp"
        minSdk = 25          // Fire OS 6 and newer
        targetSdk = 30       // Fire OS 8 is Android 11
        versionCode = 1
        versionName = "1.0"
        buildConfigField("String", "RELAY_URL", "\"wss://xxxxx/ws/device\"")
    }

    signingConfigs {
        create("release") {
            if (signing.containsKey("storeFile")) {
                storeFile = rootProject.file(signing.getProperty("storeFile"))
                storePassword = signing.getProperty("storePassword")
                keyAlias = signing.getProperty("keyAlias")
                keyPassword = signing.getProperty("keyPassword")
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }
    buildFeatures { buildConfig = true }
    lint { checkReleaseBuilds = false; abortOnError = false }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    packaging { resources.excludes += setOf("META-INF/*.kotlin_module", "META-INF/versions/**") }
}

dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("dev.mobile:dadb:1.2.10")
}
