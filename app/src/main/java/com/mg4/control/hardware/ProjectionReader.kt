package com.mg4.control.hardware

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Parcel
import com.mg4.control.debug.AppLogger
import com.mg4.control.model.ProjectionKind
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Téléphone en projection (Android Auto / CarPlay), en **lecture seule** (2026-10-23).
 *
 * Même voie que les lanceurs d'origine, sur les 6 firmwares : le service AllGo
 * `com.allgo.rui/.RemoteUIService` (exporté, sans permission, uid système comme nous), interface
 * `com.allgo.rui.IRemoteUIService`, `getActiveRemoteDevice()` = transaction 15. AllGo pose
 * l'appareil au début de la session et l'efface à sa fin ou à son échec : `null` = aucune session.
 * Sur SWI133, le lanceur passe par une enveloppe SAIC (`com.saicmotor.projection`) qui relaie la
 * même question au même service — inutile pour nous.
 *
 * On ne s'abonne à rien (`register`) : une question à l'appui suffit, et le lanceur garde seul ses
 * rappels. La liaison est gardée une fois faite, comme celle du lanceur.
 */
object ProjectionReader {

    private const val TAG = "MG4_PROJ"
    private const val DESCRIPTEUR = "com.allgo.rui.IRemoteUIService"
    private const val TX_APPAREIL_ACTIF = 15
    /** Première liaison : le service tourne déjà, elle répond en quelques millisecondes. */
    private const val LIAISON_MS = 2000L
    private val SERVICE = ComponentName("com.allgo.rui", "com.allgo.rui.RemoteUIService")

    /**
     * Appareil en session, d'après `com.allgo.rui.RemoteDevice` : statut de session, nom, UUID,
     * adresse BT, type, transport, connu, activé — dans cet ordre (SWI69 comme SWI133). Le nom du
     * téléphone est lu mais pas gardé : il n'a rien à faire dans le journal.
     */
    data class Appareil(val type: Int, val transport: Int, val session: Int) {
        val kind: ProjectionKind? get() = ProjectionKind.fromType(type)
    }

    sealed class Lecture {
        /** AllGo a répondu ; [appareil] `null` = aucune session. */
        data class Lue(val appareil: Appareil?) : Lecture()
        /** Service introuvable ou muet : on ne sait pas. */
        object Injoignable : Lecture()
    }

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

    /** Hors du fil principal : liaison au premier appel, puis un appel binder. */
    fun appareilActif(ctx: Context): Lecture {
        val b = lier(ctx) ?: return Lecture.Injoignable
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(DESCRIPTEUR)
            b.transact(TX_APPAREIL_ACTIF, data, reply, 0)
            reply.readException()
            if (reply.readInt() == 0) return Lecture.Lue(null)
            val session = reply.readInt()
            reply.readString()   // nom
            reply.readString()   // UUID
            reply.readString()   // adresse BT
            val type = reply.readInt()
            val transport = reply.readInt()
            Lecture.Lue(Appareil(type, transport, session))
        } catch (e: Exception) {
            AppLogger.w(TAG, "appareil actif illisible : ${e.javaClass.simpleName} ${e.message}")
            Lecture.Injoignable
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
        }.onFailure { AppLogger.w(TAG, "liaison à AllGo refusée : ${it.javaClass.simpleName} ${it.message}") }
            .getOrDefault(false)
        if (!ok) {
            AppLogger.w(TAG, "service AllGo ${SERVICE.flattenToShortString()} introuvable")
            return null
        }
        if (!latch.await(LIAISON_MS, TimeUnit.MILLISECONDS)) {
            AppLogger.w(TAG, "service AllGo : pas de réponse en $LIAISON_MS ms")
        }
        return binder
    }
}
