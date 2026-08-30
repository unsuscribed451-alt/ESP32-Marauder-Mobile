package com.marauder.mobile.protocol

/**
 * CRC-32 (IEEE 802.3, polynomial 0xEDB88320) — the exact algorithm the firmware
 * runs over each streamed capture frame (`buf_crc32` in Buffer.cpp), so a frame
 * validates iff its bytes arrived intact. Returned as an unsigned value in a
 * [Long] (0..0xFFFFFFFF) to compare cleanly with the little-endian trailer.
 */
object Crc32 {

    private val table = IntArray(256) { n ->
        var c = n
        repeat(8) { c = if (c and 1 != 0) (POLY xor (c ushr 1)) else (c ushr 1) }
        c
    }

    private const val POLY = -0x12477ce0 // 0xEDB88320 as a signed Int

    /**
     * CRC-32 over [aLen] bytes of [a] (from [aOff]) followed by [bLen] bytes of
     * [b]. The firmware CRCs the frame header (seq+type+len, 9 bytes) immediately
     * followed by the payload, so this two-part form avoids an intermediate copy.
     */
    fun compute(a: ByteArray, aOff: Int, aLen: Int, b: ByteArray, bLen: Int): Long {
        var crc = -1 // 0xFFFFFFFF
        for (i in 0 until aLen) crc = table[(crc xor a[aOff + i].toInt()) and 0xFF] xor (crc ushr 8)
        for (i in 0 until bLen) crc = table[(crc xor b[i].toInt()) and 0xFF] xor (crc ushr 8)
        return crc.inv().toLong() and 0xFFFFFFFFL
    }
}
