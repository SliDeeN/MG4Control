package com.mg4.control.hardware

import android.content.Context
import android.os.PowerManager
import com.mg4.control.debug.AppLogger

/**
 * Verrou de réveil des séquences de vitres.
 *
 * Une fermeture n'est pas un ordre unique : c'est une attente (le délai réglable, jusqu'à 30 s)
 * puis, pour chaque vitre sans capteur, une commande répétée toutes les 120 ms pendant toute la
 * course. Une dizaine de secondes d'activité, au moment précis où la voiture s'éteint et où le
 * boîtier cherche à s'endormir. Si le processeur suspend au milieu, les répétitions cessent, le
 * moteur s'arrête avec elles et la vitre reste à moitié fermée — sans trace pour l'expliquer.
 *
 * Le service de premier plan ne protège pas de ça : il empêche de TUER le processus, pas de le
 * SUSPENDRE. D'où ce verrou partiel, repris de winclose, qui ne touche pas à l'écran.
 *
 * Chaque instance ne tient qu'un verrou à la fois et pose toujours une expiration : un verrou
 * oublié garderait le processeur éveillé, ce qui serait pire que le défaut qu'il corrige.
 */
internal class WindowWakeLock(private val tag: String) {

    private var lock: PowerManager.WakeLock? = null

    /** Prend le verrou pour [timeoutMs] au plus. Reprendre un verrou déjà tenu le prolonge. */
    @Synchronized
    fun acquire(timeoutMs: Long) {
        release()
        val context = MG4Hardware.appContext() ?: return
        runCatching {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, tag).apply {
                setReferenceCounted(false)
                acquire(timeoutMs)
                lock = this
            }
        }.onFailure {
            AppLogger.w(PowerWindows.TAG, "$tag : verrou de réveil impossible (${it.javaClass.simpleName}: ${it.message})")
        }
    }

    /** Rend le verrou s'il est encore tenu. Sans effet sinon — l'expiration a pu passer avant. */
    @Synchronized
    fun release() {
        val held = lock ?: return
        lock = null
        runCatching { if (held.isHeld) held.release() }
    }
}
