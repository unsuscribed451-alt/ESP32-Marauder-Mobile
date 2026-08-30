package com.marauder.mobile.protocol

import java.io.ByteArrayOutputStream

/**
 * Demultiplexes the ESP32's mixed serial stream into text lines and binary
 * capture frames.
 *
 * The firmware interleaves ordinary text output (console text and `@J {…}` JSON
 * lines, each terminated by `\n`) with length-prefixed binary capture frames.
 * Text can never contain a raw `0xFE` byte (it is never valid UTF-8, and the
 * firmware escapes all SSID bytes), so `0xFE` unambiguously marks the start of a
 * binary frame's 4-byte SYNC. A frame is:
 *
 *   SYNC(FE ED FA CE) | seq(4 LE) | type(1) | len(4 LE) | payload(len) | crc32(4 LE)
 *
 * The parser is a byte-oriented state machine so it is correct across arbitrary
 * read-chunk boundaries (a SYNC, header, payload or CRC may be split across
 * reads). It never throws: a bad length or a CRC mismatch is reported via
 * [onError] and the parser resyncs on the next `0xFE`. This is the single point
 * that keeps binary capture bytes from corrupting the line-oriented parser (and
 * vice-versa), so both the mobile and desktop serial managers route every
 * incoming byte through it.
 */
class SerialDemux(
    private val onLine: (String) -> Unit,
    private val onFrame: (CaptureFrame) -> Unit,
    private val onError: (String) -> Unit = {},
) {
    private enum class State { TEXT, SYNC, HEADER, PAYLOAD, CRC }

    private var state = State.TEXT
    private var syncIdx = 0

    private val line = ByteArrayOutputStream(256)

    private val header = ByteArray(HEADER_LEN) // seq(4) + type(1) + len(4)
    private var headerPos = 0

    private var seq = 0L
    private var type = 0
    private var payloadLen = 0
    private var payload = ByteArray(0)
    private var payloadPos = 0

    private val crcBytes = ByteArray(4)
    private var crcPos = 0

    /** Discard any partial state (call on connect / port re-open). */
    fun reset() {
        state = State.TEXT
        syncIdx = 0
        headerPos = 0
        payloadPos = 0
        crcPos = 0
        line.reset()
    }

    /** Feed [len] freshly-read bytes from [data]. */
    fun feed(data: ByteArray, len: Int) {
        var i = 0
        while (i < len) {
            when (state) {
                State.TEXT -> {
                    val b = data[i].toInt() and 0xFF
                    when (b) {
                        SYNC0 -> { emitLine(); state = State.SYNC; syncIdx = 1 }
                        '\n'.code -> emitLine()
                        '\r'.code -> { /* drop CR */ }
                        else -> line.write(b)
                    }
                    i++
                }

                State.SYNC -> {
                    val b = data[i].toInt() and 0xFF
                    if (b == SYNC[syncIdx]) {
                        syncIdx++
                        if (syncIdx == SYNC.size) { state = State.HEADER; headerPos = 0 }
                        i++
                    } else {
                        // Not a real SYNC after all: the matched bytes were data.
                        // Push them back as text and re-process this byte in TEXT
                        // (do NOT advance i).
                        for (k in 0 until syncIdx) line.write(SYNC[k])
                        state = State.TEXT
                        syncIdx = 0
                    }
                }

                State.HEADER -> {
                    header[headerPos++] = data[i]; i++
                    if (headerPos == HEADER_LEN) {
                        seq = le32(header, 0)
                        type = header[4].toInt() and 0xFF
                        payloadLen = le32(header, 5).toInt()
                        if (payloadLen < 0 || payloadLen > MAX_PAYLOAD) {
                            onError("bad frame length $payloadLen (seq=$seq) — resyncing")
                            resetToText()
                        } else {
                            payload = ByteArray(payloadLen)
                            payloadPos = 0
                            crcPos = 0
                            state = if (payloadLen == 0) State.CRC else State.PAYLOAD
                        }
                    }
                }

                State.PAYLOAD -> {
                    val remaining = payloadLen - payloadPos
                    val avail = len - i
                    val n = if (remaining < avail) remaining else avail
                    System.arraycopy(data, i, payload, payloadPos, n)
                    payloadPos += n
                    i += n
                    if (payloadPos == payloadLen) { state = State.CRC; crcPos = 0 }
                }

                State.CRC -> {
                    crcBytes[crcPos++] = data[i]; i++
                    if (crcPos == 4) {
                        val got = le32(crcBytes, 0) and 0xFFFFFFFFL
                        val calc = Crc32.compute(header, 0, HEADER_LEN, payload, payloadLen)
                        if (got == calc) onFrame(CaptureFrame(seq, type, payload))
                        else onError("crc mismatch (seq=$seq, ${payload.size} bytes) — dropping frame")
                        resetToText()
                    }
                }
            }
        }
    }

    private fun emitLine() {
        if (line.size() == 0) return
        val text = line.toString("UTF-8")
        line.reset()
        if (text.isNotEmpty()) onLine(text)
    }

    private fun resetToText() {
        state = State.TEXT
        syncIdx = 0
    }

    private fun le32(a: ByteArray, off: Int): Long =
        (a[off].toLong() and 0xFF) or
            ((a[off + 1].toLong() and 0xFF) shl 8) or
            ((a[off + 2].toLong() and 0xFF) shl 16) or
            ((a[off + 3].toLong() and 0xFF) shl 24)

    companion object {
        private const val SYNC0 = 0xFE
        private val SYNC = intArrayOf(0xFE, 0xED, 0xFA, 0xCE)
        private const val HEADER_LEN = 9 // seq(4) + type(1) + len(4)

        /** Sanity cap so a corrupt length can't force a huge allocation. */
        private const val MAX_PAYLOAD = 4 * 1024 * 1024
    }
}
