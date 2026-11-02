// Release signing comes from the environment (CI secrets); without it, release builds are unsigned.
val releaseKeystore: String? = providers.environmentVariable("REVEILLE_KEYSTORE_FILE").orNull

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.replydev.reveille"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.replydev.reveille"
        minSdk = 31
        targetSdk = 37
        // CI passes the semantic version; local builds are marked as dev builds.
        versionCode = providers.gradleProperty("reveille.versionCode").map(String::toInt).getOrElse(1)
        versionName = providers.gradleProperty("reveille.versionName").getOrElse("0.0.0-dev")
    }

    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = providers.environmentVariable("REVEILLE_KEYSTORE_PASSWORD").get()
                keyAlias = providers.environmentVariable("REVEILLE_KEY_ALIAS").get()
                keyPassword = providers.environmentVariable("REVEILLE_KEY_PASSWORD").get()
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (releaseKeystore != null) signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            // Robolectric reads ~/.m2/settings.xml without interpolating ${user.home}; pin the local repo explicitly.
            it.systemProperty("maven.repo.local", "${System.getProperty("user.home")}/.m2/repository")
        }
    }
}

ksp {
    arg("room.generateKotlin", "true")
}

dependencies {
    implementation(project(":engine"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.datastore.preferences)
    implementation(libs.work.runtime.ktx)
    implementation(libs.glance.appwidget)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
