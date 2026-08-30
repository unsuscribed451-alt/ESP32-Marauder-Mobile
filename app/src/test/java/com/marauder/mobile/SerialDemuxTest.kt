package com.marauder.mobile

import com.marauder.mobile.protocol.CaptureFrame
import com.marauder.mobile.protocol.Crc32
import com.marauder.mobile.protocol.SerialDemux
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * Verifies the host demultiplexer against the exact frame the firmware emits
 * (Buffer.cpp `saveSerial`): CRC-32 must match the standard vector the firmware
 * computes, and text lines and binary capture frames must be separated cleanly —
 * including when a frame is split across read chunks.
 */
class SerialDemuxTest {

    @Test fun crc32MatchesStandardVector() {
        // The canonical CRC-32/ISO-HDLC check value for "123456789".
        val data = "123456789".toByteArray(Charsets.US_ASCII)
        assertEquals(0xCBF43926L, Crc32.compute(data, 0, data.size, ByteArray(0), 0))
    }

    @Test fun separatesLinesAndFrames() {
        val payload = ByteArray(300) { (it and 0xFF).toByte() } // includes 0x0A, 0x0D, 0xFE bytes
        val stream = ByteArrayOutputStream().apply {
            write("@J {\"t\":\"status\"}\n".toByteArray(Charsets.UTF_8))
            write(frame(seq = 7, type = CaptureFrame.TYPE_PCAP, payload = payload))
            write("plain console line\n".toByteArray(Charsets.UTF_8))
        }.toByteArray()

        val (lines, frames) = run(stream, chunk = stream.size) // one chunk

        assertEquals(listOf("@J {\"t\":\"status\"}", "plain console line"), lines)
        assertEquals(1, frames.size)
        assertEquals(7L, frames[0].seq)
        assertEquals(CaptureFrame.TYPE_PCAP, frames[0].type)
        assertArrayEquals(payload, frames[0].payload)
    }

    @Test fun reassemblesFrameSplitAcrossChunks() {
        val payload = ByteArray(1024) { ((it * 31) and 0xFF).toByte() }
        val stream = ByteArrayOutputStream().apply {
            write("line1\n".toByteArray(Charsets.UTF_8))
            write(frame(seq = 1, type = CaptureFrame.TYPE_PCAP, payload = payload))
            write(frame(seq = 2, type = CaptureFrame.TYPE_LOG, payload = "hello".toByteArray()))
        }.toByteArray()

        // Feed one byte at a time — the worst case for a byte-oriented state machine.
        val (lines, frames) = run(stream, chunk = 1)

        assertEquals(listOf("line1"), lines)
        assertEquals(2, frames.size)
        assertArrayEquals(payload, frames[0].payload)
        assertEquals(2L, frames[1].seq)
        assertArrayEquals("hello".toByteArray(), frames[1].payload)
    }

    @Test fun rejectsCorruptedFrameThenResyncs() {
        val good = frame(seq = 1, type = CaptureFrame.TYPE_PCAP, payload = byteArrayOf(1, 2, 3, 4))
        good[good.size - 1] = (good[good.size - 1] + 1).toByte() // corrupt the CRC
        val stream = ByteArrayOutputStream().apply {
            write(good)
            write(frame(seq = 2, type = CaptureFrame.TYPE_PCAP, payload = byteArrayOf(9, 9)))
        }.toByteArray()

        var errors = 0
        val frames = ArrayList<CaptureFrame>()
        val d = SerialDemux(onLine = {}, onFrame = { frames.add(it) }, onError = { errors++ })
        d.feed(stream, stream.size)

        assertTrue("corrupt frame should be reported", errors >= 1)
        assertEquals("valid frame after a bad one still parses", 1, frames.size)
        assertEquals(2L, frames[0].seq)
    }

    // --- helpers: build a frame exactly as the firmware does ------------------

    private fun run(stream: ByteArray, chunk: Int): Pair<List<String>, List<CaptureFrame>> {
        val lines = ArrayList<String>()
        val frames = ArrayList<CaptureFrame>()
        val d = SerialDemux(onLine = { lines.add(it) }, onFrame = { frames.add(it) })
        var i = 0
        while (i < stream.size) {
            val n = minOf(chunk, stream.size - i)
            d.feed(stream.copyOfRange(i, i + n), n)
            i += n
        }
        return lines to frames
    }

    private fun frame(seq: Long, type: Int, payload: ByteArray): ByteArray {
        val body = ByteArray(9 + payload.size) // seq(4) + type(1) + len(4) + payload
        putLe32(body, 0, seq)
        body[4] = type.toByte()
        putLe32(body, 5, payload.size.toLong())
        System.arraycopy(payload, 0, body, 9, payload.size)
        val crc = Crc32.compute(body, 0, 9, payload, payload.size)

        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0xFE.toByte(), 0xED.toByte(), 0xFA.toByte(), 0xCE.toByte()))
        out.write(body)
        val crcBytes = ByteArray(4).also { putLe32(it, 0, crc) }
        out.write(crcBytes)
        return out.toByteArray()
    }

    private fun putLe32(a: ByteArray, off: Int, v: Long) {
        a[off] = (v and 0xFF).toByte()
        a[off + 1] = ((v shr 8) and 0xFF).toByte()
        a[off + 2] = ((v shr 16) and 0xFF).toByte()
        a[off + 3] = ((v shr 24) and 0xFF).toByte()
    }
}
