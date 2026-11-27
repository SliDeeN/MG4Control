package com.mg4.control.api

import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.view.ContextThemeWrapper
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.mg4.control.R
import com.mg4.control.debug.AppLogger
import com.mg4.control.ui.AppDialog
import com.mg4.control.util.LocaleHelper
import com.mg4.control.util.ThemeHelper
import java.security.MessageDigest

/**
 * Applications autorisées à utiliser l'API Profils (issue #288, 2026-11-27) — indépendante de l'API
 * externe et de son interrupteur.
 *
 * L'appelant est identifié par la plateforme (nom de paquet de [ProfilesProvider]), jamais par ce
 * qu'il déclare. La première demande d'une application inconnue ouvre une fenêtre Autoriser /
 * Refuser par-dessus l'écran ; la réponse est retenue. À l'autorisation, l'empreinte du certificat
 * de signature est gardée : une autre application installée plus tard sous le même nom (après
 * désinstallation) ne passe pas ([Status.CHANGED]). Réglages avancés › API Profils liste les
 * applications et permet de retirer une réponse (l'application pourra redemander).
 */
object ProfileApiAccess {

    private const val PREFS = "profile_api"
    private const val KEY_APPS = "apps"

    enum class Status { ALLOWED, DENIED, UNKNOWN, CHANGED }

    /** Une réponse retenue : [cert] = SHA-256 du certificat au moment de l'autorisation. */
    data class Entry(val pkg: String, val allowed: Boolean, val cert: String?, val label: String?, val at: Long)

    private val gson = Gson()
    private val main = Handler(Looper.getMainLooper())
    /** Paquet dont la fenêtre est ouverte : une seule à la fois, et pas deux fois la même. */
    @Volatile private var enDemande: String? = null

    /** Décision pure, d'après la réponse retenue et le certificat actuel de l'appelant. */
    fun decide(entry: Entry?, currentCert: String?): Status = when {
        entry == null -> Status.UNKNOWN
        !entry.allowed -> Status.DENIED
        entry.cert != null && entry.cert != currentCert -> Status.CHANGED
        else -> Status.ALLOWED
    }

    fun entries(ctx: Context): List<Entry> = runCatching {
        val json = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_APPS, null) ?: return emptyList()
        gson.fromJson<List<Entry>>(json, object : TypeToken<List<Entry>>() {}.type).orEmpty()
    }.getOrDefault(emptyList())

    private fun save(ctx: Context, list: List<Entry>) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_APPS, gson.toJson(list)).apply()
    }

    fun status(ctx: Context, pkg: String): Status =
        decide(entries(ctx).firstOrNull { it.pkg == pkg }, certOf(ctx, pkg))

    fun record(ctx: Context, pkg: String, allowed: Boolean) {
        val e = Entry(pkg, allowed, if (allowed) certOf(ctx, pkg) else null, label(ctx, pkg), System.currentTimeMillis())
        save(ctx, entries(ctx).filter { it.pkg != pkg } + e)
        AppLogger.i(ExternalApi.LOG_TAG, "API Profils : $pkg ${if (allowed) "autorisée" else "refusée"}")
    }

    /** Oublie la réponse : l'application redemandera à son prochain appel. */
    fun remove(ctx: Context, pkg: String) {
        save(ctx, entries(ctx).filter { it.pkg != pkg })
        AppLogger.i(ExternalApi.LOG_TAG, "API Profils : réponse oubliée pour $pkg")
    }

    /** SHA-256 (hex) du premier certificat de signature de [pkg] ; null si illisible. */
    fun certOf(ctx: Context, pkg: String): String? = runCatching {
        val info = ctx.packageManager.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES)
        val signers = info.signingInfo?.apkContentsSigners ?: return null
        val sig = signers.firstOrNull() ?: return null
        MessageDigest.getInstance("SHA-256").digest(sig.toByteArray()).joinToString("") { "%02x".format(it) }
    }.getOrNull()

    /** Nom affiché de [pkg] ; le nom de paquet s'il est introuvable. */
    fun label(ctx: Context, pkg: String): String = runCatching {
        val pm = ctx.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

    /**
     * Fenêtre Autoriser / Refuser par-dessus l'écran, pour [pkg] inconnu. Une seule à la fois ;
     * il faut répondre (toucher à côté ne ferme pas). Appelable depuis n'importe quel fil.
     */
    fun request(context: Context, pkg: String) {
        val app = context.applicationContext
        main.post {
            if (enDemande != null || status(app, pkg) != Status.UNKNOWN) return@post
            enDemande = pkg
            val themed = ContextThemeWrapper(ThemeHelper.withAppTheme(LocaleHelper.applyLocale(app)), R.style.Theme_MG4Control)
            val nom = label(app, pkg)
            AppLogger.i(ExternalApi.LOG_TAG, "API Profils : $pkg ($nom) demande l'accès — fenêtre affichée")
            runCatching {
                AppDialog.confirmer(themed,
                    titre = themed.getString(R.string.profile_api_request_title, nom),
                    message = themed.getString(R.string.profile_api_request_msg),
                    icone = R.drawable.ic_dialog_question,
                    oui = themed.getString(R.string.profile_api_allow),
                    non = themed.getString(R.string.profile_api_deny),
                    onOui = { enDemande = null; record(app, pkg, true); ProfilesProvider.notifierChangement(app) },
                    onNon = { enDemande = null; record(app, pkg, false) },
                    superpose = true, annulable = false)
            }.onFailure {
                enDemande = null
                AppLogger.w(ExternalApi.LOG_TAG, "API Profils : fenêtre impossible (${it.javaClass.simpleName} ${it.message})")
            }
        }
    }
}
