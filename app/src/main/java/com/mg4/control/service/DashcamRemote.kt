package com.mg4.control.service

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.mg4.control.debug.AppLogger
import com.mg4.control.model.DashcamContract
import com.mg4.control.model.DashcamRecState
import com.mg4.control.model.RecClock
import com.mg4.control.util.DashcamApp
import com.mg4.control.util.PreClimateSettings

/**
 * Enregistrement continu de l'appli caméra pendant la surveillance (issue #180, 2026-11-08).
 *
 * START part dès « Démarrer », STOP à toutes les fins (durée, déverrouillage, batterie, voiture
 * qui roule, Arrêter, Annuler). L'état renvoyé par l'appli (`RECORDING_STATE`) ne sert qu'à
 * l'AFFICHAGE (« REC » de l'écran rouge) : c'est une diffusion ouverte, n'importe quelle appli
 * pourrait en envoyer une fausse — elle ne décide jamais d'un arrêt ni d'une extinction.
 * Un échec (pas de clé, caméra occupée) ne change rien à la surveillance et n'affiche rien
 * (sa décision) : le journal `MG4_DASHCAM` le garde.
 *
 * Tout se passe sur le fil principal (inscriptions et rappels).
 */
object DashcamRemote {

    const val TAG = "MG4_DASHCAM"

    /** Après STOP, le temps d'entendre le « stopped » final pour le journal. */
    private const val ECOUTE_APRES_STOP_MS = 20_000L
    /** L'appli répond au test de clé en 8 s au plus. */
    private const val DELAI_TEST_USB_MS = 10_000L

    private val principal = Handler(Looper.getMainLooper())

    private var appContext: Context? = null
    private var commandes: DashcamContract.Commands? = null
    private var requete: String? = null
    private var recepteurEtat: BroadcastReceiver? = null
    private var desinscription: Runnable? = null
    /** Une séance pilotée est en cours (START envoyé, STOP pas encore). */
    private var actif = false
    private val horloge = RecClock()

    /** Prévenu (fil principal) à chaque état reçu : l'écran de surveillance se repeint. */
    var ecouteur: (() -> Unit)? = null

    /** Ce que l'écran affiche : « REC 00:12:34 », ou en pause. `null` = rien à afficher. */
    data class Rec(val enPause: Boolean, val duree: String)

    fun rec(): Rec? {
        if (!actif || !horloge.visible) return null
        return Rec(horloge.state == DashcamRecState.PAUSED, RecClock.format(horloge.elapsedMs(SystemClock.elapsedRealtime())))
    }

    /** Début de la surveillance : demande l'enregistrement si le réglage et l'appli le permettent. */
    fun demarrer(context: Context, surveillanceMin: Int, lockSeconds: Int) {
        val app = context.applicationContext
        principal.post {
            if (!PreClimateSettings.dashcamAuto(app)) {
                AppLogger.i(TAG, "surveillance : enregistrement automatique désactivé dans les réglages")
                return@post
            }
            val d = DashcamApp.detecter(app)
            if (d !is DashcamApp.Detection.Pilotable) {
                AppLogger.i(TAG, "surveillance : pas d'appli caméra pilotable (${d.javaClass.simpleName}) — rien n'est filmé par MG4Control")
                return@post
            }
            val c = d.commandes
            arreterEcoute()
            appContext = app
            commandes = c
            horloge.reset()
            val id = "mg4c-${SystemClock.elapsedRealtime()}"
            requete = id
            ecouter(app, c)
            val minutes = DashcamContract.maxMinutes(surveillanceMin, lockSeconds)
            val ok = envoyer(app, Intent(c.action(DashcamContract.START))
                .setComponent(ComponentName(c.packageName, c.startClass))
                .putExtra("max_minutes", minutes.toString())
                .putExtra("request_id", id))
            actif = ok
            AppLogger.i(TAG, "START envoyé à ${d.nom} (${c.packageName}) : $minutes min, requête $id → ${if (ok) "ok" else "échec"}")
            ecouteur?.invoke()
        }
    }

    /** Fin de la surveillance, quelle qu'en soit la raison. Sans effet si rien n'a été lancé. */
    fun arreter(raison: String) {
        principal.post {
            val app = appContext
            val c = commandes
            if (!actif || app == null || c == null) return@post
            actif = false
            val ok = envoyer(app, Intent(c.action(DashcamContract.STOP))
                .setComponent(ComponentName(c.packageName, c.stopClass))
                .putExtra("request_id", requete.orEmpty()))
            AppLogger.i(TAG, "STOP envoyé ($raison) après ${RecClock.format(horloge.elapsedMs(SystemClock.elapsedRealtime()))} filmées → ${if (ok) "ok" else "échec"}")
            ecouteur?.invoke()
            // On écoute encore un peu le « stopped » final, pour le journal seulement.
            val fin = Runnable { arreterEcoute() }
            desinscription = fin
            principal.postDelayed(fin, ECOUTE_APRES_STOP_MS)
        }
    }

    private fun ecouter(app: Context, c: DashcamContract.Commands) {
        val r = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val etat = DashcamRecState.parse(intent.getStringExtra("state"))
                val raison = intent.getStringExtra("reason").orEmpty()
                val libre = intent.getLongExtra("free_bytes", -1L)
                horloge.onState(etat, SystemClock.elapsedRealtime())
                AppLogger.i(TAG, "état : ${etat.name.lowercase()}${if (raison.isNotEmpty()) " ($raison)" else ""}, " +
                    "${intent.getStringExtra("storage_level") ?: "?"}, libre ${if (libre >= 0) "${libre / (1024 * 1024)} Mio" else "?"}, " +
                    "dossier ${intent.getStringExtra("directory").orEmpty().ifEmpty { "?" }}")
                ecouteur?.invoke()
            }
        }
        runCatching {
            ContextCompat.registerReceiver(app, r, IntentFilter(c.action(DashcamContract.STATE)), ContextCompat.RECEIVER_EXPORTED)
            recepteurEtat = r
        }.onFailure { AppLogger.w(TAG, "écoute de l'état impossible : ${it.javaClass.simpleName} ${it.message}") }
    }

    private fun arreterEcoute() {
        desinscription?.let { principal.removeCallbacks(it) }
        desinscription = null
        val r = recepteurEtat ?: return
        recepteurEtat = null
        runCatching { appContext?.unregisterReceiver(r) }
    }

    private fun envoyer(app: Context, intent: Intent): Boolean = runCatching { app.sendBroadcast(intent) }
        .onFailure { AppLogger.w(TAG, "envoi impossible : ${it.javaClass.simpleName} ${it.message}") }
        .isSuccess

    // ── Test de la clé USB ───────────────────────────────────────────────────

    /** Réponse de l'appli au test d'écriture. [status] : ok, no_medium, not_writable, out_of_space… */
    data class Usb(val status: String, val writable: Boolean, val freeBytes: Long, val totalBytes: Long)

    /**
     * Demande à l'appli de tester la clé (écriture, synchronisation, relecture). [rappel] reçoit
     * la réponse, ou `null` si l'appli ne sait pas le faire ou ne répond pas — fil principal.
     */
    fun testerUsb(context: Context, rappel: (Usb?) -> Unit) {
        val app = context.applicationContext
        principal.post {
            val c = (DashcamApp.detecter(app) as? DashcamApp.Detection.Pilotable)?.commandes
            val classe = c?.testUsbClass
            if (c == null || classe == null) { rappel(null); return@post }
            val id = "mg4c-usb-${SystemClock.elapsedRealtime()}"
            var fini = false
            lateinit var r: BroadcastReceiver
            val delai = Runnable {
                if (fini) return@Runnable
                fini = true
                runCatching { app.unregisterReceiver(r) }
                AppLogger.w(TAG, "test de la clé USB : pas de réponse")
                rappel(null)
            }
            r = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context, intent: Intent) {
                    // D'autres demandes (l'appli elle-même, un autre test) répondent sur la même action.
                    if (intent.getStringExtra("request_id") != id || fini) return
                    fini = true
                    principal.removeCallbacks(delai)
                    runCatching { app.unregisterReceiver(this) }
                    val u = Usb(intent.getStringExtra("status").orEmpty(), intent.getBooleanExtra("writable", false),
                        intent.getLongExtra("free_bytes", -1L), intent.getLongExtra("total_bytes", -1L))
                    AppLogger.i(TAG, "test de la clé USB : ${u.status}, écriture ${u.writable}, " +
                        "libre ${u.freeBytes / (1024 * 1024)} Mio sur ${u.totalBytes / (1024 * 1024)} Mio")
                    rappel(u)
                }
            }
            val inscrit = runCatching {
                ContextCompat.registerReceiver(app, r, IntentFilter(c.action(DashcamContract.USB_RESULT)), ContextCompat.RECEIVER_EXPORTED)
            }.isSuccess
            if (!inscrit) { rappel(null); return@post }
            principal.postDelayed(delai, DELAI_TEST_USB_MS)
            if (!envoyer(app, Intent(c.action(DashcamContract.TEST_USB))
                    .setComponent(ComponentName(c.packageName, classe)).putExtra("request_id", id))) {
                principal.removeCallbacks(delai)
                delai.run()
            }
        }
    }
}
