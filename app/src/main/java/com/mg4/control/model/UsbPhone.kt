package com.mg4.control.model

/**
 * Téléphone branché au câble, vu par Android (issue #251) : la règle, sans Android.
 *
 * Le service AllGo `com.allgo.rui` (même code sur SWI69/131/132/133/173) ne regarde les appareils
 * déjà branchés qu'**une fois**, à son démarrage ; ensuite il n'écoute plus que
 * `USB_DEVICE_ATTACHED`. Un téléphone branché avant l'écran peut donc être raté. Ses critères,
 * repris ici : iPhone = Apple 0x05ac:0x12a8 → CarPlay ; port interne (bus « 001 ») ignoré ; tout
 * le reste → bascule Android Auto. Hubs, clés USB et root hubs écartés comme le fait l'appli
 * AA Kick (Skittle6938/MG4-AndroidAuto-BootFix, testée SWI69).
 */
data class UsbSeen(
    val vendorId: Int,
    val productId: Int,
    /** `/dev/bus/usb/BBB/DDD`. */
    val name: String?,
    val interfaceClasses: List<Int>,
)

enum class UsbPhoneKind(val label: String) { IPHONE("iPhone"), ANDROID("Android") }

object UsbPhoneRule {
    const val VID_APPLE = 0x05ac
    /** Le seul identifiant d'iPhone qu'AllGo reconnaît. */
    const val PID_IPHONE = 0x12a8
    const val VID_GOOGLE = 0x18d1
    /** Mode accessoire Android (AOAP) : le téléphone est déjà basculé pour Android Auto. */
    val PID_AOAP = 0x2d00..0x2d05
    const val VID_ROOT_HUB = 0x1d6b
    const val CLASS_MASS_STORAGE = 8
    const val CLASS_HUB = 9

    /** Port « gris » interne (modem, MCU…) : AllGo lit les caractères 13 à 15 du nom. */
    fun isGrayPort(name: String?): Boolean =
        name != null && name.length >= 16 && name.substring(13, 16) == "001"

    /** Un Pixel non basculé est aussi en 0x18d1 : seul le PID dit l'AOAP. */
    fun isAoap(d: UsbSeen): Boolean = d.vendorId == VID_GOOGLE && d.productId in PID_AOAP

    fun kind(d: UsbSeen): UsbPhoneKind? = when {
        isGrayPort(d.name) -> null
        d.vendorId == VID_APPLE -> if (d.productId == PID_IPHONE) UsbPhoneKind.IPHONE else null
        d.vendorId == VID_ROOT_HUB || isAoap(d) -> null
        d.interfaceClasses.any { it == CLASS_HUB || it == CLASS_MASS_STORAGE } -> null
        else -> UsbPhoneKind.ANDROID
    }

    /** Le téléphone à relancer ; Android d'abord s'il y a les deux, comme AllGo. */
    fun pick(devices: List<UsbSeen>): UsbSeen? =
        devices.firstOrNull { kind(it) == UsbPhoneKind.ANDROID } ?: devices.firstOrNull { kind(it) == UsbPhoneKind.IPHONE }

    fun anyAoap(devices: List<UsbSeen>): Boolean = devices.any { isAoap(it) && !isGrayPort(it.name) }
}

/** Quand relancer (issue #251). */
object ProjectionKickRule {

    /**
     * Le service démarre-t-il avec la voiture ? Démarré longtemps après Android (mise à jour de
     * l'appli, service relancé), ce n'est pas un allumage : on ne relance rien.
     */
    fun recentBoot(elapsedMs: Long, limitMs: Long): Boolean = elapsedMs <= limitMs

    /**
     * Vraie veille entre deux allumages de l'écran : `elapsedRealtime` avance en veille profonde,
     * `uptimeMillis` non. [gapBefore] / [gapNow] = leur écart au dernier relevé et maintenant.
     * Écran éteint sans veille : AllGo est resté éveillé et a vu tout branchement.
     */
    fun slept(gapBefore: Long, gapNow: Long, minMs: Long): Boolean = gapNow - gapBefore >= minMs
}
