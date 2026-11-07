import java.util.Properties
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

// Release signing — keystore.properties (gitignored) holds the store/key
// passwords; it's absent on CI/other machines, so release signing there
// silently falls back to unsigned rather than failing the build.
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use { load(it) }
    }
}

android {
    namespace = "iam699030.gmail.movitop"
    compileSdk = 36

    defaultConfig {
        applicationId = "iam699030.gmail.movitop"
        minSdk = 24
        targetSdk = 36
        versionCode = 4
        versionName = "1.3"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Real phones are effectively 100% arm64-v8a — x86_64 only matters
        // for emulators/Chromebooks, which isn't who this app ships to (see
        // package_motis_data.sh: APKs are handed out directly, not through
        // Play Store's per-device delivery, so whatever ships here is the
        // one file every user installs). libmotis.so alone is ~95-100MB per
        // ABI, so a universal APK effectively pays for it twice. The
        // x86_64 .so under app/src/main/jniLibs/x86_64/ stays on disk
        // unchanged — compile_motis_graph.sh still uses it directly as its
        // dev-machine preprocessing binary, this filter only affects what
        // Gradle packages into the APK.
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    signingConfigs {
        if (keystorePropertiesFile.exists()) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (keystorePropertiesFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        buildConfig = true
    }
    kotlinOptions {
        jvmTarget = "11"
    }

    // The MOTIS engine binary ships as app/src/main/jniLibs/<abi>/libmotis.so
    // (see build_motis_arm64.sh). It must land on disk under the app's
    // nativeLibraryDir as a real, installer-extracted file — Android 10+
    // forbids exec() of anything the app itself wrote (and can therefore
    // also make executable) inside its own writable data directory, so a
    // copy-to-filesDir-then-chmod approach is blocked on real devices.
    // useLegacyPackaging forces that on-disk extraction instead of leaving
    // native libs page-aligned/mmapped straight out of the (compressed) APK.
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.kotlinx.coroutines.android)

    // Mapsforge offline vector maps
    implementation(libs.mapsforge.core)
    implementation(libs.mapsforge.map)
    implementation(libs.mapsforge.map.reader)
    implementation(libs.mapsforge.themes)
    implementation(libs.mapsforge.map.android)
    implementation(libs.androidsvg)

    // Networking (MOTIS REST API)
    implementation(libs.retrofit)
    implementation(libs.retrofit.converter.gson)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}

/**
 * Every APK must carry the complete offline transit graph, line-schedule DB,
 * timezone files and Mapsforge map. Do not silently emit a broken "offline"
 * APK without this archive.
 *
 * For reproducible builds, supply -PmovitopDataZip=/path/to/verified.zip
 * (the weekly CI job uses its freshly generated archive). In ordinary local
 * builds the original project's published snapshot is downloaded once into
 * Gradle's build directory. This download runs on the developer/CI machine,
 * NEVER on the installed Android device.
 */
val bundledTransitAssets = layout.buildDirectory.dir("generated/bundledTransitData/assets")
val prepareBundledTransitData = tasks.register("prepareBundledTransitData") {
    val destination = bundledTransitAssets.map { it.file("motis-data.zip") }
    val overridePath = providers.gradleProperty("movitopDataZip")
    inputs.property("dataZipOverride", overridePath.orElse("published-release"))
    outputs.file(destination)
    doLast {
        val output = destination.get().asFile
        output.parentFile.mkdirs()
        if (overridePath.isPresent) {
            val local = file(overridePath.get())
            check(local.isFile && local.length() > 1_000_000L) {
                "Transit data ZIP not found or empty: ${local.absolutePath}"
            }
            local.copyTo(output, overwrite = true)
        } else {
            val downloadUrl =
                "https://github.com/a-true-man/MovitTop/releases/download/transit-data-latest/movitop-data-latest.zip"
            val part = File(output.parentFile, output.name + ".part")
            try {
                val connection = URL(downloadUrl).openConnection() as HttpURLConnection
                connection.connectTimeout = 30_000
                connection.readTimeout = 120_000
                connection.instanceFollowRedirects = true
                try {
                    check(connection.responseCode in 200..299) {
                        "Cannot download transit data: HTTP ${connection.responseCode}"
                    }
                    connection.inputStream.use { input ->
                        part.outputStream().buffered().use { input.copyTo(it, 1024 * 1024) }
                    }
                } finally {
                    connection.disconnect()
                }
                check(part.length() > 1_000_000L) { "Downloaded transit archive is empty" }
                if (output.exists()) output.delete()
                check(part.renameTo(output)) { "Cannot activate downloaded transit data archive" }
            } finally {
                if (part.exists()) part.delete()
            }
        }

        // Check the content, not just the file extension. Graph and schedules
        // must both exist or the installation cannot work.
        java.util.zip.ZipFile(output).use { zip ->
            check(zip.getEntry("config.yml") != null ||
                zip.getEntry("./config.yml") != null) {
                "Missing MOTIS config.yml in bundled transit archive"
            }
            check(zip.getEntry("line_schedules.sqlite") != null ||
                zip.getEntry("./line_schedules.sqlite") != null) {
                "Missing line_schedules.sqlite in bundled transit archive"
            }
        }
        logger.lifecycle("Bundled offline transit data: ${output.length()} bytes")
    }
}

android.sourceSets.getByName("main").assets.srcDir(bundledTransitAssets)
tasks.configureEach {
    if (name.startsWith("merge") && name.endsWith("Assets")) {
        dependsOn(prepareBundledTransitData)
    }
}
