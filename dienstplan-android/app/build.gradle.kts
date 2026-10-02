import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Signierdaten für Release-Builds: keystore.properties im Projektordner (nicht einchecken!)
// oder Umgebungsvariablen. Siehe docs/RELEASE.md.
val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.isFile) file.inputStream().use { load(it) }
}

fun signingValue(property: String, environment: String): String? =
    keystoreProperties.getProperty(property) ?: System.getenv(environment)

android {
    namespace = "ch.digitana.dienstplan"
    compileSdk = 37

    defaultConfig {
        applicationId = "ch.digitana.dienstplan"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Nur Architekturen, für die die MLS-Bibliothek gebaut wird (siehe cargoBuildAndroid).
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    signingConfigs {
        val storeFilePath = signingValue("storeFile", "DIENSTPLAN_KEYSTORE")
        if (storeFilePath != null) {
            create("release") {
                storeFile = rootProject.file(storeFilePath)
                storePassword = signingValue("storePassword", "DIENSTPLAN_KEYSTORE_PASSWORD")
                keyAlias = signingValue("keyAlias", "DIENSTPLAN_KEY_ALIAS")
                keyPassword = signingValue("keyPassword", "DIENSTPLAN_KEY_PASSWORD")
                // v1 (JAR) ist ab minSdk 24 nicht mehr nötig; AGP würde es sonst weglassen.
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
                enableV4Signing = false
            }
        }
    }

    buildTypes {
        debug {
            // Eigene Paket-ID: Debug- und Release-Build lassen sich nebeneinander installieren,
            // ohne dass unterschiedliche Signaturen zu „App nicht installiert“ führen.
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests {
            // Robolectric braucht Texte, Schrift und Symbole für die Screenshots.
            isIncludeAndroidResources = true
            all {
                it.systemProperty("robolectric.pixelCopyRenderMode", "hardware")
                // Roborazzi schreibt die Bilder immer (zum Durchsehen, kein Pixelvergleich).
                it.systemProperty("roborazzi.test.record", "true")
            }
        }
    }

    packaging {
        resources {
            excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "/META-INF/versions/9/OSGI-INF/MANIFEST.MF")
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

// MLS-Bibliothek (Rust, Ordner mls/) für die Android-Architekturen; landet in
// src/main/jniLibs (nicht eingecheckt). Braucht Rust, cargo-ndk und das NDK (siehe README).
// 32-Bit-x86 fehlt bewusst: Es gibt praktisch keine solchen Geräte mehr.
val mlsDir = layout.projectDirectory.dir("../mls")
val jniLibsDir = layout.projectDirectory.dir("src/main/jniLibs")
val cargoBuildAndroid = tasks.register<Exec>("cargoBuildAndroid") {
    description = "Baut die MLS-Bibliothek (Rust) für arm64-v8a, armeabi-v7a und x86_64."
    group = "build"
    workingDir(mlsDir)
    commandLine(
        "cargo", "ndk", "--platform", "26",
        "-t", "arm64-v8a", "-t", "armeabi-v7a", "-t", "x86_64",
        "-o", jniLibsDir.asFile.absolutePath,
        "build", "--release", "--locked", "--lib",
    )
    inputs.files(
        fileTree(mlsDir) {
            include("src/**", "Cargo.toml", "Cargo.lock", "rust-toolchain.toml", ".cargo/**")
        },
    )
    outputs.dir(jniLibsDir)
}
tasks.named("preBuild") { dependsOn(cargoBuildAndroid) }

dependencies {
    implementation(project(":core"))
    // JNA als AAR (enthält die nativen JNA-Bibliotheken für Android) für die MLS-Bindings.
    implementation(libs.jna) {
        artifact {
            name = "jna"
            type = "aar"
        }
    }

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.zxing.core)
    implementation(libs.androidx.profileinstaller)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.junit.rule)
    testImplementation(libs.roborazzi.accessibility.check)

    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.junit4)
    androidTestImplementation(libs.kotlinx.coroutines.test)
}
