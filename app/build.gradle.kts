import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

// Firebase plugins are applied only when a real google-services.json is present.
// Without it the app still builds and runs with all local (offline) features.
val hasFirebaseConfig = file("google-services.json").exists()
if (hasFirebaseConfig) {
    apply(plugin = "com.google.gms.google-services")
    apply(plugin = "com.google.firebase.crashlytics")
} else {
    logger.warn("LifeOS: app/google-services.json not found - building without Firebase (local-only mode).")
}

// Release signing is read from keystore.properties (never committed).
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}

fun prop(name: String, default: String = ""): String =
    (project.findProperty(name) as String?) ?: default

// Developer AI keys for DEBUG builds only, read from the gitignored local.properties. Only the
// debug build type gets these BuildConfig fields, so release code cannot even reference them.
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun localKey(name: String) = localProps.getProperty(name, "").trim().filter { it.isLetterOrDigit() || it == '_' || it == '-' }
fun localList(name: String, default: String) =
    localProps.getProperty(name, default).trim().filter { it.isLetterOrDigit() || it in "._-," }
val devGeminiKey = localKey("GEMINI_API_KEY")
val devGeminiModels = localList("GEMINI_MODELS", "gemini-3.5-flash-lite,gemini-3-flash-preview,gemini-3.1-flash-lite,gemini-2.5-flash")
val devAnthropicKey = localKey("ANTHROPIC_API_KEY")
val devAnthropicModel = localList("ANTHROPIC_MODEL", "claude-opus-5-5")

android {
    namespace = "com.lifeos.ai"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.lifeos.ai"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }

        manifestPlaceholders["admobAppId"] = prop("lifeos.admob.appId")
        buildConfigField("String", "ADMOB_BANNER_ID", "\"${prop("lifeos.admob.bannerId")}\"")
        buildConfigField("String", "ADMOB_INTERSTITIAL_ID", "\"${prop("lifeos.admob.interstitialId")}\"")
        buildConfigField("String", "ADMOB_NATIVE_ID", "\"${prop("lifeos.admob.nativeId")}\"")
        buildConfigField("boolean", "ADS_ENABLED", prop("lifeos.ads.enabled", "true"))
        buildConfigField("boolean", "FIREBASE_CONFIGURED", hasFirebaseConfig.toString())
    }

    signingConfigs {
        if (keystorePropsFile.exists()) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ""
            isMinifyEnabled = false
            buildConfigField("String", "DEV_GEMINI_API_KEY", "\"$devGeminiKey\"")
            buildConfigField("String", "DEV_GEMINI_MODELS", "\"$devGeminiModels\"")
            buildConfigField("String", "DEV_ANTHROPIC_API_KEY", "\"$devAnthropicKey\"")
            buildConfigField("String", "DEV_ANTHROPIC_MODEL", "\"$devAnthropicModel\"")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (keystorePropsFile.exists()) signingConfig = signingConfigs.getByName("release")
            // No developer keys here: release builds talk to AI only through the Firebase backend.
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    buildFeatures {
        compose = true
        buildConfig = true
    }
    androidResources {
        generateLocaleConfig = true
    }
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
            // Opt-in icon export: ./gradlew :app:testDebugUnitTest --tests "*StoreIconExportTest" -PexportIcons=true
            all {
                it.systemProperty("lifeos.exportIcons", (project.findProperty("exportIcons") ?: "false").toString())
                it.systemProperty("lifeos.iconPreviewDir", (project.findProperty("iconPreviewDir") ?: "").toString())
            }
        }
    }
    packaging {
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.generateKotlin", "true")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.splashscreen)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.animation)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.navigation.compose)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.coil.compose)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    // Claude fallback for developer testing only; release builds never include it (see src/release).
    debugImplementation(libs.anthropic.java)
    implementation(libs.kotlinx.coroutines.play.services)

    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.auth)
    implementation(libs.firebase.firestore)
    implementation(libs.firebase.functions)
    implementation(libs.firebase.messaging)
    implementation(libs.firebase.analytics)
    implementation(libs.firebase.crashlytics)

    implementation(libs.play.services.ads)
    implementation(libs.google.ump)
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play.services)
    implementation(libs.googleid)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.room.testing)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
}
