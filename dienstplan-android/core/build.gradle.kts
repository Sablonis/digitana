import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    api(libs.kotlinx.coroutines.core)
    api(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    // BIP-340-Schnorr über die JNI-Bindung von libsecp256k1 (ACINQ). Die nativen
    // Bibliotheken kommen je Plattform dazu: jni-jvm für Tests, jni-android in :app.
    implementation(libs.secp256k1.kmp)
    // HKDF und AES-256-GCM aus Google Tink.
    implementation(libs.tink.android) {
        // Enthält nur Annotationen (@RequiresApi …). In der App kommen sie ohnehin
        // über AndroidX; so bleibt :core ohne Google-Maven-Abhängigkeit baubar.
        exclude(group = "androidx.annotation")
    }

    // MLS-Bibliothek (Rust) über JNA: In der App kommt die AAR-Variante mit den nativen
    // JNA-Bibliotheken für Android dazu, in den Tests das Jar für den Rechner.
    compileOnly(libs.jna)
    testImplementation(libs.jna)

    testRuntimeOnly(libs.secp256k1.kmp.jni.jvm)
    testImplementation(kotlin("test"))
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver3)
    testImplementation(libs.okhttp.tls)
}

// Der Hauptcode zielt auf Java 17 (Android). Die nativen JVM-Bibliotheken von
// secp256k1-kmp-jni-jvm werden nur für Java 21+ veröffentlicht, deshalb laufen die
// Tests auf einem JDK 21 (Android Studio bringt ein passendes JBR mit).
configurations.testRuntimeClasspath {
    attributes {
        attribute(TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, 21)
    }
}

// ---------------------------------------------------------------------------
// MLS-Bibliothek (Rust, Ordner mls/): UniFFI erzeugt die Kotlin-Bindings aus der für
// diesen Rechner gebauten Bibliothek, und die JVM-Tests laden dieselbe Bibliothek über
// JNA. Voraussetzung ist Rust (rustup), siehe README. Der Debug-Build behält die
// UniFFI-Metadaten, die der gestrippte Release-Build nicht mehr enthält.
// ---------------------------------------------------------------------------
val mlsDir = layout.projectDirectory.dir("../mls")
val mlsHostLib = mlsDir.file("target/debug/" + System.mapLibraryName("dienstplan_mls"))
val uniffiDir = layout.buildDirectory.dir("generated/uniffi/kotlin")

val cargoBuildHost = tasks.register<Exec>("cargoBuildHost") {
    description = "Baut die MLS-Bibliothek (Rust) für diesen Rechner."
    group = "build"
    workingDir(mlsDir)
    commandLine("cargo", "build", "--locked", "--lib")
    inputs.files(
        fileTree(mlsDir) {
            include("src/**", "Cargo.toml", "Cargo.lock", "uniffi.toml", "rust-toolchain.toml", ".cargo/**")
        },
    )
    outputs.file(mlsHostLib)
}

val generateMlsBindings = tasks.register<Exec>("generateMlsBindings") {
    description = "Erzeugt die Kotlin-Bindings der MLS-Bibliothek (UniFFI)."
    group = "build"
    dependsOn(cargoBuildHost)
    workingDir(mlsDir)
    inputs.file(mlsHostLib)
    outputs.dir(uniffiDir)
    doFirst { delete(uniffiDir) }
    commandLine(
        "cargo", "run", "--locked", "--release", "--features", "bindgen", "--bin", "uniffi-bindgen", "--",
        "generate", "--library", mlsHostLib.asFile.absolutePath,
        "--language", "kotlin", "--no-format", "--out-dir", uniffiDir.get().asFile.absolutePath,
    )
}

kotlin.sourceSets.named("main") {
    kotlin.srcDir(generateMlsBindings)
}

tasks.withType<Test>().configureEach {
    dependsOn(cargoBuildHost)
    systemProperty("jna.library.path", mlsHostLib.asFile.parentFile.absolutePath)
    // Fehlersuche: ./gradlew :core:test -Pdienstplan.testlog=true gibt die Meldungen der Engine aus.
    val testLog = providers.gradleProperty("dienstplan.testlog").orNull == "true"
    if (testLog) systemProperty("dienstplan.testlog", "true")
    testLogging {
        events("passed", "skipped", "failed")
        exceptionFormat = TestExceptionFormat.FULL
        showStandardStreams = testLog
    }
}

// Standard: alles, was ohne Internet läuft (inkl. lokaler TLS-Relays).
tasks.test {
    useJUnitPlatform {
        excludeTags("network")
    }
}

// ./gradlew :core:networkTest – echte Nostr-Relays und badssl.com (direkte Internetverbindung nötig).
tasks.register<Test>("networkTest") {
    description = "Integrationstests gegen echte Relays sowie TLS-Negativtests gegen badssl.com."
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform {
        includeTags("network")
    }
    // Andere Relays: ./gradlew :core:networkTest -Pdienstplan.relays=wss://a,wss://b,wss://c
    providers.gradleProperty("dienstplan.relays").orNull?.let { systemProperty("dienstplan.relays", it) }
    shouldRunAfter(tasks.test)
    outputs.upToDateWhen { false }
}
