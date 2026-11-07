package iam699030.gmail.movitop.data

import android.content.Context
import android.util.Log
import iam699030.gmail.movitop.MotisForegroundService
import java.io.File
import java.io.IOException
import java.util.zip.ZipInputStream

/**
 * Installs the full, build-time bundled MOTIS graph, line-schedule SQLite DB and
 * offline map on first launch. Nothing is downloaded or imported by the user.
 *
 * The archive has the same layout as package_motis_data.sh: graph files at the
 * ZIP root, plus optional israel.map and tzdata/ entries. Work happens off the
 * main thread. Extraction is streamed, staged and swapped with a backup so an
 * interrupted/failed installation does not destroy an older working dataset.
 *
 * Each distinct bundled archive installs once, identified by its SHA-256.
 * Updating the APK with newer data installs the new bundled snapshot; subsequent launches do not re-extract gigabytes.
 */
class BundledTransitDataInstaller(private val context: Context) {

    fun ensureInstalled() = synchronized(installLock) {
        val liveRoot = MotisForegroundService.motisDataDir(context)
        val stageRoot = File(context.filesDir, "motis_data_bundle_staging")
        val backupRoot = File(context.filesDir, "motis_data_bundle_backup")
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val bundledVersion = context.assets.open(VERSION_ASSET).bufferedReader().use { it.readText().trim() }
        if (!bundledVersion.matches(Regex("[0-9a-f]{64}"))) {
            throw IOException("Invalid bundled transit archive version")
        }

        // A previous process might have died between moving live -> backup and
        // moving stage -> live. Restore the known-good version in that case.
        if (backupRoot.exists() && !liveRoot.exists()) {
            if (!backupRoot.renameTo(liveRoot)) {
                throw IOException("Could not restore the previous transit data")
            }
        }

        if (prefs.getString(KEY_BUNDLED_VERSION, null) == bundledVersion && isComplete(liveRoot)) {
            return@synchronized
        }

        if (backupRoot.exists() && !backupRoot.deleteRecursively()) {
            throw IOException("Could not clear an obsolete transit-data backup")
        }
        if (stageRoot.exists() && !stageRoot.deleteRecursively()) {
            throw IOException("Could not clear a previous incomplete extraction")
        }
        if (!stageRoot.mkdirs()) throw IOException("Could not create transit-data staging directory")

        try {
            extractBundledArchive(stageRoot)
            if (!isComplete(stageRoot)) {
                throw IOException("Bundled transit data is missing config.yml or line_schedules.sqlite")
            }

            // On a normal launch the engine has not started yet. Stopping it
            // also protects against a service left running during an update.
            MotisForegroundService.stop(context)

            val hadLive = liveRoot.exists()
            if (hadLive && !liveRoot.renameTo(backupRoot)) {
                throw IOException("Could not back up the previous transit data")
            }
            if (!stageRoot.renameTo(liveRoot)) {
                if (hadLive && !backupRoot.renameTo(liveRoot)) {
                    Log.e(TAG, "Could not restore transit-data backup after failed swap")
                }
                throw IOException("Could not activate the bundled transit data")
            }

            if (!prefs.edit().putString(KEY_BUNDLED_VERSION, bundledVersion).commit()) {
                Log.w(TAG, "Transit data installed, but version marker could not be persisted")
            }
            if (backupRoot.exists() && !backupRoot.deleteRecursively()) {
                Log.w(TAG, "Could not remove old transit-data backup")
            }
            Log.i(TAG, "Bundled transit data installed for APK version $bundledVersion")
        } finally {
            if (stageRoot.exists()) stageRoot.deleteRecursively()
        }
    }

    private fun extractBundledArchive(stageRoot: File) {
        val stageGraph = File(stageRoot, "data")
        if (!stageGraph.mkdirs()) throw IOException("Could not create graph directory")

        var files = 0
        var extractedBytes = 0L
        val buffer = ByteArray(128 * 1024)
        val safeRoot = stageRoot.canonicalPath + File.separator

        context.assets.open(ARCHIVE_NAME).buffered().use { input ->
            ZipInputStream(input).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val name = entry.name.removePrefix("./")

                    if (!entry.isDirectory && name.isNotBlank()) {
                        val destination = when {
                            name == "israel.map" -> File(stageRoot, "israel.map")
                            name.startsWith("tzdata/") -> File(stageRoot, name)
                            else -> File(stageGraph, name)
                        }
                        if (!destination.canonicalPath.startsWith(safeRoot)) {
                            throw IOException("Unsafe path in bundled transit data: $name")
                        }
                        destination.parentFile?.mkdirs()
                        destination.outputStream().buffered().use { output ->
                            while (true) {
                                val read = zip.read(buffer)
                                if (read < 0) break
                                extractedBytes += read
                                if (extractedBytes > MAX_UNPACKED_BYTES) {
                                    throw IOException("Bundled data exceeds permitted unpacked size")
                                }
                                output.write(buffer, 0, read)
                            }
                        }
                        files++
                        if (files > MAX_FILES) throw IOException("Too many files in bundled transit data")
                    }
                    zip.closeEntry() // checks the ZIP entry CRC
                }
            }
        }
        Log.i(TAG, "Extracted $files bundled transit files ($extractedBytes bytes)")
    }

    private fun isComplete(root: File): Boolean =
        File(root, "data/config.yml").let { it.isFile && it.length() > 0L } &&
            File(root, "data/line_schedules.sqlite").let { it.isFile && it.length() > 0L }

    companion object {
        private const val TAG = "BundledTransitData"
        private const val ARCHIVE_NAME = "motis-data.zip"
        private const val VERSION_ASSET = "motis-data.version"
        private const val PREFS_NAME = "bundled_transit_data"
        private const val KEY_BUNDLED_VERSION = "installed_apk_version"
        private const val MAX_FILES = 100_000
        private const val MAX_UNPACKED_BYTES = 5L * 1024 * 1024 * 1024
        private val installLock = Any()
    }
}
