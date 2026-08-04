package com.marauder.mobile.esp

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import kotlinx.coroutines.CompletableDeferred

/**
 * [SerialLink] backed by an Android USB-serial port opened directly for flashing.
 *
 * Deliberately independent of the app's [com.marauder.mobile.usb.UsbSerialManager]:
 * flashing needs raw, synchronous byte access (the ROM protocol is binary/SLIP, not
 * line-oriented), so it does not use that manager's background line reader. Only one
 * thing may hold the port open at a time, so the caller must disconnect the normal
 * serial session before opening this.
 */
class UsbFlashLink(context: Context, private val driver: UsbSerialDriver) : SerialLink {

    private val appContext = context.applicationContext
    private val usbManager = appContext.getSystemService(Context.USB_SERVICE) as UsbManager
    private var port: UsbSerialPort? = null
    private var baud = EspFlashDefaults.INITIAL_BAUD

    /** Request permission if needed, then open the port at 115200 8N1. */
    suspend fun open() {
        if (!usbManager.hasPermission(driver.device)) requestPermission()
        val connection = usbManager.openDevice(driver.device)
            ?: throw FlashException("Couldn't open the USB device (permission denied?)")
        val p = driver.ports.firstOrNull() ?: throw FlashException("No serial port on the USB device")
        p.open(connection)
        p.setParameters(baud, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
        port = p
    }

    private suspend fun requestPermission() {
        val granted = CompletableDeferred<Boolean>()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.action == ACTION_FLASH_PERMISSION) {
                    granted.complete(intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false))
                }
            }
        }
        ContextCompat.registerReceiver(
            appContext,
            receiver,
            IntentFilter(ACTION_FLASH_PERMISSION),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        try {
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_IMMUTABLE else 0
            val intent = Intent(ACTION_FLASH_PERMISSION).setPackage(appContext.packageName)
            val pi = PendingIntent.getBroadcast(appContext, 0, intent, flags)
            usbManager.requestPermission(driver.device, pi)
            if (!granted.await()) throw FlashException("USB permission denied")
        } finally {
            runCatching { appContext.unregisterReceiver(receiver) }
        }
    }

    override fun write(data: ByteArray) {
        val p = port ?: throw FlashException("Port not open")
        p.write(data, WRITE_TIMEOUT_MS)
    }

    override fun read(timeoutMs: Int): ByteArray {
        val p = port ?: return ByteArray(0)
        val buf = ByteArray(READ_BUFFER)
        val n = try {
            p.read(buf, timeoutMs)
        } catch (e: Exception) {
            return ByteArray(0)
        }
        return if (n > 0) buf.copyOf(n) else ByteArray(0)
    }

    override fun setDtr(on: Boolean) { runCatching { port?.dtr = on } }
    override fun setRts(on: Boolean) { runCatching { port?.rts = on } }

    override fun setBaud(baud: Int) {
        this.baud = baud
        runCatching { port?.setParameters(baud, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE) }
    }

    fun close() {
        runCatching { port?.close() }
        port = null
    }

    private companion object {
        const val ACTION_FLASH_PERMISSION = "com.marauder.mobile.FLASH_USB_PERMISSION"
        const val WRITE_TIMEOUT_MS = 3000
        const val READ_BUFFER = 4096
    }
}

object EspFlashDefaults {
    const val INITIAL_BAUD = 115200
}
