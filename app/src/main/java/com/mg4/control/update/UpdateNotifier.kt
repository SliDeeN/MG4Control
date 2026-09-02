package com.mg4.control.update

import android.content.Context
import android.content.Intent
import com.mg4.control.BuildConfig
import com.mg4.control.debug.AppLogger

/**
 * Décide QUAND signaler une mise à jour par-dessus l'infodivertissement.
 *
 * Séparé de [UpdateChecker], qui sait seulement interroger GitHub et GitLab, et de
 * `UpdateOverlay`, qui sait seulement afficher. Ici vivent les garde-fous — et il en faut,
 * parce que ce chemin-là n'est déclenché par personne : ni ouverture d'application, ni bouton.
 */
object UpdateNotifier {

    private const val TAG = "MG4_UPDATE"

    private const val PREFS = "mg4_settings"

    /** Interrupteur du popup véhicule. Défaut true : la fonction a du sens sans réglage. */
    const val KEY_OVERLAY_ENABLED = "update_overlay_enabled"

    /** Interrupteur historique, commun avec la vérification au lancement de l'application. */
    private const val KEY_AUTO_CHECK = "auto_check_update"

    private const val KEY_LAST_CHECK = "update_overlay_last_check"

    /**
     * Six heures entre deux interrogations réseau.
     *
     * Ce n'est pas de la frilosité : la voiture est sur un forfait données que l'utilisateur
     * suit (voir l'écran de consommation), et rien ne justifie d'aller chercher une release à
     * chaque coup de contact d'une journée de trajets courts.
     */
    private const val MIN_INTERVAL_MS = 6L * 60L * 60L * 1_000L

    /**
     * Version déjà proposée depuis le démarrage du service.
     *
     * En mémoire et non en préférence : « déjà vu » ne doit durer que le temps d'une session.
     * Refuser durablement, c'est le rôle d'« Ignorer cette version », que l'utilisateur choisit.
     *
     * Renseignée par [marquerProposee] et NON par [check] : entre les deux, le popup peut très
     * bien renoncer à s'afficher (véhicule en mouvement, permission d'overlay retirée). Marquer
     * trop tôt ferait disparaître l'annonce pour toute la session sans que personne ne l'ait vue.
     */
    @Volatile private var dejaProposee: String? = null

    /** À appeler quand le popup est réellement apparu à l'écran. */
    fun marquerProposee(version: String) {
        dejaProposee = version
    }

    fun isEnabled(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_OVERLAY_ENABLED, true)

    fun setEnabled(ctx: Context, actif: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_OVERLAY_ENABLED, actif).apply()
        AppLogger.i(TAG, "popup véhicule ${if (actif) "activé" else "désactivé"}")
    }

    /**
     * Interroge le dépôt si toutes les conditions sont réunies, et rappelle [onDisponible] sur
     * le thread principal avec la version trouvée.
     *
     * [UpdateChecker.check] écarte déjà de lui-même la version qu'on a demandé d'ignorer : il
     * n'y a donc rien à filtrer ici de ce côté.
     */
    fun check(ctx: Context, raison: String, onDisponible: (UpdateInfo) -> Unit) {
        if (BuildConfig.OFFLINE) return
        if (!isEnabled(ctx)) return
        // L'interrupteur historique coupe TOUTE vérification automatique. Le popup véhicule en
        // est une : le laisser passer trahirait un réglage que l'utilisateur croit global.
        if (!prefs(ctx).getBoolean(KEY_AUTO_CHECK, true)) return

        val maintenant = System.currentTimeMillis()
        val dernier = prefs(ctx).getLong(KEY_LAST_CHECK, 0L)
        // Une horloge véhicule qui recule (mise à l'heure GPS) rendrait l'écart négatif et
        // bloquerait la vérification pour six heures : on repart de zéro dans ce cas.
        val ecart = maintenant - dernier
        if (dernier != 0L && ecart in 0 until MIN_INTERVAL_MS) return

        prefs(ctx).edit().putLong(KEY_LAST_CHECK, maintenant).apply()
        AppLogger.i(TAG, "vérification de mise à jour ($raison)")

        UpdateChecker.check(
            context = ctx,
            onUpdateAvailable = { info ->
                if (info.versionName == dejaProposee) {
                    AppLogger.i(TAG, "${info.versionName} déjà proposée dans cette session")
                    return@check
                }
                onDisponible(info)
            }
            // Silencieux quand tout va bien ou quand le réseau manque : personne n'a rien demandé.
        )
    }

    // ── Transport de l'UpdateInfo jusqu'à MainActivity ───────────────────────
    //
    // Sans ça, « Installer la MAJ » ouvrirait l'application qui relancerait sa propre requête :
    // l'utilisateur regarderait un écran vide le temps de l'aller-retour, pour réapprendre ce
    // qu'on vient de lui dire. Quatre chaînes suffisent, UpdateInfo n'a pas besoin d'être
    // Parcelable pour autant.

    const val EXTRA_UPDATE = "mg4.update.available"
    private const val EXTRA_VERSION = "mg4.update.version"
    private const val EXTRA_TAG     = "mg4.update.tag"
    private const val EXTRA_APK     = "mg4.update.apk"
    private const val EXTRA_NOTES   = "mg4.update.notes"

    fun putInto(intent: Intent, info: UpdateInfo): Intent = intent.apply {
        putExtra(EXTRA_UPDATE, true)
        putExtra(EXTRA_VERSION, info.versionName)
        putExtra(EXTRA_TAG, info.tagName)
        putExtra(EXTRA_APK, info.apkUrl)
        putExtra(EXTRA_NOTES, info.releaseNotes)
    }

    /** L'[UpdateInfo] portée par l'intent, ou `null` si l'application a été ouverte autrement. */
    fun readFrom(intent: Intent?): UpdateInfo? {
        if (intent?.getBooleanExtra(EXTRA_UPDATE, false) != true) return null
        val version = intent.getStringExtra(EXTRA_VERSION) ?: return null
        val apk     = intent.getStringExtra(EXTRA_APK) ?: return null
        return UpdateInfo(
            versionName  = version,
            tagName      = intent.getStringExtra(EXTRA_TAG) ?: "v$version",
            apkUrl       = apk,
            releaseNotes = intent.getStringExtra(EXTRA_NOTES).orEmpty()
        )
    }

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
