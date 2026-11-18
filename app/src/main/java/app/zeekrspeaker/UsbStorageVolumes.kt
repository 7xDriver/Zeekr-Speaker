package app.zeekrspeaker

import android.content.Context
import java.io.File

/** Finds USB volumes the ZEEKR head unit exposes through /proc/mounts. */
object UsbStorageVolumes {
    data class Volume(val root: File, val label: String, val freeBytes: Long, val totalBytes: Long)

    fun discover(context: Context): List<Volume> {
        val found = linkedMapOf<String, File>()
        try {
            File("/proc/mounts").bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    val parts = line.split(Regex("\\s+"))
                    if (parts.size >= 2) {
                        val mount = parts[1].replace("\\040", " ")
                        // Same UUID-style mount point detection used by the application.
                        if (mount.matches(Regex("/storage/[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}"))) {
                            val root = File(mount)
                            if (root.exists() && root.isDirectory && root.canRead()) {
                                found[root.absolutePath] = root
                            }
                        }
                    }
                }
            }
        } catch (_: Exception) { }

        // Secondary standard-API path, as used by the application.
        try {
            val dirs = context.getExternalFilesDirs(null)
            if (dirs != null) {
                for (i in 1 until dirs.size) {
                    val appDir = dirs[i] ?: continue
                    var root = appDir
                    val path = appDir.absolutePath
                    val cut = path.indexOf("/Android/data/")
                    if (cut > 0) {
                        val candidate = File(path.substring(0, cut))
                        if (candidate.exists() && candidate.canRead()) root = candidate
                    }
                    if (root.exists() && root.isDirectory && root.canRead()) {
                        found[root.absolutePath] = root
                    }
                }
            }
        } catch (_: Exception) { }

        return found.values.map { root ->
            Volume(root, "USB drive (${root.name})", root.usableSpace, root.totalSpace)
        }
    }
}
