package sh.haven.core.fido

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.os.Build
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "FidoAuthenticator"
private const val ACTION_USB_PERMISSION = "sh.haven.core.fido.USB_PERMISSION"

data class FidoAssertionResult(
    val signature: ByteArray,
    val flags: Byte,
    val counter: Int,
)

/**
 * Manages FIDO2 authenticator interactions using generic CTAP2 protocol.
 * Supports any FIDO2 security key over USB HID or NFC ISO-DEP —
 * not limited to YubiKeys.
 */
@Singleton
class FidoAuthenticator @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    /** Sealed type for connected security key transport. */
    private sealed class ConnectedDevice {
        data class Usb(val device: UsbDevice) : ConnectedDevice()
        data class Nfc(val tag: Tag) : ConnectedDevice()
    }

    private var pendingDevice: CompletableDeferred<ConnectedDevice>? = null
    private var usbReceiver: BroadcastReceiver? = null

    /** Callback for UI to show/hide "touch your security key" prompt. */
    var onTouchRequired: ((Boolean) -> Unit)? = null

    fun startUsbDiscovery(activity: Activity) {
        Log.d(TAG, "Starting USB FIDO discovery")
        val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager

        // Check already-connected devices
        for (device in usbManager.deviceList.values) {
            if (isFidoHidDevice(device)) {
                Log.d(TAG, "Found already-connected FIDO device: ${device.productName}")
                pendingDevice?.complete(ConnectedDevice.Usb(device))
                return
            }
        }

        // Register for USB attach events
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                when (intent.action) {
                    UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                        val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                        } else {
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                        }
                        if (device != null && isFidoHidDevice(device)) {
                            Log.d(TAG, "USB FIDO device attached: ${device.productName}")
                            pendingDevice?.complete(ConnectedDevice.Usb(device))
                        }
                    }
                    ACTION_USB_PERMISSION -> {
                        // Permission result — handled inline in getAssertion
                    }
                }
            }
        }
        usbReceiver = receiver
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(ACTION_USB_PERMISSION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }
    }

    fun startNfcDiscovery(activity: Activity) {
        val nfcAdapter = NfcAdapter.getDefaultAdapter(context)
        if (nfcAdapter == null) {
            Log.w(TAG, "NFC not available")
            return
        }
        Log.d(TAG, "Starting NFC FIDO discovery")
        nfcAdapter.enableReaderMode(
            activity,
            { tag ->
                if (IsoDep.get(tag) != null) {
                    Log.d(TAG, "NFC FIDO tag detected")
                    pendingDevice?.complete(ConnectedDevice.Nfc(tag))
                }
            },
            NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or
                NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK,
            null,
        )
    }

    fun stopDiscovery(activity: Activity) {
        try {
            usbReceiver?.let { context.unregisterReceiver(it) }
            usbReceiver = null
        } catch (_: Exception) {}
        try {
            NfcAdapter.getDefaultAdapter(context)?.disableReaderMode(activity)
        } catch (_: Exception) {}
    }

    /**
     * Perform a FIDO2 assertion. Blocks until a security key is connected/tapped
     * and the user touches it.
     *
     * @param rpId       The relying party ID (application string, e.g. "ssh:")
     * @param message    The SSH sign data to hash and sign
     * @param credentialId The credential ID (key_handle) from the SK key file
     * @return The assertion result with signature, flags, and counter
     */
    suspend fun getAssertion(
        rpId: String,
        message: ByteArray,
        credentialId: ByteArray,
    ): FidoAssertionResult = withContext(Dispatchers.IO) {
        Log.d(TAG, "FIDO2 assertion requested: rpId=$rpId, message=${message.size}b, credId=${credentialId.size}b")

        val clientDataHash = MessageDigest.getInstance("SHA-256").digest(message)

        onTouchRequired?.invoke(true)

        try {
            val deferred = CompletableDeferred<ConnectedDevice>()
            pendingDevice = deferred
            Log.d(TAG, "Waiting for security key (USB or NFC)...")

            val device = deferred.await()

            val result = when (device) {
                is ConnectedDevice.Usb -> performUsbAssertion(device.device, rpId, clientDataHash, credentialId)
                is ConnectedDevice.Nfc -> performNfcAssertion(device.tag, rpId, clientDataHash, credentialId)
            }

            Log.d(TAG, "FIDO2 assertion success: sig=${result.signature.size}b, flags=0x${
                "%02x".format(result.flags)
            }, counter=${result.counter}")

            result
        } finally {
            pendingDevice = null
            onTouchRequired?.invoke(false)
        }
    }

    private fun performUsbAssertion(
        device: UsbDevice,
        rpId: String,
        clientDataHash: ByteArray,
        credentialId: ByteArray,
    ): FidoAssertionResult {
        val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager

        // Request USB permission if needed
        if (!usbManager.hasPermission(device)) {
            val permDeferred = CompletableDeferred<Boolean>()
            val permReceiver = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context, intent: Intent) {
                    if (intent.action == ACTION_USB_PERMISSION) {
                        permDeferred.complete(
                            intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                        )
                    }
                }
            }
            val filter = IntentFilter(ACTION_USB_PERMISSION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(permReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                context.registerReceiver(permReceiver, filter)
            }
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                PendingIntent.FLAG_MUTABLE else 0
            usbManager.requestPermission(
                device,
                PendingIntent.getBroadcast(context, 0, Intent(ACTION_USB_PERMISSION), flags),
            )
            // Block until permission is granted (we're on Dispatchers.IO)
            val granted = kotlinx.coroutines.runBlocking { permDeferred.await() }
            context.unregisterReceiver(permReceiver)
            if (!granted) throw IOException("USB permission denied")
        }

        // Find HID interface and endpoints
        val hidInterface = (0 until device.interfaceCount)
            .map { device.getInterface(it) }
            .firstOrNull { it.interfaceClass == UsbConstants.USB_CLASS_HID }
            ?: throw IOException("No HID interface on FIDO device")

        var endpointIn: android.hardware.usb.UsbEndpoint? = null
        var endpointOut: android.hardware.usb.UsbEndpoint? = null
        for (i in 0 until hidInterface.endpointCount) {
            val ep = hidInterface.getEndpoint(i)
            if (ep.direction == UsbConstants.USB_DIR_IN) endpointIn = ep
            if (ep.direction == UsbConstants.USB_DIR_OUT) endpointOut = ep
        }
        requireNotNull(endpointIn) { "No IN endpoint on HID interface" }
        requireNotNull(endpointOut) { "No OUT endpoint on HID interface" }

        val connection = usbManager.openDevice(device)
            ?: throw IOException("Failed to open USB device")
        connection.claimInterface(hidInterface, true)

        CtapHidTransport(connection, endpointIn, endpointOut).use { transport ->
            transport.init()

            val command = Ctap2Cbor.encodeGetAssertionCommand(rpId, clientDataHash, credentialId)
            val response = transport.sendCborCommand(command) {
                onTouchRequired?.invoke(true)
            }

            return parseCtap2Response(response)
        }
    }

    private fun performNfcAssertion(
        tag: Tag,
        rpId: String,
        clientDataHash: ByteArray,
        credentialId: ByteArray,
    ): FidoAssertionResult {
        val isoDep = IsoDep.get(tag) ?: throw IOException("Tag does not support ISO-DEP")

        CtapNfcTransport(isoDep).use { transport ->
            transport.connect()
            transport.select()

            val command = Ctap2Cbor.encodeGetAssertionCommand(rpId, clientDataHash, credentialId)
            val response = transport.sendCborCommand(command)

            return parseCtap2Response(response)
        }
    }

    private fun parseCtap2Response(response: ByteArray): FidoAssertionResult {
        require(response.isNotEmpty()) { "Empty CTAP2 response" }

        val status = response[0]
        if (status != Ctap2Cbor.STATUS_OK) {
            val desc = when (status) {
                Ctap2Cbor.STATUS_NO_CREDENTIALS -> "No matching credential on this key"
                Ctap2Cbor.STATUS_ACTION_TIMEOUT -> "User did not touch the key in time"
                else -> "CTAP2 error 0x${"%02x".format(status)}"
            }
            throw IOException("FIDO2 assertion failed: $desc")
        }

        val parsed = Ctap2Cbor.decodeGetAssertionResponse(response.copyOfRange(1, response.size))

        val authData = parsed.authData
        require(authData.size >= 37) { "authenticatorData too short: ${authData.size}" }
        val flags = authData[32]
        val counter = ((authData[33].toInt() and 0xFF) shl 24) or
            ((authData[34].toInt() and 0xFF) shl 16) or
            ((authData[35].toInt() and 0xFF) shl 8) or
            (authData[36].toInt() and 0xFF)

        return FidoAssertionResult(
            signature = parsed.signature,
            flags = flags,
            counter = counter,
        )
    }

    /** Check if a USB device is a FIDO HID device (interface class 0x03). */
    private fun isFidoHidDevice(device: UsbDevice): Boolean {
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            // FIDO keys use HID class (0x03) with no subclass/protocol
            if (iface.interfaceClass == UsbConstants.USB_CLASS_HID) {
                return true
            }
        }
        return false
    }
}
