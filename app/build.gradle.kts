plugins {
    id("com.android.application")
    kotlin("android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "studio.nuvora.veila"
    compileSdk = 35
    defaultConfig {
        applicationId = "studio.nuvora.veila"
        minSdk = 29
        targetSdk = 35
        versionCode = 11
        versionName = "0.7.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        manifestPlaceholders["appLabel"] = "Veila Paint"
    }
    signingConfigs {
        getByName("debug") {
            // Use the historical key when it is supplied by CI.  A clean checkout
            // still builds with Android's standard debug key, which keeps local and
            // public CI verification possible without storing credentials in source.
            val storePasswordValue = providers.environmentVariable("VEILA_STORE_PASSWORD").orNull
            val keyAliasValue = providers.environmentVariable("VEILA_KEY_ALIAS").orNull
            val keyPasswordValue = providers.environmentVariable("VEILA_KEY_PASSWORD").orNull
            val historicalStore = rootProject.file("signing/veila-development.jks")
            if (historicalStore.isFile && storePasswordValue != null &&
                keyAliasValue != null && keyPasswordValue != null) {
                storeFile = historicalStore
                storePassword = storePasswordValue
                keyAlias = keyAliasValue
                keyPassword = keyPasswordValue
            }
        }
    }
    buildTypes {
        create("preview") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".preview"
            versionNameSuffix = "-preview"
            manifestPlaceholders["appLabel"] = "Veila Paint · Essai"
            matchingFallbacks += "debug"
        }
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    testOptions { unitTests.isIncludeAndroidResources = true }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}

dependencies {
    implementation(project(":core"))
    implementation("androidx.media3:media3-transformer:1.6.1")
    implementation("androidx.media3:media3-effect:1.6.1")
    implementation(platform("androidx.compose:compose-bom:2025.03.00"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation(platform("androidx.compose:compose-bom:2025.03.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

// Robolectric fetches its framework image at test execution time. CI can provide a
// pre-populated local Maven repository when outbound DNS is intentionally disabled.
val robolectricMavenRepo = providers.environmentVariable("ROBOLECTRIC_MAVEN_REPO")
val testProxy = System.getenv("HTTPS_PROXY") ?: System.getenv("HTTP_PROXY")
val testProxyHost = testProxy?.substringAfter("://")?.substringBefore(":")
val testProxyPort = testProxy?.substringAfterLast(":")?.toIntOrNull()
tasks.withType<Test>().configureEach {
    robolectricMavenRepo.orNull?.let { systemProperty("maven.repo.local", it) }
    // Robolectric resolves framework images while tests run, outside Gradle's own HTTP client.
    testProxyHost?.let { host ->
        systemProperty("http.proxyHost", host)
        systemProperty("https.proxyHost", host)
    }
    testProxyPort?.let { port ->
        systemProperty("http.proxyPort", port)
        systemProperty("https.proxyPort", port)
    }
}
