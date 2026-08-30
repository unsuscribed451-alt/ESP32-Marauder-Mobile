package com.marauder.mobile.data

import com.marauder.mobile.protocol.DeviceMessage
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** A location fix from the phone's own GNSS — the on-phone replacement for the
 *  ESP32 GPS module used when wardriving. */
data class PhoneLocation(
    val lat: Double,
    val lon: Double,
    val altMeters: Double,
    val accuracyMeters: Double,
    val timeMillis: Long,
)

/**
 * Builds WigleWiFi-1.4 CSV wardrive logs on the phone, tagging each access point
 * the ESP32 reports (`jsonlist a`) with the phone's current GNSS fix — so
 * wardriving needs no GPS module wired to the board. The header and column order
 * match WiGLE's importer and the firmware's on-device format (see
 * `WiFiScan::header_line`), so the resulting file uploads to WiGLE unchanged.
 */
object WardriveAssembler {

    /** CSV preamble + column header. [appRelease] identifies the producing app. */
    fun header(appRelease: String): String =
        "WigleWifi-1.4,appRelease=$appRelease,model=ESP32 Marauder,release=$appRelease," +
            "device=Marauder Mobile,display=phone,board=ESP32 Marauder,brand=JustCallMeKoko\n" +
            "MAC,SSID,AuthMode,FirstSeen,Channel,RSSI,CurrentLatitude,CurrentLongitude," +
            "AltitudeMeters,AccuracyMeters,Type\n"

    /** One WigleWiFi data row for [ap] observed at [loc] (a null fix logs zeroes). */
    fun line(ap: DeviceMessage.Ap, loc: PhoneLocation?): String {
        val lat = loc?.lat ?: 0.0
        val lon = loc?.lon ?: 0.0
        val alt = loc?.altMeters ?: 0.0
        val acc = loc?.accuracyMeters ?: 0.0
        val seen = firstSeen(loc?.timeMillis ?: System.currentTimeMillis())
        val ssid = ap.essid.replace(",", " ") // keep the CSV single-column
        return "${ap.bssid},$ssid,${authMode(ap.sec)},$seen,${ap.channel},${ap.rssi}," +
            "$lat,$lon,$alt,$acc,WIFI\n"
    }

    private val fmt: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault())

    private fun firstSeen(millis: Long): String = fmt.format(Instant.ofEpochMilli(millis))

    /** Map the firmware's `sec` (ESP `wifi_auth_mode_t`) to a WigleWiFi auth tag. */
    private fun authMode(sec: Int): String = when (sec) {
        0 -> "[OPEN]"
        1 -> "[WEP]"
        2 -> "[WPA-PSK]"
        3 -> "[WPA2-PSK]"
        4 -> "[WPA-WPA2-PSK]"
        5 -> "[WPA2-EAP]"
        6 -> "[WPA3-PSK]"
        7 -> "[WPA2-WPA3-PSK]"
        8 -> "[WAPI-PSK]"
        else -> "[UNKNOWN]"
    }
}
