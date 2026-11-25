import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    id("com.google.devtools.ksp")
}

val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) {
        keystorePropsFile.inputStream().use { load(it) }
    }
}
val releaseStorePath: String? =
    keystoreProps.getProperty("storeFile")?.takeIf { it.isNotBlank() }
        ?: System.getenv("OBD2_KEYSTORE_PATH")?.takeIf { it.isNotBlank() }
val releaseStorePassword: String? =
    keystoreProps.getProperty("storePassword")?.takeIf { it.isNotBlank() }
        ?: System.getenv("OBD2_KEYSTORE_PASSWORD")?.takeIf { it.isNotBlank() }
val releaseKeyAlias: String? =
    keystoreProps.getProperty("keyAlias")?.takeIf { it.isNotBlank() }
        ?: System.getenv("OBD2_KEY_ALIAS")?.takeIf { it.isNotBlank() }
val releaseKeyPassword: String? =
    keystoreProps.getProperty("keyPassword")?.takeIf { it.isNotBlank() }
        ?: System.getenv("OBD2_KEY_PASSWORD")?.takeIf { it.isNotBlank() }
val hasReleaseSigning = !releaseStorePath.isNullOrBlank() &&
    !releaseStorePassword.isNullOrBlank() &&
    !releaseKeyAlias.isNullOrBlank() &&
    !releaseKeyPassword.isNullOrBlank() &&
    file(releaseStorePath).exists()

val githubRepoProp = (findProperty("OBD2_GITHUB_REPO") as? String)
    ?.takeIf { it.isNotBlank() }
    ?: "WildLIK/WilDia"

android {
    namespace = "com.obd2.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.obd2.app"
        minSdk = 23
        targetSdk = 34
        versionCode = 19
        versionName = "0.6.0"
        buildConfigField("String", "APK_UPDATE_MANIFEST_URL", "\"\"")
        buildConfigField("String", "DB_UPDATE_MANIFEST_URL", "\"\"")
        buildConfigField("String", "UPDATE_GITHUB_REPO", "\"$githubRepoProp\"")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        resourceConfigurations += listOf("ru", "en")
    }

    signingConfigs {
        create("release") {
            enableV1Signing = true
            enableV2Signing = true
            if (hasReleaseSigning) {
                storeFile = file(releaseStorePath!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = libs.versions.composeCompiler.get()
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf(
            "-opt-in=kotlin.RequiresOptIn",
            "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi"
        )
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
        unitTests.all {
            it.maxHeapSize = "2g"
            it.forkEvery = 1L
            it.testLogging {
                events("passed", "failed", "skipped")
                showStandardStreams = true
            }
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.documentfile)
    implementation(libs.usb.serial.android)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    implementation(libs.datastore.preferences)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.sqlite.jdbc)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.junit)
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.compose.ui.test.manifest)
}
