package com.mg4.control.util

import android.content.Context
import com.mg4.hardware.AppLogger

/**
 * Lit la version firmware du système d'infodivertissement.
 * Propriété système : ro.build.mt2712.version → "SWI133-29176-1300R32"
 */
object FirmwareHelper {

    private const val TAG         = "MG4_FW"
    private const val PROP_KEY    = "ro.build.mt2712.version"
    private const val PROP_FALLBACK = "ro.build.version.incremental"

    @Volatile private var cachedVersion: String? = null

    fun getMpuVersion(context: Context, onResult: (String?) -> Unit) {
        if (cachedVersion != null) { onResult(cachedVersion); return }

        val version = readSystemProperty(PROP_KEY)
            ?: readSystemProperty(PROP_FALLBACK)

        if (version != null) {
            AppLogger.i(TAG, "$PROP_KEY = $version")
            cachedVersion = version
        } else {
            AppLogger.w(TAG, "Firmware indisponible ($PROP_KEY introuvable)")
        }

        onResult(version)
    }

    private fun readSystemProperty(key: String): String? {
        return try {
            val sp  = Class.forName("android.os.SystemProperties")
            val get = sp.getMethod("get", String::class.java, String::class.java)
            (get.invoke(null, key, "") as? String)?.takeIf { it.isNotBlank() && it != "0" }
        } catch (e: Exception) {
            AppLogger.d(TAG, "SystemProperties[$key] exc: ${e.message}")
            null
        }
    }

    fun invalidateCache() { cachedVersion = null }
}
