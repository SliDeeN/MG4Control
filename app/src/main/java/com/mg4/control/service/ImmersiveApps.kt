package com.mg4.control.service

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.provider.Settings
import com.mg4.control.debug.AppLogger
import com.mg4.control.model.ImmersiveMode
import com.mg4.control.model.ImmersivePolicy

/**
 * Applis en plein écran (issue #248, Réglages › Interface, désactivé par défaut) : le bandeau du
 * haut et la barre latérale de la voiture sont les barres système d'Android 9 ; `policy_control`
 * les cache pour les applis listées. Par appli : bandeau seul, barre seule, ou les deux.
 *
 * La clé est partagée avec MG4Dash : la fusion suit la règle commune de [ImmersivePolicy]
 * (une clé par propriétaire, chacun ne retire que ses paquets). Une version de MG4Dash qui ne
 * fusionne pas encore remplacerait nos paquets ou perdrait les siens : l'option reste en pause
 * tant qu'elle est installée ([mg4Dash]).
 *
 * Un passage au démarrage du service et à chaque changement, une relecture unique 5 s après une
 * écriture (pas de lecture-écriture atomique dans Settings). Journal [TAG].
 */
object ImmersiveApps {

    private const val TAG = "MG4_IMMERSIF"
    private const val PREFS = "mg4_settings"
    const val KEY_ON = "immersive_apps_on"
    /** Choix de l'utilisateur, au format des clés communes (`paquet`, `paquet/status`…). */
    const val KEY_APPS = "immersive_apps"
    private const val RELECTURE_MS = 5_000L

    /**
     * Première version de MG4Dash qui applique la règle commune ; `null` tant que son développeur
     * ne l'a pas donnée : toute version installée met alors l'option en pause.
     */
    private val MG4DASH_FUSION_VERSION: Long? = null

    enum class Mg4Dash { ABSENT, OK, OUTDATED }

    private val fil: Handler by lazy { Handler(HandlerThread("mg4-immersive").also { it.start() }.looper) }

    fun isOn(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_ON, false)

    fun setOn(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_ON, on).apply()
        AppLogger.i(TAG, "applis en plein écran : ${if (on) "activé" else "désactivé"}")
        refresh(ctx, "option")
    }

    /** Une appli → un mode ; « tout » si elle a les deux modes partiels. */
    fun selection(ctx: Context): Map<String, ImmersiveMode> =
        ImmersivePolicy.parseKey(prefs(ctx).getString(KEY_APPS, null)).mapValues { (_, m) ->
            when {
                ImmersiveMode.FULL in m || (ImmersiveMode.STATUS in m && ImmersiveMode.NAVIGATION in m) -> ImmersiveMode.FULL
                ImmersiveMode.STATUS in m -> ImmersiveMode.STATUS
                else -> ImmersiveMode.NAVIGATION
            }
        }

    fun setSelection(ctx: Context, choix: Map<String, ImmersiveMode>) {
        val texte = ImmersivePolicy.formatKey(choix.mapValues { setOf(it.value) })
        prefs(ctx).edit().putString(KEY_APPS, texte).apply()
        AppLogger.i(TAG, "applis choisies : ${texte ?: "aucune"}")
        refresh(ctx, "choix")
    }

    fun mg4Dash(ctx: Context): Mg4Dash {
        val info = runCatching { ctx.packageManager.getPackageInfo(ImmersivePolicy.MG4DASH, 0) }.getOrNull()
            ?: return Mg4Dash.ABSENT
        @Suppress("DEPRECATION")
        val version = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
        val mini = MG4DASH_FUSION_VERSION ?: return Mg4Dash.OUTDATED
        return if (version >= mini) Mg4Dash.OK else Mg4Dash.OUTDATED
    }

    /** Applis que MG4Dash cache entièrement (sa clé) : elles le restent quel que soit notre choix. */
    fun mg4DashFull(ctx: Context): Set<String> =
        ImmersivePolicy.parseKey(global(ctx, ImmersivePolicy.OWNER_KEYS.getValue(ImmersivePolicy.MG4DASH)))
            .filterValues { ImmersiveMode.FULL in it }.keys

    /**
     * Applis proposées : celles qui s'ouvrent depuis l'écran, sans Android Auto, CarPlay, les
     * lanceurs (MG4Dash compris) ni MG4Control. (paquet, nom), triées par nom.
     */
    fun candidates(ctx: Context): List<Pair<String, String>> {
        val pm = ctx.packageManager
        val lanceurs = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
            .map { it.activityInfo.packageName }.toSet()
        val exclus = lanceurs + ImmersivePolicy.OWNER_KEYS.keys + setOf(ImmersivePolicy.ANDROID_AUTO, ImmersivePolicy.CARPLAY)
        return pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
            .filter { (p, _) -> p !in exclus && ImmersivePolicy.isClaimable(p) }
            .distinctBy { it.first }
            .sortedBy { it.second.lowercase() }
    }

    /** Un passage, sur son fil : démarrage du service, option ou choix changés. */
    fun refresh(ctx: Context, raison: String) {
        val app = ctx.applicationContext
        fil.post { passage(app, raison, relire = true) }
    }

    private fun passage(app: Context, raison: String, relire: Boolean) {
        runCatching {
            val self = app.packageName
            val maCle = ImmersivePolicy.OWNER_KEYS[self] ?: run {
                AppLogger.w(TAG, "paquet $self absent de la règle commune — rien")
                return
            }
            val dash = mg4Dash(app)
            val voulus = if (isOn(app) && dash != Mg4Dash.OUTDATED) selection(app).mapValues { setOf(it.value) } else emptyMap()
            val cles = ImmersivePolicy.OWNER_KEYS.entries.associate { (o, k) -> o to global(app, k) }
            val installes = ImmersivePolicy.OWNER_KEYS.keys.filter { it == self || installe(app, it) }.toSet()
            val pause = if (dash == Mg4Dash.OUTDATED && isOn(app)) " (en pause : MG4Dash à mettre à jour)" else ""
            // Rien à ajouter, rien à nous à retirer : on ne touche pas à la valeur, même pour la
            // ranger — elle peut être celle d'une autre appli ou d'un ancien MG4Dash (revue du
            // 2026-11-15). En pause, seuls nos restes éventuels sont retirés.
            if (!ImmersivePolicy.hasStake(self, voulus, cles, installes)) {
                AppLogger.i(TAG, "$raison : rien à faire$pause")
                return
            }
            val avant = global(app, ImmersivePolicy.SETTING)
            val p = ImmersivePolicy.plan(self, voulus, avant, cles, installes)

            // Une écriture refusée arrête le passage : les clés de suivi restent telles quelles (la
            // nôtre garde anciens + voulus), le passage suivant reprendra (revue du 2026-11-15).
            var ecrit = false
            fun ecrire(nom: String, valeur: String?): Boolean {
                if (put(app, nom, valeur)) { ecrit = true; return true }
                AppLogger.w(TAG, "$raison : écriture de $nom refusée — passage arrêté, clés de suivi gardées")
                if (relire) fil.postDelayed({ passage(app, "nouvel essai", relire = false) }, RELECTURE_MS)
                return false
            }
            var cle = cles[self]
            if (p.keyBefore != cle) { if (!ecrire(maCle, p.keyBefore)) return; cle = p.keyBefore }
            if (p.policyChanged) {
                if (!ecrire(ImmersivePolicy.SETTING, p.policy)) return
                AppLogger.i(TAG, "$raison : policy_control ${avant ?: "(vide)"} → ${p.policy ?: "(vide)"}")
            }
            if (p.keyAfter != cle && !ecrire(maCle, p.keyAfter)) return
            for (k in p.clearKeys) if (!ecrire(k, null)) return
            if (!ecrit) AppLogger.i(TAG, "$raison : rien à changer$pause")
            if (ecrit && relire) fil.postDelayed({ passage(app, "relecture", relire = false) }, RELECTURE_MS)
        }.onFailure { AppLogger.w(TAG, "$raison : ${it.javaClass.simpleName} ${it.message}") }
    }

    private fun global(ctx: Context, nom: String): String? =
        runCatching { Settings.Global.getString(ctx.contentResolver, nom) }.getOrNull()?.ifEmpty { null }

    /** `false` si Android refuse l'écriture ([Settings.Global.putString] le dit) ou si elle lève. */
    private fun put(ctx: Context, nom: String, valeur: String?): Boolean =
        runCatching { Settings.Global.putString(ctx.contentResolver, nom, valeur) }.getOrDefault(false)

    private fun installe(ctx: Context, pkg: String): Boolean =
        runCatching { ctx.packageManager.getPackageInfo(pkg, 0); true }.getOrDefault(false)

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
