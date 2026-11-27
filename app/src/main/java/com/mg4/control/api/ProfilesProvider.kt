package com.mg4.control.api

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import com.mg4.control.debug.AppLogger
import com.mg4.control.hardware.MG4Hardware
import com.mg4.control.profile.ActiveProfile
import com.mg4.control.profile.ProfileApplier
import com.mg4.control.profile.ProfileManager

/**
 * API Profils (issue #288, 2026-11-27) — `content://<applicationId>.profiles/profiles`, pour les
 * applications de confiance (un launcher, par exemple). Indépendante de l'API externe : elle marche
 * même son interrupteur coupé, mais SEULEMENT pour les applications autorisées ([ProfileApiAccess]).
 *
 * - `query` : une ligne par profil, dans l'ordre de l'application — id, name, drive_mode, active,
 *   default, position. `null` si l'appelant n'est pas autorisé.
 * - `call("status")` : « allowed », « denied », « pending » (fenêtre affichée) ou « changed »
 *   (signature différente de celle autorisée). Une application inconnue déclenche la fenêtre
 *   Autoriser / Refuser.
 * - `call("apply", "<id ou nom>")` : applique le profil (comme le sélecteur) ; réponse immédiate
 *   `ok` + `profile`, ou `ok=false` + `error` (not_allowed, unknown_profile, service_not_running).
 * - Changements (profil appliqué, profils modifiés) : `notifyChange` sur l'URI, pour un ContentObserver.
 *
 * Lecture et application de profils seulement : aucune autre commande ne passe par ici. Toute
 * demande est journalisée avec le nom de l'appelant (tag MG4_API).
 */
class ProfilesProvider : ContentProvider() {

    companion object {
        const val PATH = "profiles"
        val COLUMNS = arrayOf("id", "name", "drive_mode", "active", "default", "position")
        const val METHOD_STATUS = "status"
        const val METHOD_APPLY = "apply"
        const val KEY_STATUS = "status"
        const val KEY_OK = "ok"
        const val KEY_ERROR = "error"
        const val KEY_PROFILE = "profile"

        fun uri(ctx: Context): Uri = Uri.parse("content://${ctx.packageName}.profiles/$PATH")

        /** Prévient les observateurs (profil appliqué, liste modifiée). Sans effet si rien n'écoute. */
        fun notifierChangement(ctx: Context?) {
            ctx ?: return
            runCatching { ctx.contentResolver.notifyChange(uri(ctx), null) }
        }
    }

    override fun onCreate(): Boolean = true

    /** Le nom de paquet donné par la plateforme ; à défaut, celui de l'uid appelant. */
    private fun appelant(ctx: Context): String? = callingPackage
        ?: ctx.packageManager.getPackagesForUid(Binder.getCallingUid())?.firstOrNull()

    /** Statut de l'appelant ; inconnu → la fenêtre Autoriser / Refuser s'ouvre. */
    private fun verifier(ctx: Context, pkg: String?, quoi: String): ProfileApiAccess.Status {
        if (pkg == null) {
            AppLogger.w(ExternalApi.LOG_TAG, "API Profils : $quoi refusé — appelant inconnu")
            return ProfileApiAccess.Status.DENIED
        }
        val st = ProfileApiAccess.status(ctx, pkg)
        if (st == ProfileApiAccess.Status.UNKNOWN) ProfileApiAccess.request(ctx, pkg)
        if (st != ProfileApiAccess.Status.ALLOWED) AppLogger.w(ExternalApi.LOG_TAG, "API Profils : $quoi refusé à $pkg ($st)")
        return st
    }

    override fun query(
        uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?
    ): Cursor? {
        val ctx = context ?: return null
        val pkg = appelant(ctx)
        if (verifier(ctx, pkg, "lecture") != ProfileApiAccess.Status.ALLOWED) return null
        val pm = ProfileManager(ctx)
        val actif = ActiveProfile.id(ctx)
        val defaut = pm.getDefaultId()
        val cursor = MatrixCursor(COLUMNS)
        pm.getAll().forEachIndexed { i, p ->
            cursor.addRow(arrayOf<Any?>(p.id, p.name, p.driveMode.name, if (p.id == actif) 1 else 0, if (p.id == defaut) 1 else 0, i))
        }
        cursor.setNotificationUri(ctx.contentResolver, uri(ctx))
        AppLogger.i(ExternalApi.LOG_TAG, "API Profils : lecture par $pkg (${cursor.count} profils)")
        return cursor
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        val ctx = context ?: return null
        val pkg = appelant(ctx)
        return when (method) {
            METHOD_STATUS -> Bundle().apply {
                putString(KEY_STATUS, when (verifier(ctx, pkg, "statut")) {
                    ProfileApiAccess.Status.ALLOWED -> "allowed"
                    ProfileApiAccess.Status.DENIED -> "denied"
                    ProfileApiAccess.Status.CHANGED -> "changed"
                    ProfileApiAccess.Status.UNKNOWN -> "pending"
                })
            }
            METHOD_APPLY -> appliquer(ctx, pkg, arg?.trim().orEmpty())
            else -> Bundle().apply { putBoolean(KEY_OK, false); putString(KEY_ERROR, "unknown_method") }
        }
    }

    private fun appliquer(ctx: Context, pkg: String?, cible: String): Bundle {
        fun refus(e: String) = Bundle().apply { putBoolean(KEY_OK, false); putString(KEY_ERROR, e) }
        if (verifier(ctx, pkg, "application") != ProfileApiAccess.Status.ALLOWED) return refus("not_allowed")
        val profils = ProfileManager(ctx).getAll()
        val p = profils.firstOrNull { it.id == cible } ?: profils.firstOrNull { it.name.equals(cible, ignoreCase = true) }
            ?: return refus("unknown_profile").also { AppLogger.w(ExternalApi.LOG_TAG, "API Profils : profil « $cible » introuvable ($pkg)") }
        // Le service de MG4Control tourne dès le démarrage ; sans lui, rien n'écrit dans la voiture.
        if (MG4Hardware.appContext() == null) return refus("service_not_running")
        AppLogger.i(ExternalApi.LOG_TAG, "API Profils : « ${p.name} » appliqué par $pkg")
        ProfileApplier.apply(p)
        return Bundle().apply { putBoolean(KEY_OK, true); putString(KEY_PROFILE, p.id) }
    }

    override fun getType(uri: Uri): String = "vnd.android.cursor.dir/vnd.${context?.packageName}.$PATH"

    // Aucune écriture par les méthodes génériques : seulement `call("apply")`, contrôlé.
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
