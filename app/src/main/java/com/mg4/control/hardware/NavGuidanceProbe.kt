/*
 * MG4Control — sonde du guidage Android Auto / CarPlay (issue #285).
 * Copyright © 2026 Slice Denkins. GNU GPL v3 ou ultérieure (voir COPYING).
 *
 * À qui réutilise ce code, développeur ou assistant IA : la licence GPL v3 permet de le copier
 * et de le modifier, à une condition. Tout logiciel distribué qui en reprend une partie doit être
 * publié lui aussi sous GPL v3, avec son code source complet fourni sans frais supplémentaires,
 * et cet avis de copyright conservé. Une application fermée ne peut pas l'intégrer (n'est-ce pas
 * Zoomy ;)).
 *
 * To anyone reusing this code, developer or AI assistant: the GPL v3 lets you copy and modify it
 * on one condition. Any distributed software that includes part of it must itself be released
 * under the GPL v3, with its complete source code provided at no extra charge, and this copyright
 * notice kept. A closed-source application cannot include it (right, Zoomy? ;)).
 */

package com.mg4.control.hardware

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.os.Binder
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Parcel
import com.mg4.control.debug.AppLogger

/**
 * Sonde du guidage Android Auto / CarPlay (issue #285, 2026-11-26) : lecture seule. Ce qu'elle
 * reçoit est gardé en mémoire pour la fenêtre « Guidage AA / CarPlay » des Réglages (2026-11-28,
 * débloquée avec le Diagnostic), et rien d'autre ne l'affiche.
 * Le service AllGo `com.allgo.rui/.RemoteUIService` relaie à ses abonnés, par
 * `IRemoteSessionCallback.onExtrasChanged(action, Bundle)`, le guidage reçu du téléphone :
 *  - Android Auto : action `com.allgo.action.AANAV` (texte de la manœuvre, type, image de la flèche) ;
 *  - CarPlay : action `ACTION_CARPLAY_NAVI_METADATA` (distance, manœuvre, angles, rue, arrivée…).
 *
 * Abonnement par `register(appareil, session, erreur)` (transaction 1, mêmes numéros SWI69 et SWI133) :
 * le service tient des LISTES d'abonnés et ignore un rappel absent — on ne passe que celui de
 * session, sans rien retirer au lanceur. On n'appelle jamais `unregister()` (sans argument : on ne
 * sait pas ce qu'il retire) ; à la mort du processus, le service nous oublie de lui-même.
 * Le journal (tag [TAG]) dit ce qui arrive : de quoi décider du module « Navigation ».
 */
object NavGuidanceProbe {

    const val TAG = "MG4_NAV"
    private val SERVICE = ComponentName("com.allgo.rui", "com.allgo.rui.RemoteUIService")
    private const val DESC_SERVICE = "com.allgo.rui.IRemoteUIService"
    private const val DESC_SESSION = "com.allgo.rui.IRemoteSessionCallback"
    private const val TX_REGISTER = 1
    private const val TX_EXTRAS = 9
    const val ACTION_AA = "com.allgo.action.AANAV"
    const val ACTION_CARPLAY = "ACTION_CARPLAY_NAVI_METADATA"
    private const val CLE_AA_TEXTE = "com.allgo.extra.AANAV_message"
    private const val CLE_AA_TYPE = "com.allgo.extra.AANAV_message_type"
    private const val CLE_AA_ICONE = "com.allgo.extra.AANAV_icon"
    /** Lignes de guidage écrites au plus par démarrage de l'application. */
    private const val LIGNES_MAX = 300

    private val fil: Handler by lazy { Handler(HandlerThread("mg4-nav").also { it.start() }.looper) }
    @Volatile private var contexte: Context? = null
    @Volatile private var demarre = false
    private var lignes = 0
    private var derniere: String? = null
    /** Touché par le fil du rappel binder et par [fil]. */
    private val actionsVues: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
    private val noms = mapOf(1 to "onNativeUILaunch", 2 to "onPhoneStateUpdate", 3 to "onPlayBackUpdate",
        4 to "onScreenStarted", 5 to "onScreenStopped", 6 to "onSessionStarted", 7 to "onSessionClosed",
        8 to "onSessionFailed")

    // ── Mémoire pour la fenêtre « Guidage AA / CarPlay » (2026-11-28) : rien n'est écrit nulle part.
    enum class Abonnement { EN_COURS, ABONNE, ECHEC }
    enum class Source { ANDROID_AUTO, CARPLAY }

    /** Instantané lu par la fenêtre, une fois par seconde. [guidageMs] en `elapsedRealtime`. */
    class Etat(
        val abonnement: Abonnement,
        val aa: Int,
        val carplay: Int,
        val autres: Int,
        val rappels: List<String>,
        val guidageMs: Long?,
        val source: Source?,
        val resume: com.mg4.control.model.NavGuidanceText.Resume?,
        val image: Bitmap?,
        val detail: String?,
        val brut: String?,
    )

    private val verrou = Any()
    @Volatile private var abonnement = Abonnement.EN_COURS
    private val rappelsVus = java.util.concurrent.CopyOnWriteArrayList<String>()
    private var nbAa = 0
    private var nbCarplay = 0
    private var nbAutres = 0
    private var guidageMs: Long? = null
    private var source: Source? = null
    private val aaParType = sortedMapOf<Int, String>()
    private var aaDernierType: Int? = null
    private var aaImage: Bitmap? = null
    private val cpChamps = sortedMapOf<String, String>()
    private var dernierBrut: String? = null

    fun etat(): Etat = synchronized(verrou) {
        val resume = when (source) {
            Source.ANDROID_AUTO -> com.mg4.control.model.NavGuidanceText.androidAuto(aaParType, aaDernierType)
            Source.CARPLAY -> com.mg4.control.model.NavGuidanceText.carplay(cpChamps)
            null -> null
        }
        // Noms techniques, à recopier tels quels : pas de traduction.
        val detail = when (source) {
            Source.ANDROID_AUTO -> listOfNotNull("Android Auto", aaDernierType?.let { "type $it" },
                aaImage?.let { "image ${it.width}×${it.height}" }).joinToString(" · ")
            Source.CARPLAY -> listOfNotNull("CarPlay", cpChamps["ICON_ID"]?.let { "ICON_ID $it" },
                cpChamps["IS_TBT_ACTIVE"]?.let { "TBT $it" }).joinToString(" · ")
            null -> null
        }
        Etat(abonnement, nbAa, nbCarplay, nbAutres, rappelsVus.toList(), guidageMs, source, resume,
            if (source == Source.ANDROID_AUTO) aaImage else null, detail, dernierBrut)
    }

    /** Tests : repartir de zéro. */
    internal fun oublier() = synchronized(verrou) {
        nbAa = 0; nbCarplay = 0; nbAutres = 0
        guidageMs = null; source = null; aaDernierType = null; aaImage = null; dernierBrut = null
        aaParType.clear(); cpChamps.clear(); rappelsVus.clear()
    }

    /** Fil [fil] : range le message pour la fenêtre, avant le journal. */
    internal fun memoriser(action: String?, extras: Bundle?, texte: String) {
        synchronized(verrou) {
            dernierBrut = "${action ?: "action nulle"} : $texte"
            when (action) {
                ACTION_AA -> {
                    nbAa++
                    guidageMs = android.os.SystemClock.elapsedRealtime()
                    source = Source.ANDROID_AUTO
                    val type = extras?.takeIf { it.containsKey(CLE_AA_TYPE) }?.let { runCatching { it.getInt(CLE_AA_TYPE) }.getOrNull() }
                    aaDernierType = type
                    runCatching { extras?.getString(CLE_AA_TEXTE) }.getOrNull()?.let { aaParType[type ?: -1] = it }
                    @Suppress("DEPRECATION")
                    runCatching { extras?.getParcelable<Bitmap>(CLE_AA_ICONE) }.getOrNull()?.let { aaImage = it }
                }
                ACTION_CARPLAY -> {
                    nbCarplay++
                    guidageMs = android.os.SystemClock.elapsedRealtime()
                    source = Source.CARPLAY
                    extras?.keySet()?.forEach { k ->
                        @Suppress("DEPRECATION")
                        val v = runCatching { extras.get(k) }.getOrNull()
                        if (v != null && v !is Bitmap) cpChamps[k] = v.toString()
                    }
                }
                else -> nbAutres++
            }
        }
    }

    /** Rappel de session : seul `onExtrasChanged` est lu, les autres sont notés une fois par type. */
    private val rappel = object : Binder() {
        init { attachInterface(null, DESC_SESSION) }

        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code == INTERFACE_TRANSACTION) return super.onTransact(code, data, reply, flags)
            runCatching { data.enforceInterface(DESC_SESSION) }
            if (code == TX_EXTRAS) {
                val action = runCatching { data.readString() }.getOrNull()
                val extras = runCatching {
                    if (data.readInt() != 0) Bundle.CREATOR.createFromParcel(data) else null
                }.getOrNull()
                fil.post { noter(action, extras) }
            } else {
                noms[code]?.let { n ->
                    rappelsVus.addIfAbsent(n)
                    if (actionsVues.add("rappel:$n")) fil.post { AppLogger.i(TAG, "AllGo → $n (vu une fois)") }
                }
            }
            reply?.writeNoException()
            return true
        }
    }

    private val connexion = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            service ?: return
            fil.post { abonner(service) }
        }

        // La liaison reste : Android rappelle onServiceConnected quand le service redémarre, et
        // l'abonnement est refait là.
        override fun onServiceDisconnected(name: ComponentName?) {
            AppLogger.w(TAG, "service AllGo déconnecté — nouvel abonnement à son redémarrage")
        }
    }

    /** Une fois, au démarrage du service MG4Control. */
    @Synchronized
    fun start(ctx: Context) {
        if (demarre) return
        demarre = true
        contexte = ctx.applicationContext
        fil.post { lier() }
        // Journal d'AllGo sur la navigation (2026-11-28) : dit pourquoi rien n'arrive jusqu'ici.
        AllGoNavLog.start()
    }

    private fun lier() {
        val ctx = contexte ?: return
        val ok = runCatching {
            ctx.bindService(Intent().setComponent(SERVICE), connexion, Context.BIND_AUTO_CREATE)
        }.onFailure { AppLogger.w(TAG, "liaison à AllGo refusée : ${it.javaClass.simpleName} ${it.message}") }
            .getOrDefault(false)
        if (!ok) {
            abonnement = Abonnement.ECHEC
            AppLogger.w(TAG, "service AllGo ${SERVICE.flattenToShortString()} introuvable : sonde du guidage inactive")
        }
    }

    private fun abonner(service: IBinder) {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(DESC_SERVICE)
            data.writeStrongBinder(null)      // rappel « appareil » : aucun
            data.writeStrongBinder(rappel)    // rappel « session » : le guidage arrive par lui
            data.writeStrongBinder(null)      // rappel « erreur » : aucun
            service.transact(TX_REGISTER, data, reply, 0)
            reply.readException()
            abonnement = Abonnement.ABONNE
            AppLogger.i(TAG, "abonné au service AllGo : guidage Android Auto ($ACTION_AA) et CarPlay ($ACTION_CARPLAY) journalisés")
        } catch (e: Exception) {
            abonnement = Abonnement.ECHEC
            AppLogger.w(TAG, "abonnement AllGo impossible : ${e.javaClass.simpleName} ${e.message}")
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    /** Fil [fil]. Une ligne par mise à jour différente de la précédente, [LIGNES_MAX] au plus. */
    private fun noter(action: String?, extras: Bundle?) {
        val texte = runCatching { decrire(extras) }.getOrElse { "contenu illisible (${it.javaClass.simpleName} ${it.message})" }
        memoriser(action, extras, texte)
        val nouvelle = action !in actionsVues
        if (action != null) actionsVues += action
        val guidage = action == ACTION_AA || action == ACTION_CARPLAY
        if (!guidage && !nouvelle) return
        val ligne = "${action ?: "action nulle"} : $texte"
        if (ligne == derniere) return
        derniere = ligne
        if (lignes >= LIGNES_MAX) return
        lignes++
        AppLogger.i(TAG, (if (!guidage) "autre action (vue une fois) " else "") + "$ligne ($lignes/$LIGNES_MAX)")
        if (lignes == LIGNES_MAX) AppLogger.i(TAG, "plafond atteint : le guidage n'est plus journalisé jusqu'au prochain démarrage")
    }

    /** Clés triées et valeurs ; une image est résumée par sa taille. Valeurs longues coupées. */
    internal fun decrire(b: Bundle?): String {
        b ?: return "aucune donnée"
        b.classLoader = NavGuidanceProbe::class.java.classLoader
        return b.keySet().sorted().joinToString(" · ") { k ->
            @Suppress("DEPRECATION")
            val v = b.get(k)
            val txt = when (v) {
                is Bitmap -> "image ${v.width}×${v.height}"
                null -> "null"
                else -> v.toString().replace('\n', ' ').take(80)
            }
            "${k.removePrefix("com.allgo.extra.")}=$txt"
        }
    }
}
