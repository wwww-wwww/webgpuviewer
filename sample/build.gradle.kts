plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "moe.grass.webgpuviewer.test"
    compileSdk = 36

    defaultConfig {
        applicationId = "moe.grass.webgpuviewer.test"
        minSdk = 24

        versionCode = 4
        versionName = "3.1.0"

        defaultConfig {
            packagingOptions {
                jniLibs.keepDebugSymbols.addAll(listOf("*/mips/*.so", "*/mips64/*.so"))
            }
        }

        buildFeatures {
            compose = true
        }

        compileOptions {
            viewBinding.enable = true
        }
    }

    sourceSets {
        getByName("main").assets.srcDirs("assets")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":library"))

    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.viewpager)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
}
