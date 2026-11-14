package com.mg4.control.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import com.mg4.control.debug.AppLogger
import com.mg4.control.hardware.ProjectionReader
import com.mg4.control.hardware.UsbPhoneKick
import com.mg4.control.model.ProjectionKickRule
import com.mg4.control.model.ProjectionKind
import com.mg4.control.model.UsbPhoneKind
import com.mg4.control.model.UsbPhoneRule
import com.mg4.control.model.UsbSeen
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Relancer la détection du téléphone branché (issue #251, Réglages › Avancé, désactivé par
 * défaut) : un téléphone branché avant l'écran n'est pas vu par AllGo, qui ne regarde l'USB qu'à
 * son démarrage (voir [UsbPhoneRule]). On lui redit « appareil branché » — Android Auto comme
 * CarPlay, AllGo trie lui-même.
 *
 * Trois déclencheurs : le démarrage de l'écran et son réveil après une vraie veille (option), et le
 * raccourci « Android Auto / CarPlay » sans session (toujours). Rien si une projection est ouverte.
 * Deux essais au plus : l'avis seul, puis réinitialisation USB + avis ; jamais de boucle.
 * Au démarrage et au réveil, le téléphone Android est laissé à AA Kick s'il est installé.
 * Journal [TAG].
 */
object ProjectionKick {

    private const val TAG = "MG4_PROJ"
    private const val PREFS = "mg4_settings"
    const val KEY = "projection_usb_kick"
    private const val AA_KICK = "com.mg4.aakick"

    /** Laisse AllGo finir sa propre vérification du démarrage. */
    private const val DELAI_DEMARRAGE_MS = 15_000L
    private const val DELAI_REVEIL_MS = 10_000L
    /** Service démarré plus tard qu'Android + ceci : ce n'est pas un allumage de la voiture. */
    private const val DEMARRAGE_RECENT_MS = 180_000L
    /** Veille minimale pour parler de réveil. */
    private const val VEILLE_MIN_MS = 60_000L
    /** Attente de la session après chaque essai (bascule AOAP comprise pour Android Auto). */
    private const val ATTENTE_SESSION_MS = 15_000L
    private const val PAS_MS = 1_000L
    /** Le téléphone réapparaît après la réinitialisation. */
    private const val APRES_RESET_MS = 2_000L

    enum class Origin(val label: String) { BOOT("démarrage"), WAKE("réveil"), SHORTCUT("raccourci") }

    sealed class Outcome {
        /** Une session est ouverte ([kind] `null` : type inconnu). */
        data class Session(val kind: ProjectionKind?) : Outcome()
        object NoPhone : Outcome()
        object NotRecognised : Outcome()
        object Unreachable : Outcome()
        /** Téléphone Android laissé à AA Kick. */
        object LeftToAaKick : Outcome()
    }

    private val fil: Handler by lazy { Handler(HandlerThread("mg4-projkick").also { it.start() }.looper) }
    private val enCours = AtomicBoolean(false)
    /** `elapsedRealtime − uptimeMillis` au dernier relevé : grandit pendant la veille. */
    @Volatile private var ecartVeille = 0L
    private var ecran: BroadcastReceiver? = null

    fun isOn(ctx: Context?): Boolean =
        ctx?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.getBoolean(KEY, false) ?: false

    fun setOn(ctx: Context, on: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, on).apply()
        AppLogger.i(TAG, "relancer la détection du téléphone branché : ${if (on) "activé" else "désactivé"}")
    }

    /** Démarrage du service. L'écoute de l'écran est posée même option coupée : l'option se lit à l'allumage. */
    fun start(ctx: Context) {
        val app = ctx.applicationContext
        ecartVeille = ecart()
        if (ecran == null) {
            val r = object : BroadcastReceiver() {
                override fun onReceive(c: Context?, i: Intent?) = screenOn(app)
            }
            runCatching { app.registerReceiver(r, IntentFilter(Intent.ACTION_SCREEN_ON)) }
                .onSuccess { ecran = r }
                .onFailure { AppLogger.w(TAG, "écoute de l'écran impossible : ${it.javaClass.simpleName} ${it.message}") }
        }
        if (!isOn(app)) return
        val depuis = SystemClock.elapsedRealtime()
        if (!ProjectionKickRule.recentBoot(depuis, DEMARRAGE_RECENT_MS)) {
            AppLogger.i(TAG, "relance USB : service démarré ${depuis / 1000} s après Android, pas un allumage — rien")
            return
        }
        AppLogger.i(TAG, "relance USB : démarrage, vérification dans ${DELAI_DEMARRAGE_MS / 1000} s")
        fil.postDelayed({ launch(app, Origin.BOOT, null) }, DELAI_DEMARRAGE_MS)
    }

    fun stop(ctx: Context) {
        ecran?.let { r -> runCatching { ctx.applicationContext.unregisterReceiver(r) } }
        ecran = null
    }

    private fun ecart() = SystemClock.elapsedRealtime() - SystemClock.uptimeMillis()

    private fun screenOn(app: Context) {
        val avant = ecartVeille
        val maintenant = ecart()
        ecartVeille = maintenant
        if (!isOn(app)) return
        if (!ProjectionKickRule.slept(avant, maintenant, VEILLE_MIN_MS)) {
            AppLogger.i(TAG, "relance USB : écran allumé sans veille (${(maintenant - avant) / 1000} s) — rien")
            return
        }
        AppLogger.i(TAG, "relance USB : réveil après ${(maintenant - avant) / 1000} s de veille, vérification dans ${DELAI_REVEIL_MS / 1000} s")
        fil.postDelayed({ launch(app, Origin.WAKE, null) }, DELAI_REVEIL_MS)
    }

    /** Un téléphone (ou un Android déjà basculé) est-il sur l'USB ? Lecture seule, rapide. */
    fun phonePlugged(ctx: Context): Boolean {
        val vus = UsbPhoneKick.devices(ctx).map { it.first }
        return UsbPhoneRule.pick(vus) != null || UsbPhoneRule.anyAoap(vus)
    }

    /** Lance une relance sur son fil ; `false` si une autre est en cours. [then] est appelé sur ce fil. */
    fun launch(ctx: Context, origin: Origin, then: ((Outcome) -> Unit)?): Boolean {
        val app = ctx.applicationContext
        if (!enCours.compareAndSet(false, true)) {
            AppLogger.i(TAG, "relance USB (${origin.label}) : une relance est déjà en cours")
            return false
        }
        fil.post {
            val issue = try {
                relancer(app, origin)
            } catch (e: Exception) {
                AppLogger.w(TAG, "relance USB : ${e.javaClass.simpleName} ${e.message}")
                Outcome.NotRecognised
            } finally {
                enCours.set(false)
            }
            then?.invoke(issue)
        }
        return true
    }

    private fun relancer(app: Context, origin: Origin): Outcome {
        val lecture = ProjectionReader.appareilActif(app) as? ProjectionReader.Lecture.Lue ?: run {
            AppLogger.w(TAG, "relance USB (${origin.label}) : AllGo injoignable — rien")
            return Outcome.Unreachable
        }
        lecture.appareil?.let {
            AppLogger.i(TAG, "relance USB (${origin.label}) : session ${it.kind?.label ?: "type ${it.type}"} déjà ouverte — rien")
            return Outcome.Session(it.kind)
        }
        val vus = UsbPhoneKick.devices(app)
        // Téléphone resté basculé pour Android Auto (session d'avant, veille) : AllGo ne le
        // redécouvre qu'au branchement — on le lui annonce aussi (idée reprise de MG4Dash).
        val dejaBascule = bascule(vus)
        val choisi = dejaBascule ?: choisir(vus) ?: run {
            AppLogger.i(TAG, "relance USB (${origin.label}) : aucun téléphone sur l'USB (${vus.size} appareil(s)) — rien")
            return Outcome.NoPhone
        }
        val type = if (dejaBascule != null) UsbPhoneKind.ANDROID else UsbPhoneRule.kind(choisi.first)!!
        if (type == UsbPhoneKind.ANDROID && origin != Origin.SHORTCUT && installe(app, AA_KICK)) {
            AppLogger.i(TAG, "relance USB (${origin.label}) : téléphone Android laissé à AA Kick")
            return Outcome.LeftToAaKick
        }
        AppLogger.i(TAG, "relance USB (${origin.label}) : ${type.label}" +
            (if (dejaBascule != null) " déjà basculé pour Android Auto" else "") +
            " branché sans session (${hex(choisi.first)}) — essai 1 : nouvel avis de branchement")
        UsbPhoneKick.announceAttached(app, choisi.second)
        (attendre(app, "essai 1") as? Outcome.Session)?.let { return it }

        // Essai 2 : comme un câble débranché puis rebranché.
        val encore = UsbPhoneKick.devices(app)
        val basculeMaintenant = bascule(encore)
        // Basculé par notre essai 1 : AllGo est en route, on ne casse pas sa bascule.
        if (basculeMaintenant != null && dejaBascule == null) return attendre(app, "bascule Android Auto")
        val tel = basculeMaintenant ?: choisir(encore) ?: run {
            AppLogger.i(TAG, "relance USB : le téléphone n'est plus sur l'USB")
            return Outcome.NoPhone
        }
        AppLogger.i(TAG, "relance USB : essai 2 — réinitialisation USB puis nouvel avis")
        val reinit = UsbPhoneKick.reset(app, tel.second)
        SystemClock.sleep(APRES_RESET_MS)
        val apres = UsbPhoneKick.devices(app)
        val revu = bascule(apres) ?: choisir(apres)
        if (revu != null) UsbPhoneKick.announceAttached(app, revu.second)
        else AppLogger.i(TAG, "relance USB : téléphone pas encore revu après la réinitialisation ($reinit)")
        return attendre(app, "essai 2")
    }

    private fun choisir(vus: List<Pair<UsbSeen, android.hardware.usb.UsbDevice>>) =
        UsbPhoneRule.pick(vus.map { it.first })?.let { c -> vus.first { it.first == c } }

    private fun bascule(vus: List<Pair<UsbSeen, android.hardware.usb.UsbDevice>>) =
        UsbPhoneRule.firstAoap(vus.map { it.first })?.let { c -> vus.first { it.first == c } }

    /** Relit AllGo chaque seconde ; la session ouverte, ou « non reconnu » au bout de l'attente. */
    private fun attendre(app: Context, etape: String): Outcome {
        val debut = SystemClock.elapsedRealtime()
        while (SystemClock.elapsedRealtime() - debut < ATTENTE_SESSION_MS) {
            SystemClock.sleep(PAS_MS)
            val a = (ProjectionReader.appareilActif(app) as? ProjectionReader.Lecture.Lue)?.appareil ?: continue
            AppLogger.i(TAG, "relance USB ($etape) : session ${a.kind?.label ?: "type ${a.type}"} ouverte " +
                "après ${(SystemClock.elapsedRealtime() - debut) / 1000} s")
            return Outcome.Session(a.kind)
        }
        AppLogger.i(TAG, "relance USB ($etape) : pas de session après ${ATTENTE_SESSION_MS / 1000} s")
        return Outcome.NotRecognised
    }

    private fun installe(ctx: Context, pkg: String): Boolean =
        runCatching { ctx.packageManager.getPackageInfo(pkg, 0); true }.getOrDefault(false)

    private fun hex(d: UsbSeen) = "%04x:%04x".format(java.util.Locale.US, d.vendorId, d.productId)
}
