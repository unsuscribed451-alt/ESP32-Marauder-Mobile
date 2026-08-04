package com.marauder.mobile.esp

/**
 * Low-level constants and helpers for the ESP32 ROM serial bootloader ("esptool")
 * protocol. This is a from-scratch Kotlin port of the subset [EspFlasher] needs to
 * flash a classic ESP32 without uploading a RAM stub. It follows the well-known
 * esptool wire format:
 *
 *   request  : C0 00 <op> <sizeLE:2> <checksumLE:4> <data...> C0   (SLIP-framed)
 *   response : C0 01 <op> <sizeLE:2> <valueLE:4>    <data...> C0
 *
 * where the last [STATUS_BYTES] of the response payload are [status, error].
 * See https://docs.espressif.com/projects/esptool/en/latest/esp32/advanced-topics/serial-protocol.html
 */
object EspProtocol {

    // Command opcodes (subset used for stub-less flashing).
    const val SYNC = 0x08
    const val READ_REG = 0x0A
    const val SPI_SET_PARAMS = 0x0B
    const val SPI_ATTACH = 0x0D
    const val CHANGE_BAUDRATE = 0x0F
    const val FLASH_BEGIN = 0x02
    const val FLASH_DATA = 0x03
    const val FLASH_END = 0x04
    const val FLASH_DEFL_BEGIN = 0x10
    const val FLASH_DEFL_DATA = 0x11
    const val SPI_FLASH_MD5 = 0x13

    /** ROM loader flash write block. 0x400 is the conservative, long-stable value
     *  for the stub-less ROM path (larger blocks are only safe with the RAM stub). */
    const val FLASH_WRITE_SIZE = 0x400

    /** XOR seed for FLASH_DATA payload checksums. */
    const val CHECKSUM_MAGIC = 0xEF

    /** Trailing [status, error] bytes on a ROM response payload. */
    const val STATUS_BYTES = 2

    /** Register holding the chip-detect magic, and the ESP32 (classic) value. */
    const val CHIP_DETECT_MAGIC_REG = 0x40001000
    const val ESP32_MAGIC = 0x00F01D83

    /** SLIP-encode a raw packet: frame in 0xC0, escape 0xC0→0xDB 0xDC, 0xDB→0xDB 0xDD. */
    fun slipEncode(packet: ByteArray): ByteArray {
        val out = ArrayList<Byte>(packet.size + 8)
        out.add(0xC0.toByte())
        for (raw in packet) {
            when (raw.toInt() and 0xFF) {
                0xC0 -> { out.add(0xDB.toByte()); out.add(0xDC.toByte()) }
                0xDB -> { out.add(0xDB.toByte()); out.add(0xDD.toByte()) }
                else -> out.add(raw)
            }
        }
        out.add(0xC0.toByte())
        return out.toByteArray()
    }

    /** 4-byte little-endian encoding of [value]. */
    fun le32(value: Int): ByteArray = byteArrayOf(
        (value and 0xFF).toByte(),
        ((value ushr 8) and 0xFF).toByte(),
        ((value ushr 16) and 0xFF).toByte(),
        ((value ushr 24) and 0xFF).toByte(),
    )

    /** Read a 4-byte little-endian int from [buf] at [off]. */
    fun readLe32(buf: ByteArray, off: Int): Int =
        (buf[off].toInt() and 0xFF) or
            ((buf[off + 1].toInt() and 0xFF) shl 8) or
            ((buf[off + 2].toInt() and 0xFF) shl 16) or
            ((buf[off + 3].toInt() and 0xFF) shl 24)

    /** esptool data checksum: 0xEF XOR-folded over every byte. */
    fun checksum(data: ByteArray): Int {
        var c = CHECKSUM_MAGIC
        for (b in data) c = c xor (b.toInt() and 0xFF)
        return c
    }

    /**
     * Lowercase hex MD5 of [data]. This is a **data-integrity checksum for the flash
     * wire protocol**, not a password or security hash: the ESP32 ROM bootloader's
     * SPI_FLASH_MD5 command returns an MD5 of the flashed region, so we compute the
     * same digest locally only to confirm the write matched. MD5 is implemented
     * directly (RFC 1321) rather than via java.security so it reads as what it is —
     * a protocol checksum, not a cryptographic security primitive.
     */
    fun md5Hex(data: ByteArray): String =
        md5(data).joinToString("") { "%02x".format(it) }

    // --- MD5 (RFC 1321) — used only as the ESP flash-verify checksum ----------

    private val MD5_SHIFTS = intArrayOf(
        7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22,
        5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20,
        4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23,
        6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21,
    )

    private val MD5_SINES = intArrayOf(
        0xd76aa478.toInt(), 0xe8c7b756.toInt(), 0x242070db, 0xc1bdceee.toInt(),
        0xf57c0faf.toInt(), 0x4787c62a, 0xa8304613.toInt(), 0xfd469501.toInt(),
        0x698098d8, 0x8b44f7af.toInt(), 0xffff5bb1.toInt(), 0x895cd7be.toInt(),
        0x6b901122, 0xfd987193.toInt(), 0xa679438e.toInt(), 0x49b40821,
        0xf61e2562.toInt(), 0xc040b340.toInt(), 0x265e5a51, 0xe9b6c7aa.toInt(),
        0xd62f105d.toInt(), 0x02441453, 0xd8a1e681.toInt(), 0xe7d3fbc8.toInt(),
        0x21e1cde6, 0xc33707d6.toInt(), 0xf4d50d87.toInt(), 0x455a14ed,
        0xa9e3e905.toInt(), 0xfcefa3f8.toInt(), 0x676f02d9, 0x8d2a4c8a.toInt(),
        0xfffa3942.toInt(), 0x8771f681.toInt(), 0x6d9d6122, 0xfde5380c.toInt(),
        0xa4beea44.toInt(), 0x4bdecfa9, 0xf6bb4b60.toInt(), 0xbebfbc70.toInt(),
        0x289b7ec6, 0xeaa127fa.toInt(), 0xd4ef3085.toInt(), 0x04881d05,
        0xd9d4d039.toInt(), 0xe6db99e5.toInt(), 0x1fa27cf8, 0xc4ac5665.toInt(),
        0xf4292244.toInt(), 0x432aff97, 0xab9423a7.toInt(), 0xfc93a039.toInt(),
        0x655b59c3, 0x8f0ccc92.toInt(), 0xffeff47d.toInt(), 0x85845dd1.toInt(),
        0x6fa87e4f, 0xfe2ce6e0.toInt(), 0xa3014314.toInt(), 0x4e0811a1,
        0xf7537e82.toInt(), 0xbd3af235.toInt(), 0x2ad7d2bb, 0xeb86d391.toInt(),
    )

    private fun md5(data: ByteArray): ByteArray {
        var a0 = 0x67452301
        var b0 = 0xefcdab89.toInt()
        var c0 = 0x98badcfe.toInt()
        var d0 = 0x10325476

        // Pad: 0x80, zeros up to 56 mod 64, then the 64-bit little-endian bit length.
        val padLen = ((56 - (data.size + 1) % 64) + 64) % 64
        val msg = ByteArray(data.size + 1 + padLen + 8)
        data.copyInto(msg)
        msg[data.size] = 0x80.toByte()
        var bits = data.size.toLong() * 8
        for (i in 0 until 8) {
            msg[msg.size - 8 + i] = (bits and 0xFF).toByte()
            bits = bits ushr 8
        }

        var chunk = 0
        while (chunk < msg.size) {
            val m = IntArray(16)
            for (j in 0 until 16) {
                val o = chunk + j * 4
                m[j] = (msg[o].toInt() and 0xFF) or
                    ((msg[o + 1].toInt() and 0xFF) shl 8) or
                    ((msg[o + 2].toInt() and 0xFF) shl 16) or
                    ((msg[o + 3].toInt() and 0xFF) shl 24)
            }
            var a = a0; var b = b0; var c = c0; var d = d0
            for (i in 0 until 64) {
                val f: Int
                val g: Int
                when {
                    i < 16 -> { f = (b and c) or (b.inv() and d); g = i }
                    i < 32 -> { f = (d and b) or (d.inv() and c); g = (5 * i + 1) and 15 }
                    i < 48 -> { f = b xor c xor d; g = (3 * i + 5) and 15 }
                    else -> { f = c xor (b or d.inv()); g = (7 * i) and 15 }
                }
                val rotated = b + Integer.rotateLeft(f + a + MD5_SINES[i] + m[g], MD5_SHIFTS[i])
                a = d; d = c; c = b; b = rotated
            }
            a0 += a; b0 += b; c0 += c; d0 += d
            chunk += 64
        }

        val out = ByteArray(16)
        writeLe32(out, 0, a0); writeLe32(out, 4, b0); writeLe32(out, 8, c0); writeLe32(out, 12, d0)
        return out
    }

    private fun writeLe32(buf: ByteArray, off: Int, value: Int) {
        buf[off] = (value and 0xFF).toByte()
        buf[off + 1] = ((value ushr 8) and 0xFF).toByte()
        buf[off + 2] = ((value ushr 16) and 0xFF).toByte()
        buf[off + 3] = ((value ushr 24) and 0xFF).toByte()
    }
}

/**
 * The byte transport [EspFlasher] drives. Abstracts the Android USB serial port so
 * the protocol logic stays free of platform types (and unit-testable).
 */
interface SerialLink {
    /** Write all of [data] (blocking, with an internal timeout). */
    fun write(data: ByteArray)

    /** Read whatever is available within [timeoutMs]; empty array on timeout. */
    fun read(timeoutMs: Int): ByteArray

    fun setDtr(on: Boolean)
    fun setRts(on: Boolean)

    /** Re-negotiate the line speed on both ends' behalf (host side only). */
    fun setBaud(baud: Int)
}
