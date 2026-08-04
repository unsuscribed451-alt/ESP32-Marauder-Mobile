package com.marauder.mobile.esp

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Fetches firmware images from the GitHub release
 * [Firmware.RELEASE_PAGE]. Downloads happen over HTTPS with redirect handling
 * (release asset URLs redirect to a CDN host), and each image is checked against
 * `SHA256SUMS.txt` when that file is present in the release.
 */
class FirmwareRepository {

    /** Download one release asset by name, reporting 0f..1f progress. */
    suspend fun download(assetName: String, onProgress: (Float) -> Unit): ByteArray =
        withContext(Dispatchers.IO) {
            val url = "https://github.com/${Firmware.REPO}/releases/download/${Firmware.TAG}/$assetName"
            fetch(url, onProgress)
        }

    /** Parse `SHA256SUMS.txt` (`<hex>␠␠<name>` lines) into name→hash, or empty on failure. */
    suspend fun checksums(): Map<String, String> = withContext(Dispatchers.IO) {
        runCatching {
            val url = "https://github.com/${Firmware.REPO}/releases/download/${Firmware.TAG}/SHA256SUMS.txt"
            val text = String(fetch(url) {}, Charsets.UTF_8)
            text.lineSequence()
                .mapNotNull { line ->
                    val parts = line.trim().split(Regex("\\s+"), limit = 2)
                    if (parts.size == 2 && parts[0].length == 64) {
                        parts[1].removePrefix("*").substringAfterLast('/') to parts[0].lowercase()
                    } else null
                }
                .toMap()
        }.getOrDefault(emptyMap())
    }

    /** Verify [data] against [expectedSha256] (lowercase hex); throws on mismatch. */
    fun verifySha256(assetName: String, data: ByteArray, expectedSha256: String) {
        val actual = MessageDigest.getInstance("SHA-256").digest(data)
            .joinToString("") { "%02x".format(it) }
        if (!actual.equals(expectedSha256, ignoreCase = true)) {
            throw FlashException("Checksum mismatch for $assetName — download may be corrupt")
        }
    }

    private fun fetch(startUrl: String, onProgress: (Float) -> Unit): ByteArray {
        var url = URL(startUrl)
        var redirects = 0
        while (true) {
            val conn = (url.openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 15_000
                readTimeout = 30_000
                setRequestProperty("User-Agent", "MarauderMobile-Flasher")
                setRequestProperty("Accept", "application/octet-stream")
            }
            try {
                when (val code = conn.responseCode) {
                    HttpURLConnection.HTTP_OK -> {
                        val total = conn.contentLengthLong
                        conn.inputStream.use { input ->
                            val out = ByteArrayOutputStream(if (total > 0) total.toInt() else 1 shl 20)
                            val buf = ByteArray(16 * 1024)
                            var read = 0L
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                read += n
                                if (total > 0) onProgress((read.toFloat() / total).coerceIn(0f, 1f))
                            }
                            return out.toByteArray()
                        }
                    }

                    in REDIRECT_CODES -> {
                        val location = conn.getHeaderField("Location")
                            ?: throw FlashException("Redirect without a Location header")
                        if (++redirects > MAX_REDIRECTS) throw FlashException("Too many redirects")
                        url = URL(url, location)
                    }

                    else -> throw FlashException("Download failed (HTTP $code) for ${url.path.substringAfterLast('/')}")
                }
            } finally {
                conn.disconnect()
            }
        }
    }

    private companion object {
        val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
        const val MAX_REDIRECTS = 5
    }
}
