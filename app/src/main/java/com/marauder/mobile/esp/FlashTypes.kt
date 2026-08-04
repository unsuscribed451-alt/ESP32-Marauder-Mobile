package com.marauder.mobile.esp

/** One region to write: an image [bytes]-blob at flash [offset]. */
data class FlashPart(val offset: Int, val assetName: String)

/** A flashable firmware target: the ordered regions that make up one full image. */
data class FlashProfile(val id: String, val label: String, val parts: List<FlashPart>)

/** Where the firmware comes from and which profile the app flashes by default. */
object Firmware {
    const val REPO = "0xsys/ESP32Marauder"
    const val TAG = "v1.12.3-jsonserial"
    const val RELEASE_PAGE = "https://github.com/0xsys/ESP32Marauder/releases/tag/v1.12.3-jsonserial"

    /**
     * Default target: the single **merged** image flashed at 0x0. It already contains
     * the bootloader (0x1000), partition table (0x8000), boot_app0 (0xE000) and the
     * app (0x10000), so one write + one MD5 check flashes the whole device.
     */
    val MARAUDER_V4 = FlashProfile(
        id = "marauder_v4",
        label = "Marauder v4 (ESP32)",
        parts = listOf(
            FlashPart(offset = 0x0, assetName = "ESP32Marauder-$TAG-marauder_v4.merged.bin"),
        ),
    )

    val PROFILES: List<FlashProfile> = listOf(MARAUDER_V4)
}

enum class FlashStage { IDLE, DOWNLOAD, CONNECT, ERASE, WRITE, VERIFY, DONE, ERROR }

/** A single progress update emitted by [EspFlasher] / the download step. */
data class FlashProgress(val stage: FlashStage, val fraction: Float, val message: String)

/** UI-facing flashing state held by the view-model. */
data class FlashUiState(
    val stage: FlashStage = FlashStage.IDLE,
    val fraction: Float = 0f,
    val message: String = "",
    val log: List<String> = emptyList(),
) {
    val inProgress: Boolean
        get() = stage == FlashStage.DOWNLOAD || stage == FlashStage.CONNECT ||
            stage == FlashStage.ERASE || stage == FlashStage.WRITE || stage == FlashStage.VERIFY

    val finished: Boolean get() = stage == FlashStage.DONE || stage == FlashStage.ERROR
}
