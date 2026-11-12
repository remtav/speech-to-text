import java.io.File
import java.net.URI
import java.security.MessageDigest
import java.util.zip.ZipFile

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// ---------------------------------------------------------------------------
// Native dependencies fetched from their public releases, verified by SHA-256.
// sherpa-onnx is not on any Maven repository (JitPack has no build of 2.0.1),
// and the Silero VAD (0.6 MB) is the only model shipped inside the APK.
// ---------------------------------------------------------------------------
val sherpaVersion = "2.0.1"
val sherpaBase = "https://github.com/k2-fsa/sherpa-onnx/releases/download"

data class Fetched(val url: String, val sha256: String, val dest: File)

val libsDir = file("libs")
val hostNativeDir = layout.buildDirectory.dir("host-native").get().asFile

val sherpaAar = Fetched(
    "$sherpaBase/v$sherpaVersion/sherpa-onnx-$sherpaVersion.aar",
    "a8d09d1d32f3eb8520482c6d3d43fae674681c5b050385967c70a3c2c9bf0ee7",
    File(libsDir, "sherpa-onnx-$sherpaVersion.aar"),
)
val sileroVad = Fetched(
    "$sherpaBase/asr-models/silero_vad.onnx",
    "9e2449e1087496d8d4caba907f23e0bd3f78d91fa552479bb9c23ac09cbb1fd6",
    file("src/main/assets/silero_vad.onnx"),
)
// Same sherpa-onnx release built for Linux x86-64: lets JVM unit tests run the
// real recognizer and VAD with the classes of the Android AAR (see DECISIONS.md).
val sherpaLinuxJni = Fetched(
    "$sherpaBase/v$sherpaVersion/sherpa-onnx-native-lib-linux-x64-$sherpaVersion.jar",
    "744330a68a7024d9412f029c23b06f847b8444787c94e5118e0fdaf1b4b2cb3f",
    File(hostNativeDir, "sherpa-onnx-native-lib-linux-x64-$sherpaVersion.jar"),
)

fun sha256(f: File): String {
    val md = MessageDigest.getInstance("SHA-256")
    f.inputStream().use { input ->
        val buf = ByteArray(1 shl 16)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            md.update(buf, 0, n)
        }
    }
    return md.digest().joinToString("") { "%02x".format(it) }
}

fun fetch(item: Fetched) {
    if (item.dest.isFile && sha256(item.dest) == item.sha256) return
    item.dest.parentFile.mkdirs()
    val tmp = File(item.dest.path + ".part")
    var lastError: Exception? = null
    for (attempt in 1..4) {
        try {
            logger.lifecycle("Downloading ${item.url}")
            URI(item.url).toURL().openStream().use { input ->
                tmp.outputStream().use { input.copyTo(it) }
            }
            val actual = sha256(tmp)
            check(actual == item.sha256) { "Checksum mismatch for ${item.url}: $actual" }
            tmp.renameTo(item.dest)
            return
        } catch (e: Exception) {
            lastError = e
            Thread.sleep(2000L shl attempt)
        }
    }
    throw GradleException("Could not fetch ${item.url}", lastError)
}

// Runs at configuration time: the AAR must exist before dependency resolution.
fetch(sherpaAar)
fetch(sileroVad)

val fetchHostNative by tasks.registering {
    description = "Fetches and unpacks the Linux x86-64 sherpa-onnx JNI libraries for JVM tests."
    outputs.dir(File(hostNativeDir, "linux-x64"))
    doLast {
        fetch(sherpaLinuxJni)
        val out = File(hostNativeDir, "linux-x64").apply { mkdirs() }
        ZipFile(sherpaLinuxJni.dest).use { zip ->
            zip.entries().asSequence().filter { it.name.endsWith(".so") }.forEach { e ->
                val target = File(out, e.name.substringAfterLast('/'))
                zip.getInputStream(e).use { input -> target.outputStream().use { input.copyTo(it) } }
            }
        }
    }
}

android {
    namespace = "io.github.remtav.dictee"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.remtav.dictee"
        minSdk = 29
        targetSdk = 35
        // CI passes the workflow run number so each published APK upgrades the previous one.
        versionCode = (project.findProperty("versionCode") as String?)?.toInt() ?: 1
        versionName = (project.findProperty("versionName") as String?) ?: "0.1.0-dev"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        // Debug key committed on purpose (issue #10): every APK installs over the previous one.
        getByName("debug") {
            storeFile = rootProject.file("keystore/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("debug")
            ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
        }
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
            ndk { abiFilters += listOf("arm64-v8a") }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    buildFeatures { buildConfig = true }

    packaging {
        jniLibs { useLegacyPackaging = true }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.all { test ->
            test.dependsOn(fetchHostNative)
            val nativeDir = File(hostNativeDir, "linux-x64").absolutePath
            test.systemProperty("java.library.path", nativeDir)
            test.environment("LD_LIBRARY_PATH", nativeDir)
            test.systemProperty("dictee.assetsDir", file("src/main/assets").absolutePath)
            test.systemProperty("dictee.refDir", file("src/main/assets/reference").absolutePath)
            // Directory holding (or receiving) the STT models for the model tests;
            // without it those tests are skipped.
            (project.findProperty("modelsDir") as String? ?: System.getenv("DICTEE_MODELS_DIR"))
                ?.let { test.systemProperty("dictee.modelsDir", it) }
            test.maxHeapSize = "2g"
            test.testLogging {
                events("passed", "skipped", "failed")
                showStandardStreams = true
                exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
            }
        }
    }
}

dependencies {
    implementation(files("libs/sherpa-onnx-$sherpaVersion.aar"))
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    // Optional text clean-up by a small local LLM (lot 3). arm64 only; guarded at runtime.
    implementation("com.google.mediapipe:tasks-genai:0.10.35")

    testImplementation("junit:junit:4.13.2")
    // Real org.json for JVM tests (android.jar only has stubs).
    testImplementation("org.json:json:20240303")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")

    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation("androidx.test:core-ktx:1.6.1")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
}
