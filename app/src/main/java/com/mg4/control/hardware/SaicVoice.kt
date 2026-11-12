package com.mg4.control.hardware

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import com.mg4.control.debug.AppLogger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * La voix de la voiture (2026-11-12) : le moteur Nuance de SAIC, `com.saicmotor.voicetts/.TtsService`.
 *
 * La voiture n'a aucun moteur de synthèse Android standard (aucun service `TTS_SERVICE` dans les
 * extractions SWI133, SWI69, SWI131 ; sur SWI132, seule la carte turque en déclare un) : un
 * `TextToSpeech` n'y dit rien. Le service SAIC est exporté, sans permission, présent sur SWI133 et
 * SWI132 (absent des extractions SWI69 / SWI131). Interface `com.saicmotor.voicetts.ITtsService` :
 *  - 1 `promptCommonWords(content, shouldAudioFocus, sourceId)` — lu dans la langue de la voiture ;
 *  - 3 `stopPrompt()`.
 * Le service garde une file de 4 phrases au plus ; au-delà, il ignore la nouvelle.
 */
object SaicVoice {

    private const val TAG = "MG4_TTS"
    private const val PAQUET = "com.saicmotor.voicetts"
    private const val DESCRIPTEUR = "com.saicmotor.voicetts.ITtsService"
    private const val TX_DIRE = 1
    private const val LIAISON_MS = 2000L
    private val SERVICE = ComponentName(PAQUET, "$PAQUET.TtsService")

    @Volatile private var binder: IBinder? = null
    @Volatile private var attente: CountDownLatch? = null

    private val connexion = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            binder = service
            attente?.countDown()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            binder = null
        }
    }

    /** La voiture a-t-elle sa voix ? */
    fun available(ctx: Context): Boolean = runCatching {
        ctx.packageManager.getServiceInfo(SERVICE, 0)
        true
    }.getOrDefault(false)

    /**
     * Fait lire [texte] par la voix de la voiture, le média baissé pendant la phrase. Hors du fil
     * principal (la première liaison attend le service). Faux si le service est absent ou refuse.
     */
    fun speak(ctx: Context, texte: String): Boolean {
        check(Looper.myLooper() != Looper.getMainLooper()) { "SaicVoice.speak sur le fil principal" }
        val b = lier(ctx) ?: return false
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(DESCRIPTEUR)
            data.writeString(texte)
            data.writeInt(1)                 // shouldAudioFocus : le média baisse pendant la phrase
            data.writeString(ctx.packageName) // sourceId
            b.transact(TX_DIRE, data, reply, 0)
            reply.readException()
            AppLogger.i(TAG, "voix de la voiture : phrase envoyée (${texte.length} caractères)")
            true
        } catch (e: Exception) {
            AppLogger.w(TAG, "voix de la voiture : refus ${e.javaClass.simpleName} ${e.message}")
            false
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    @Synchronized
    private fun lier(ctx: Context): IBinder? {
        binder?.takeIf { it.isBinderAlive }?.let { return it }
        val latch = CountDownLatch(1)
        attente = latch
        val ok = runCatching {
            ctx.applicationContext.bindService(Intent().setComponent(SERVICE), connexion, Context.BIND_AUTO_CREATE)
        }.onFailure { AppLogger.w(TAG, "liaison à la voix refusée : ${it.javaClass.simpleName} ${it.message}") }
            .getOrDefault(false)
        if (!ok) {
            AppLogger.w(TAG, "voix de la voiture ${SERVICE.flattenToShortString()} introuvable")
            return null
        }
        if (!latch.await(LIAISON_MS, TimeUnit.MILLISECONDS)) AppLogger.w(TAG, "voix de la voiture : pas de réponse en $LIAISON_MS ms")
        return binder
    }
}
