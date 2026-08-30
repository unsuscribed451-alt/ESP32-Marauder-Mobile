package com.marauder.mobile.protocol

/**
 * One decoded binary capture frame streamed by firmware ≥ proto 2 (see the frame
 * layout in Buffer.cpp `saveSerial`). [seq] is the per-capture-session frame
 * counter (used to detect gaps), [type] is the stream kind, and [payload] is the
 * raw bytes to append to the on-phone capture file — for a pcap stream the first
 * frame's payload begins with the pcap global header.
 */
class CaptureFrame(val seq: Long, val type: Int, val payload: ByteArray) {

    companion object {
        const val TYPE_PCAP = 0
        const val TYPE_LOG = 1
        const val TYPE_GPX = 2

        /** File extension for a stream [type] (matches the on-device SD naming). */
        fun extension(type: Int): String = when (type) {
            TYPE_PCAP -> "pcap"
            TYPE_GPX -> "gpx"
            else -> "log"
        }
    }
}
