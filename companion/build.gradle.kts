import java.security.MessageDigest

plugins {
    id("com.android.application")
}

fun sha256(file: java.io.File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().buffered().use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

val supportedAbis = setOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
val torAbis = providers.gradleProperty("torAbis")
    .map { it.split(',').toSet() }.getOrElse(supportedAbis)
require(torAbis.isNotEmpty() && supportedAbis.containsAll(torAbis)) { "Unsupported torAbis" }

android {
    namespace = "app.piko.tor"
    compileSdk = 36
    defaultConfig {
        applicationId = "app.piko.tor"
        minSdk = 29
        targetSdk = 35
        versionCode = 2
        versionName = "0.2.0-experimental"
        ndk { abiFilters += torAbis }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    sourceSets["main"].jniLibs.srcDir(layout.buildDirectory.dir("generated/jniLibs").get().asFile)
    sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("generated/assets").get().asFile)
    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    lint { abortOnError = true }
    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            it.jvmArgs(
                "--add-opens=java.base/java.lang=ALL-UNNAMED",
                "--add-opens=java.base/java.util=ALL-UNNAMED",
                "--add-opens=java.base/java.io=ALL-UNNAMED",
                "--add-opens=java.base/java.net=ALL-UNNAMED",
                "--add-opens=java.base/java.security=ALL-UNNAMED",
                "--add-opens=java.base/java.text=ALL-UNNAMED",
                "--add-opens=java.base/jdk.internal.access=ALL-UNNAMED",
                "--add-opens=java.desktop/java.awt.font=ALL-UNNAMED",
                "--add-opens=jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED",
            )
        }
    }
}

val verifyTorNative = tasks.register("verifyTorNative") {
    val nativeDir = layout.buildDirectory.dir("generated/jniLibs")
    inputs.dir(nativeDir).optional()
    doLast {
        val dir = nativeDir.get().asFile
        check(dir.resolve("onionmasq-revision.txt").takeIf { it.isFile }?.readText()?.trim() ==
            "086d05145f5aa711ac300d04edc19c26360bac29") {
            "Run scripts/build-tor-native.sh before packaging Piko Tor."
        }
        torAbis.forEach { abi ->
            check(dir.resolve("$abi/libonionmasq_mobile.so").isFile) { "Missing Onionmasq ABI: $abi" }
        }
        val patchDigest = sha256(file("native/privacy.patch"))
        check(dir.resolve("privacy-patch.sha256").readText().trim() == patchDigest) {
            "Native privacy patch changed; rebuild the native libraries."
        }
        val checksums = dir.resolve("SHA256SUMS").readLines().associate { line ->
            val (hash, path) = line.split("  ", limit = 2)
            path to hash
        }
        torAbis.forEach { abi ->
            val path = "$abi/libonionmasq_mobile.so"
            check(sha256(dir.resolve(path)) == checksums[path]) {
                "Native library changed since ELF verification: $abi"
            }
        }
        check(layout.buildDirectory.file("generated/assets/licenses/rust-dependencies.json").get().asFile.isFile) {
            "Missing third-party notices. Run scripts/build-tor-native.sh."
        }
        check(layout.buildDirectory.file("generated/assets/licenses/unresolved-notices.json").get().asFile.readText().trim() == "[]") {
            "Unresolved third-party notices. Do not distribute this build."
        }
    }
}
tasks.configureEach {
    if (name.startsWith("merge") && name.endsWith("NativeLibs")) dependsOn(verifyTorNative)
}

dependencies {
    testImplementation(libs.junit)
    testImplementation("org.robolectric:robolectric:4.17")
}
