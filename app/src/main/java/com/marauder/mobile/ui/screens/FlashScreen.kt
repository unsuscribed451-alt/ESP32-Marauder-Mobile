package com.marauder.mobile.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeveloperBoard
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.marauder.mobile.esp.Firmware
import com.marauder.mobile.esp.FlashProfile
import com.marauder.mobile.esp.FlashStage
import com.marauder.mobile.esp.FlashUiState
import com.marauder.mobile.ui.components.EmptyState
import com.marauder.mobile.ui.theme.CatDevice
import com.marauder.mobile.ui.theme.Danger
import com.marauder.mobile.ui.theme.MarauderTextDim
import com.marauder.mobile.ui.theme.Success
import com.marauder.mobile.ui.theme.Warning
import com.marauder.mobile.usb.UsbSerialManager
import com.marauder.mobile.vm.MarauderViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FlashScreen(vm: MarauderViewModel, onBack: () -> Unit) {
    val flash by vm.flash.collectAsState()
    var devices by remember { mutableStateOf(vm.availableFlashDevices()) }
    var confirmTarget by remember { mutableStateOf<UsbSerialManager.DeviceOption?>(null) }
    var selected by remember { mutableStateOf(Firmware.DEFAULT) }
    var showPicker by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Flash & Update") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                actions = {
                    if (!flash.inProgress && !flash.finished) {
                        IconButton(onClick = { devices = vm.availableFlashDevices() }) {
                            Icon(Icons.Filled.Refresh, contentDescription = "Refresh", tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                ),
            )
        },
    ) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            FirmwareSourceCard()

            when {
                flash.inProgress -> ProgressCard(flash)
                flash.finished -> ResultCard(
                    state = flash,
                    onDone = {
                        vm.resetFlashState()
                        if (flash.stage == FlashStage.DONE) onBack()
                    },
                    onRetry = {
                        vm.resetFlashState()
                        devices = vm.availableFlashDevices()
                    },
                )
                else -> {
                    BoardSelectorCard(selected = selected, onClick = { showPicker = true })
                    SelectionNote()
                    BootModeNote()
                    DevicePicker(
                        devices = devices,
                        selected = selected,
                        onFlash = { confirmTarget = it },
                    )
                }
            }

            if (flash.log.isNotEmpty()) FlashLog(flash.log)
        }
    }

    confirmTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { confirmTarget = null },
            icon = { Icon(Icons.Filled.WarningAmber, contentDescription = null, tint = Warning) },
            title = { Text("Flash firmware?") },
            text = {
                Text(
                    "This erases the current firmware on \"${target.title}\" and writes " +
                        "${selected.label} (${Firmware.TAG}). Keep the cable connected " +
                        "until it finishes.",
                )
            },
            confirmButton = {
                Button(onClick = {
                    confirmTarget = null
                    vm.startFlash(target, selected)
                }) { Text("Flash") }
            },
            dismissButton = {
                TextButton(onClick = { confirmTarget = null }) { Text("Cancel") }
            },
        )
    }

    if (showPicker) {
        BoardPickerDialog(
            selected = selected,
            onPick = { selected = it; showPicker = false },
            onDismiss = { showPicker = false },
        )
    }
}

@Composable
private fun FirmwareSourceCard() {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.SystemUpdate, contentDescription = null, tint = CatDevice, modifier = Modifier.size(30.dp))
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text("ESP32 Marauder firmware", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
                Text("Source: 0xsys fork · ${Firmware.TAG}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(Firmware.RELEASE_PAGE, style = MaterialTheme.typography.labelSmall, color = MarauderTextDim)
            }
        }
    }
}

/** Shows the chosen board and opens the picker. */
@Composable
private fun BoardSelectorCard(selected: FlashProfile, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.DeveloperBoard, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(30.dp))
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text("Board", style = MaterialTheme.typography.labelMedium, color = MarauderTextDim)
                Text("${selected.label}  ·  ${selected.chip.label}", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
                Text(selected.hardware, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("Change", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
    }
}

/** Guidance for picking the right binary for common hardware. */
@Composable
private fun SelectionNote() {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Which one do I pick?", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
            Text(
                "• Plain ESP32 (WROOM-32, NodeMCU, Wemos, generic dev board): choose \"Generic ESP32 (WROOM)\".\n" +
                    "• A named Marauder board (v4/v6/v7, Kit, Mini, CYD, M5StickC…): choose that exact board.\n" +
                    "• ESP32-S3 (generic dev board): choose \"Generic ESP32-S3\"; for a Cardputer pick the Cardputer entry.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "In-app flashing works for classic ESP32 only. For ESP32-S2/S3/C5/C6 boards, pick the " +
                    "binary here for reference and flash it with the web installer or esptool (their USB-JTAG " +
                    "bootloader needs a different reset).",
                style = MaterialTheme.typography.labelSmall,
                color = MarauderTextDim,
            )
        }
    }
}

/** Full-catalog board picker: classic ESP32 boards first (flashable in-app), then
 *  the other chip families (reference / web-installer). */
@Composable
private fun BoardPickerDialog(
    selected: FlashProfile,
    onPick: (FlashProfile) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Select your board") },
        text = {
            Column(
                Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                PickerSection("Classic ESP32 · flash in-app")
                Firmware.PROFILES.filter { it.flashableInApp }.forEach {
                    BoardRow(it, it.id == selected.id, onPick)
                }
                PickerSection("Other chips · use the web installer")
                Firmware.PROFILES.filterNot { it.flashableInApp }.forEach {
                    BoardRow(it, it.id == selected.id, onPick)
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun PickerSection(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 10.dp, bottom = 2.dp),
    )
}

@Composable
private fun BoardRow(profile: FlashProfile, isSelected: Boolean, onPick: (FlashProfile) -> Unit) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f) else MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth().clickable { onPick(profile) },
    ) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("${profile.label}  ·  ${profile.chip.label}", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
                Text(profile.hardware, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (isSelected) Icon(Icons.Filled.CheckCircle, contentDescription = "Selected", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun BootModeNote() {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = Warning.copy(alpha = 0.12f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(14.dp)) {
            Icon(Icons.Filled.WarningAmber, contentDescription = null, tint = Warning, modifier = Modifier.size(22.dp))
            Column(Modifier.padding(start = 12.dp)) {
                Text("If flashing fails", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
                Text(
                    "Put the ESP32 into boot (download) mode by hand, then flash again: " +
                        "hold the BOOT / IO0 button, briefly tap EN / RST, then release BOOT. " +
                        "The board is now waiting for firmware.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun DevicePicker(
    devices: List<UsbSerialManager.DeviceOption>,
    selected: FlashProfile,
    onFlash: (UsbSerialManager.DeviceOption) -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Select a device to flash", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)

            if (!selected.flashableInApp) {
                Text(
                    "\"${selected.label}\" is an ${selected.chip.label} board — in-app flashing isn't " +
                        "available for it yet. Flash the downloaded binary with the web installer or esptool.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Warning,
                )
            }

            if (devices.isEmpty()) {
                EmptyState(
                    icon = Icons.Filled.Usb,
                    title = "No adapter found",
                    subtitle = "Plug the ESP32 into the USB-OTG port, then tap refresh.",
                )
            } else {
                devices.forEach { device ->
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.DeveloperBoard, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(26.dp))
                            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                                Text(device.title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
                                Text(device.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Button(onClick = { onFlash(device) }, enabled = selected.flashableInApp) { Text("Flash") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProgressCard(state: FlashUiState) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stageLabel(state.stage), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            // Determinate while downloading/writing; indeterminate for the short setup phases.
            val determinate = state.stage == FlashStage.DOWNLOAD || state.stage == FlashStage.WRITE
            if (determinate) {
                LinearProgressIndicator(progress = { state.fraction.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            Text(state.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
                Text("Keep the cable connected…", style = MaterialTheme.typography.labelMedium, color = MarauderTextDim)
            }
        }
    }
}

@Composable
private fun ResultCard(state: FlashUiState, onDone: () -> Unit, onRetry: () -> Unit) {
    val ok = state.stage == FlashStage.DONE
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = (if (ok) Success else Danger).copy(alpha = 0.10f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (ok) Icons.Filled.CheckCircle else Icons.Filled.ErrorOutline,
                    contentDescription = null,
                    tint = if (ok) Success else Danger,
                    modifier = Modifier.size(28.dp),
                )
                Text(
                    if (ok) "Firmware flashed" else "Flash failed",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = 10.dp),
                )
            }
            Text(
                if (ok) "The device is rebooting into the new firmware. Head back and reconnect."
                else state.message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (ok) {
                Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text("Done") }
            } else {
                OutlinedButton(onClick = onRetry, modifier = Modifier.fillMaxWidth()) { Text("Try again") }
            }
        }
    }
}

@Composable
private fun FlashLog(log: List<String>) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier
                .padding(12.dp)
                .heightIn(max = 180.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            log.forEach { line ->
                Text(line, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private fun stageLabel(stage: FlashStage): String = when (stage) {
    FlashStage.DOWNLOAD -> "Downloading firmware"
    FlashStage.CONNECT -> "Connecting to bootloader"
    FlashStage.ERASE -> "Erasing flash"
    FlashStage.WRITE -> "Writing firmware"
    FlashStage.VERIFY -> "Verifying"
    FlashStage.DONE -> "Done"
    FlashStage.ERROR -> "Failed"
    FlashStage.IDLE -> ""
}
