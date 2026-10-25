package com.mg4.control.service

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import android.widget.Toast
import com.mg4.control.BuildConfig
import com.mg4.control.R
import com.mg4.control.debug.AppLogger
import com.mg4.control.model.CustomKind
import com.mg4.control.model.CustomShortcut
import com.mg4.control.model.CustomShortcuts
import com.mg4.control.util.CustomShortcutSettings
import com.mg4.control.util.LocaleHelper
import java.net.HttpURLConnection
import java.net.URL

/**
 * Raccourcis perso (issue #172, 2026-10-25) : les lancer, et trouver les fonctions que les autres
 * applis proposent.
 *
 * Un lancement ne dit rien quand il réussit (le bouton flottant a son halo, l'appli s'ouvre) —
 * sauf le webhook, qui donne la réponse du serveur ; un échec est toujours dit. Une diffusion part
 * sans accusé de réception : on sait qu'elle est envoyée, pas que l'appli a agi.
 *
 * Sécurité : MG4Control a les droits du système, que garde toute diffusion qu'il envoie. D'où les
 * refus de [CustomShortcuts.problem] — rejoués ici à chaque lancement, au cas où une appli visée
 * serait devenue appli système depuis l'enregistrement.
 */
object CustomShortcutRunner {

    const val TAG = "MG4_CUSTOM"
    private const val DELAI_RESEAU_MS = 8_000

    private val main = Handler(Looper.getMainLooper())
    private val reseau: Handler by lazy { Handler(HandlerThread("mg4-custom").also { it.start() }.looper) }
    private var message: Toast? = null

    data class Resultat(val ok: Boolean, val texte: String)

    /** Bouton du volant, raccourci avancé ou bouton flottant : le raccourci perso [id]. */
    fun run(ctx: Context, id: String?) {
        val app = ctx.applicationContext
        val c = CustomShortcutSettings.get(app, id)
        if (c == null) {
            AppLogger.w(TAG, "raccourci perso introuvable (id=$id)")
            annoncer(app, LocaleHelper.applyLocale(app).getString(R.string.custom_missing))
            return
        }
        executer(app, c) { r ->
            if (!r.ok || CustomShortcuts.kindOf(c) == CustomKind.WEBHOOK) annoncer(app, r.texte)
        }
    }

    /** Lance [c], enregistré ou non (« Tester ») ; [fin] reçoit le résultat, sur le fil principal. */
    fun executer(ctx: Context, c: CustomShortcut, fin: (Resultat) -> Unit) {
        val loc = LocaleHelper.applyLocale(ctx)
        fun rendre(ok: Boolean, texte: String) = main.post { fin(Resultat(ok, texte)) }
        val probleme = CustomShortcuts.problem(c) { estAppliUtilisateur(ctx, it) }
        if (probleme != null) {
            AppLogger.w(TAG, "« ${c.name} » refusé : $probleme")
            rendre(false, texteProbleme(loc, probleme))
            return
        }
        val kind = CustomShortcuts.kindOf(c) ?: return
        if (kind == CustomKind.WEBHOOK) {
            if (BuildConfig.OFFLINE) {
                rendre(false, loc.getString(R.string.custom_webhook_offline))
                return
            }
            reseau.post {
                val r = appeler(c)
                AppLogger.i(TAG, "« ${c.name} » webhook ${CustomShortcuts.methodOf(c)} ${CustomShortcuts.maskUrl(c.url)} → " +
                    (r.first?.toString() ?: "erreur ${r.second}"))
                val code = r.first
                if (code != null) rendre(code in 200..299, loc.getString(R.string.custom_webhook_result, c.name, code))
                else rendre(false, loc.getString(R.string.custom_failed, c.name, r.second ?: "?"))
            }
            return
        }
        val erreur = runCatching {
            when (kind) {
                CustomKind.APP -> {
                    val i = ctx.packageManager.getLaunchIntentForPackage(c.packageName!!)
                        ?: error(loc.getString(R.string.custom_app_missing))
                    ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
                CustomKind.APP_SHORTCUT -> (ctx.getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps)
                    .startShortcut(c.packageName!!, c.shortcutId!!, null, null, Process.myUserHandle())
                CustomKind.FUNCTION -> ctx.sendBroadcast(Intent(c.action!!.trim())
                    .setComponent(ComponentName(c.packageName!!, c.className!!)))
                CustomKind.BROADCAST -> ctx.sendBroadcast(Intent(c.action!!.trim()).setPackage(c.packageName!!).apply {
                    CustomShortcuts.parseExtras(c.extras).forEach { (k, v) -> putExtra(k, v) }
                })
                CustomKind.LINK -> ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(c.uri!!.trim()))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                CustomKind.WEBHOOK -> Unit
            }
        }.exceptionOrNull()
        if (erreur == null) {
            AppLogger.i(TAG, "« ${c.name} » lancé (${kind.key}${c.packageName?.let { " → $it" } ?: ""})")
            rendre(true, loc.getString(R.string.custom_done, c.name))
        } else {
            val raison = if (erreur is android.content.ActivityNotFoundException) loc.getString(R.string.custom_no_app_for_link)
                else erreur.message ?: erreur.javaClass.simpleName
            AppLogger.w(TAG, "« ${c.name} » en échec : ${erreur.javaClass.simpleName} ${erreur.message}")
            rendre(false, loc.getString(R.string.custom_failed, c.name, raison))
        }
    }

    /** Code HTTP, ou `null` et la raison. Fil réseau. */
    private fun appeler(c: CustomShortcut): Pair<Int?, String?> = runCatching {
        val methode = CustomShortcuts.methodOf(c)
        val cx = (URL(c.url!!.trim()).openConnection() as HttpURLConnection).apply {
            requestMethod = methode
            connectTimeout = DELAI_RESEAU_MS
            readTimeout = DELAI_RESEAU_MS
            instanceFollowRedirects = true
        }
        try {
            val corps = c.body
            if (methode == "POST" && !corps.isNullOrEmpty()) {
                cx.doOutput = true
                cx.setRequestProperty("Content-Type", CustomShortcuts.contentType(corps))
                cx.outputStream.use { it.write(corps.toByteArray(Charsets.UTF_8)) }
            }
            cx.responseCode to null
        } finally {
            cx.disconnect()
        }
    }.getOrElse { null to (it.message ?: it.javaClass.simpleName) }

    /** Une appli installée par l'utilisateur : ni appli système, ni MG4Control. */
    fun estAppliUtilisateur(ctx: Context, paquet: String): Boolean = runCatching {
        if (paquet == ctx.packageName) return false
        val info = ctx.packageManager.getApplicationInfo(paquet, 0)
        info.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0
    }.getOrDefault(false)

    fun texteProbleme(ctx: Context, p: CustomShortcuts.Problem): String = ctx.getString(when (p) {
        CustomShortcuts.Problem.NAME -> R.string.custom_problem_name
        CustomShortcuts.Problem.TARGET -> R.string.custom_problem_target
        CustomShortcuts.Problem.TARGET_SYSTEM -> R.string.custom_problem_target_system
        CustomShortcuts.Problem.ACTION -> R.string.custom_problem_action
        CustomShortcuts.Problem.ACTION_RESERVED -> R.string.custom_problem_action_reserved
        CustomShortcuts.Problem.LINK -> R.string.custom_problem_link
        CustomShortcuts.Problem.URL -> R.string.custom_problem_url
        CustomShortcuts.Problem.KIND -> R.string.custom_problem_kind
    })

    // ── Fonctions proposées par les applis installées ───────────────────────

    /** Une fonction trouvée chez une autre appli : un raccourci qu'elle publie, ou déclarée pour MG4Control. */
    data class Offre(
        val kind: CustomKind,
        val packageName: String,
        val appLabel: String,
        val label: String,
        val className: String? = null,
        val shortcutId: String? = null,
        val action: String? = null,
        val icone: Drawable? = null,
    )

    /** Les fonctions déclarées pour MG4Control d'abord, puis les raccourcis des applis ; triées par appli. */
    fun fonctions(ctx: Context): List<Offre> {
        val declarees = declarees(ctx)
        val raccourcis = raccourcisDesApplis(ctx)
        AppLogger.i(TAG, "fonctions trouvées : ${declarees.size} déclarée(s) pour MG4Control, " +
            "${raccourcis.size} raccourci(s) d'applis (${raccourcis.map { it.appLabel }.distinct().joinToString()})")
        return (declarees + raccourcis).sortedWith(compareBy({ it.appLabel.lowercase() }, { it.label.lowercase() }))
    }

    /** Convention [CustomShortcuts.DISCOVERY_ACTION] : un récepteur par fonction, son label pour nom. */
    private fun declarees(ctx: Context): List<Offre> = runCatching {
        val pm = ctx.packageManager
        pm.queryBroadcastReceivers(Intent(CustomShortcuts.DISCOVERY_ACTION), PackageManager.GET_RESOLVED_FILTER)
            .filter { estAppliUtilisateur(ctx, it.activityInfo.packageName) }
            .flatMap { ri ->
                val filtre = ri.filter ?: return@flatMap emptyList<Offre>()
                val actions = (0 until filtre.countActions()).map { filtre.getAction(it) }
                    .filter { it != CustomShortcuts.DISCOVERY_ACTION && CustomShortcuts.actionAllowed(it) }
                val appli = pm.getApplicationLabel(ri.activityInfo.applicationInfo).toString()
                val nom = ri.loadLabel(pm).toString()
                actions.map { a ->
                    Offre(CustomKind.FUNCTION, ri.activityInfo.packageName, appli,
                        if (actions.size > 1) "$nom · ${a.substringAfterLast('.')}" else nom,
                        className = ri.activityInfo.name, action = a,
                        icone = runCatching { ri.loadIcon(pm) }.getOrNull())
                }
            }
    }.getOrElse {
        AppLogger.w(TAG, "fonctions déclarées illisibles : ${it.javaClass.simpleName} ${it.message}")
        emptyList()
    }

    /**
     * Les raccourcis que les applis publient pour les launchers. Lus avec la permission des
     * launchers (ACCESS_SHORTCUTS, accordée à l'appli système) ; refusés : aucun.
     */
    private fun raccourcisDesApplis(ctx: Context): List<Offre> = runCatching {
        val la = ctx.getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps
        val pm = ctx.packageManager
        val requete = LauncherApps.ShortcutQuery().setQueryFlags(
            LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST or LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC or
                LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED)
        la.getShortcuts(requete, Process.myUserHandle()).orEmpty()
            .filter { it.isEnabled && it.`package` != ctx.packageName }
            .distinctBy { it.`package` to it.id }
            .map { si ->
                val appli = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(si.`package`, 0)).toString() }
                    .getOrDefault(si.`package`)
                Offre(CustomKind.APP_SHORTCUT, si.`package`, appli, (si.shortLabel ?: si.id).toString(),
                    shortcutId = si.id, icone = runCatching { pm.getApplicationIcon(si.`package`) }.getOrNull())
            }
    }.getOrElse {
        AppLogger.w(TAG, "raccourcis des applis illisibles : ${it.javaClass.simpleName} ${it.message}")
        emptyList()
    }

    /** Un seul message à la fois, dans la langue de l'application. */
    private fun annoncer(app: Context, texte: String) {
        main.post {
            val loc = LocaleHelper.applyLocale(app)
            message?.cancel()
            message = runCatching { Toast.makeText(loc, texte, Toast.LENGTH_SHORT).also { it.show() } }.getOrNull()
        }
    }
}
