plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.ppxb.miwaku.core.data"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        minSdk = 24
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    api(project(":core:model"))
    implementation(project(":core:network"))
    implementation(libs.androidx.core.ktx)
    api(libs.kotlinx.coroutines.core)
}
