import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.ksp)
}

android {
    namespace = "io.github.dishan18.books"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.dishan18.books"
        minSdk = 29
        targetSdk = 37
        // The release workflow passes -PversionName from the git tag (v1.2.3 -> 1.2.3).
        val version = (findProperty("versionName") as String?) ?: "1.0.0"
        val (major, minor, patch) = (version.split(".").map { it.toIntOrNull() ?: 0 } + listOf(0, 0, 0)).take(3)
        versionName = version
        versionCode = major * 10_000 + minor * 100 + patch
    }

    // One fixed key for every build, so APKs from your PC and from GitHub install over each other
    // and keep your library. Locally it's read from app/signing/keystore.properties (not in git);
    // GitHub Actions gets it from repository secrets via environment variables.
    val signing = Properties().apply {
        file("signing/keystore.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
    }
    val keystoreFile = System.getenv("BOOKS_KEYSTORE_FILE") ?: signing.getProperty("storeFile")
    val keystorePassword = System.getenv("BOOKS_KEYSTORE_PASSWORD") ?: signing.getProperty("storePassword")

    signingConfigs {
        if (keystoreFile != null && keystorePassword != null) {
            create("books") {
                storeFile = file(keystoreFile)
                storePassword = keystorePassword
                keyAlias = "books"
                keyPassword = keystorePassword
            }
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Without the key (e.g. in a fork) fall back to the debug key so the build still works.
            signingConfig = signingConfigs.findByName("books") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources.excludes.add("META-INF/{AL2.0,LGPL2.1}")
    }
}

kotlin {
    compilerOptions {
        optIn.addAll(
            "org.readium.r2.shared.ExperimentalReadiumApi",
            "androidx.compose.material3.ExperimentalMaterial3Api",
        )
    }
}

dependencies {
    coreLibraryDesugaring(libs.desugar.jdk.libs)

    implementation(libs.androidx.core)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.fragment)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.coroutines.android)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.animation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.readium.shared)
    implementation(libs.readium.streamer)
    implementation(libs.readium.navigator)
}
