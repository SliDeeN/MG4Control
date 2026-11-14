package com.mg4.control.hardware

import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import com.mg4.control.debug.AppLogger
import com.mg4.control.model.UsbSeen

/**
 * Le côté Android de la relance (issue #251) : lister l'USB, redire « appareil branché »,
 * réinitialiser le port. Même méthode que l'appli AA Kick (testée SWI69 pour Android Auto) ;
 * notre uid système a le droit d'envoyer `USB_DEVICE_ATTACHED` (diffusion protégée) et d'ouvrir
 * n'importe quel appareil sans demande.
 */
object UsbPhoneKick {

    private const val TAG = "MG4_PROJ"

    fun devices(ctx: Context): List<Pair<UsbSeen, UsbDevice>> = runCatching {
        val um = ctx.getSystemService(Context.USB_SERVICE) as UsbManager
        um.deviceList.values.map { d ->
            UsbSeen(d.vendorId, d.productId, d.deviceName,
                (0 until d.interfaceCount).map { d.getInterface(it).interfaceClass }) to d
        }
    }.onFailure { AppLogger.w(TAG, "USB illisible : ${it.javaClass.simpleName} ${it.message}") }
        .getOrDefault(emptyList())

    /** Ce qu'Android envoie au branchement ; AllGo y répond comme à un câble qu'on vient de mettre. */
    fun announceAttached(ctx: Context, d: UsbDevice): Boolean = runCatching {
        ctx.sendBroadcast(Intent(UsbManager.ACTION_USB_DEVICE_ATTACHED).putExtra(UsbManager.EXTRA_DEVICE, d))
    }.onFailure { AppLogger.w(TAG, "avis de branchement refusé : ${it.javaClass.simpleName} ${it.message}") }
        .isSuccess

    /** `UsbDeviceConnection.resetDevice()` (caché) : le téléphone se réénumère, comme débranché. */
    fun reset(ctx: Context, d: UsbDevice): Boolean {
        val um = ctx.getSystemService(Context.USB_SERVICE) as? UsbManager ?: return false
        val c = runCatching { um.openDevice(d) }.getOrNull()
        if (c == null) {
            AppLogger.w(TAG, "réinitialisation USB : appareil non ouvert")
            return false
        }
        return try {
            (UsbDeviceConnection::class.java.getMethod("resetDevice").invoke(c) as? Boolean) ?: true
        } catch (e: Exception) {
            AppLogger.w(TAG, "réinitialisation USB refusée : ${e.javaClass.simpleName} ${e.message}")
            false
        } finally {
            runCatching { c.close() }
        }
    }
}
