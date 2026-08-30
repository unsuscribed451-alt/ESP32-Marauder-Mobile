package com.marauder.mobile.capture

import android.content.Context
import com.marauder.mobile.protocol.CaptureFrame
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream

/**
 * Writes streamed capture frames to a file in the phone's own storage — the
 * on-phone replacement for the ESP32's SD card. Files land in the app-specific
 * external directory (`Android/data/<pkg>/files/captures`), which needs no
 * runtime storage permission and is visible over USB/MTP for the user to pull
 * off. One [CaptureSink] serves the whole app; a session spans one capture
 * command (open on start, close on stop). Every method is synchronized because
 * frames arrive on the serial reader thread while the UI/VM reads counters.
 */
class CaptureSink(private val context: Context) {

    data class Summary(val path: String, val bytes: Long, val missedFrames: Long)

    private var out: BufferedOutputStream? = null

    @Volatile var path: String? = null
        private set
    @Volatile var bytes: Long = 0L
        private set
    @Volatile var missedFrames: Long = 0L
        private set

    private var lastSeq = -1L

    val isActive: Boolean get() = out != null

    /** Open a new capture file named after [baseName], with the extension for the
     *  frame [type] (pcap/log/gpx). */
    @Synchronized
    fun begin(baseName: String, type: Int): String? = beginFile(baseName, CaptureFrame.extension(type))

    /** Open a new capture file with an explicit [extension] (e.g. "csv" for a
     *  wardrive log whose content is WigleWiFi CSV). Returns its absolute path. */
    @Synchronized
    fun beginFile(baseName: String, extension: String): String? {
        end()
        return try {
            val dir = File(context.getExternalFilesDir(null), "captures").apply { mkdirs() }
            val f = uniqueFile(dir, sanitize(baseName), extension)
            out = BufferedOutputStream(FileOutputStream(f))
            path = f.absolutePath
            bytes = 0L
            missedFrames = 0L
            lastSeq = -1L
            f.absolutePath
        } catch (e: Exception) {
            out = null
            null
        }
    }

    /** Append one frame's payload, tracking any gap in the frame sequence. */
    @Synchronized
    fun write(frame: CaptureFrame) {
        val o = out ?: return
        if (lastSeq >= 0 && frame.seq > lastSeq + 1) missedFrames += frame.seq - lastSeq - 1
        lastSeq = frame.seq
        try {
            o.write(frame.payload)
            bytes += frame.payload.size
        } catch (_: Exception) {
            // Storage error: stop writing but keep the app alive.
            runCatching { o.close() }
            out = null
        }
    }

    /** Append raw bytes (used for the on-phone wardrive CSV, which has no frames). */
    @Synchronized
    fun appendBytes(b: ByteArray) {
        val o = out ?: return
        try {
            o.write(b)
            bytes += b.size
        } catch (_: Exception) {
            runCatching { o.close() }
            out = null
        }
    }

    /** Close the current file (if any) and return what was written. */
    @Synchronized
    fun end(): Summary? {
        val o = out ?: return null
        runCatching { o.flush(); o.close() }
        out = null
        return Summary(path ?: "", bytes, missedFrames)
    }

    private fun uniqueFile(dir: File, base: String, ext: String): File {
        var i = 0
        while (true) {
            val f = File(dir, "${base}_$i.$ext")
            if (!f.exists()) return f
            i++
        }
    }

    private fun sanitize(s: String): String =
        s.replace(Regex("[^A-Za-z0-9_-]"), "_").take(40).ifEmpty { "capture" }
}
