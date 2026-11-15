package com.mg4.control.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.provider.Settings
import com.mg4.control.debug.AppLogger
import com.mg4.control.model.ImmersiveCleanup
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
 * Un passage au démarrage du service, à chaque changement et quand MG4Dash est installé, mis à
 * jour ou désinstallé ([watch]) ; une relecture unique 5 s après une écriture (pas de
 * lecture-écriture atomique dans Settings). Journal [TAG].
 */
object ImmersiveApps {

    private const val TAG = "MG4_IMMERSIF"
    private const val PREFS = "mg4_settings"
    const val KEY_ON = "immersive_apps_on"
    /** Choix de l'utilisateur, au format des clés communes (`paquet`, `paquet/status`…). */
    const val KEY_APPS = "immersive_apps"
    private const val RELECTURE_MS = 5_000L

    /** Première version de MG4Dash qui applique la règle commune v2 (1.7.3 beta 24). */
    private const val MG4DASH_FUSION_VERSION = 292L

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
        return if (version >= MG4DASH_FUSION_VERSION) Mg4Dash.OK else Mg4Dash.OUTDATED
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

    private var surveillance: BroadcastReceiver? = null

    /**
     * MG4Dash installé, mis à jour ou désinstallé : un passage (l'option sort de pause, ses paquets
     * partent avec lui). Enregistré par le service, le temps de sa vie.
     */
    fun watch(ctx: Context) {
        if (surveillance != null) return
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                if (i.data?.schemeSpecificPart != ImmersivePolicy.MG4DASH) return
                // Une mise à jour envoie REMOVED (remplacement) puis ADDED et REPLACED : un seul passage.
                if (i.getBooleanExtra(Intent.EXTRA_REPLACING, false) && i.action != Intent.ACTION_PACKAGE_REPLACED) return
                val quoi = when (i.action) {
                    Intent.ACTION_PACKAGE_REPLACED -> "MG4Dash mis à jour"
                    Intent.ACTION_PACKAGE_REMOVED -> "MG4Dash désinstallé"
                    else -> "MG4Dash installé"
                }
                refresh(c, quoi)
            }
        }
        val f = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addDataScheme("package")
        }
        runCatching { ctx.applicationContext.registerReceiver(r, f) }
            .onSuccess { surveillance = r }
            .onFailure { AppLogger.w(TAG, "surveillance de MG4Dash impossible : ${it.message}") }
    }

    fun unwatch(ctx: Context) {
        surveillance?.let { r -> runCatching { ctx.applicationContext.unregisterReceiver(r) } }
        surveillance = null
    }

    /** Un passage, sur son fil : démarrage du service, option ou choix changés, MG4Dash changé. */
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
            val avant = global(app, ImmersivePolicy.SETTING)
            // Devant un MG4Dash qui ne suit pas encore la règle (pause, ou option coupée), la règle
            // commune n'est pas appliquée : la valeur peut être la sienne (revue du 2026-11-15).
            if (dash == Mg4Dash.OUTDATED) {
                nettoyer(app, raison, relire, self, maCle, avant, cles, installes, pause)
                return
            }
            // Rien à ajouter, rien à nous à retirer : on ne touche pas à la valeur, même pour la
            // ranger — elle peut être celle d'une autre appli (revue du 2026-11-15).
            var geres = global(app, ImmersivePolicy.MANAGED)
            if (!ImmersivePolicy.hasStake(self, voulus, avant, cles, installes, geres)) {
                AppLogger.i(TAG, "$raison : rien à faire$pause")
                return
            }
            val p = ImmersivePolicy.plan(self, voulus, avant, cles, installes, geres)

            // Une écriture refusée arrête le passage : les clés de suivi restent telles quelles (la
            // nôtre garde anciens + voulus, la commune ce qui a pu être écrit), le passage suivant
            // reprendra (revue du 2026-11-15).
            var ecrit = false
            fun ecrire(nom: String, valeur: String?): Boolean {
                if (put(app, nom, valeur)) { ecrit = true; return true }
                AppLogger.w(TAG, "$raison : écriture de $nom refusée — passage arrêté, clés de suivi gardées")
                if (relire) fil.postDelayed({ passage(app, "nouvel essai", relire = false) }, RELECTURE_MS)
                return false
            }
            var cle = cles[self]
            if (p.keyBefore != cle) { if (!ecrire(maCle, p.keyBefore)) return; cle = p.keyBefore }
            if (p.managedBefore != geres) { if (!ecrire(ImmersivePolicy.MANAGED, p.managedBefore)) return; geres = p.managedBefore }
            if (p.policyChanged) {
                if (!ecrire(ImmersivePolicy.SETTING, p.policy)) return
                AppLogger.i(TAG, "$raison : policy_control ${avant ?: "(vide)"} → ${p.policy ?: "(vide)"}")
            }
            if (p.keyAfter != cle && !ecrire(maCle, p.keyAfter)) return
            if (p.managedAfter != geres && !ecrire(ImmersivePolicy.MANAGED, p.managedAfter)) return
            for (k in p.clearKeys) if (!ecrire(k, null)) return
            if (!ecrit) AppLogger.i(TAG, "$raison : rien à changer$pause")
            if (ecrit && relire) fil.postDelayed({ passage(app, "relecture", relire = false) }, RELECTURE_MS)
        }.onFailure { AppLogger.w(TAG, "$raison : ${it.javaClass.simpleName} ${it.message}") }
    }

    /**
     * Nettoyage seul ([ImmersiveCleanup]) : les entrées que notre clé identifie partent de la
     * valeur, puis notre clé est effacée. La clé commune n'est pas touchée. Rien sans notre clé.
     */
    private fun nettoyer(
        app: Context, raison: String, relire: Boolean, self: String, maCle: String,
        avant: String?, cles: Map<String, String?>, installes: Set<String>, pause: String,
    ) {
        val nous = cles[self]
        if (nous == null) {
            AppLogger.i(TAG, "$raison : rien à faire$pause (MG4Dash ne suit pas encore la règle commune)")
            return
        }
        val autres = cles.filterKeys { it != self && it in installes }.values
        val apres = ImmersiveCleanup.clean(nous, avant, autres)
        fun refus(nom: String) {
            AppLogger.w(TAG, "$raison : nettoyage, écriture de $nom refusée — notre clé gardée")
            if (relire) fil.postDelayed({ passage(app, "nouvel essai", relire = false) }, RELECTURE_MS)
        }
        if (apres != avant) {
            if (!put(app, ImmersivePolicy.SETTING, apres)) return refus(ImmersivePolicy.SETTING)
            AppLogger.i(TAG, "$raison : nettoyage$pause, policy_control ${avant ?: "(vide)"} → ${apres ?: "(vide)"}")
        }
        if (!put(app, maCle, null)) return refus(maCle)
        AppLogger.i(TAG, "$raison : nettoyage$pause, clé $maCle effacée")
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
