plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.ksp)
}

ksp { arg("room.schemaLocation", "$projectDir/schemas") }

android {
    namespace = "dev.rojink.browser"
    compileSdk { version = release(37) }

    defaultConfig {
        applicationId = "dev.rojink.browser"
        minSdk = 26
        targetSdk = 37
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        versionCode = providers.gradleProperty("rojinkVersionCode").orNull?.toInt() ?: 1
        versionName = "1.1.0"
    }

    val keyFile = System.getenv("ANDROID_KEYSTORE_FILE")?.takeIf { it.isNotBlank() }
    val keySettings = listOf("ANDROID_STORE_PASSWORD", "ANDROID_KEY_ALIAS", "ANDROID_KEY_PASSWORD")
        .associateWith { System.getenv(it)?.takeIf(String::isNotBlank) }
    val releaseKey = if (keyFile != null) {
        check(keySettings.values.all { it != null }) { "Incomplete Android release signing configuration." }
        check(file(keyFile).isFile) { "Android release keystore file does not exist." }
        signingConfigs.create("production") {
            storeFile = file(keyFile)
            storePassword = keySettings["ANDROID_STORE_PASSWORD"]
            keyAlias = keySettings["ANDROID_KEY_ALIAS"]
            keyPassword = keySettings["ANDROID_KEY_PASSWORD"]
        }
    } else null

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            signingConfig = releaseKey
        }
    }

    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions { unitTests.isReturnDefaultValues = false }
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.icons)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.core)
    implementation(libs.androidx.webkit)
    implementation(libs.androidx.room)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.datastore)
    implementation(libs.coroutines.android)
    implementation(libs.coroutines.tasks)
    implementation(libs.mlkit.latin)
    implementation(libs.mlkit.chinese)
    implementation(libs.mlkit.japanese)
    implementation(libs.mlkit.korean)
    implementation(libs.mlkit.devanagari)
    implementation(libs.okhttp)
    implementation(libs.jsoup)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    testImplementation(libs.junit)
    testImplementation("org.json:json:20250107")
    testImplementation(libs.okhttp.mockwebserver)
}
