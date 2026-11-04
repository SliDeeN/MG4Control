package com.mg4.control.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Process
import com.mg4.control.debug.AppLogger
import java.io.File

/**
 * Installation de la mise à jour SANS l'installeur d'Android (2026-11-04), comme DiAuto-MG4 et
 * MG4Dash sur la voiture : MG4Control tourne sous l'uid système et détient INSTALL_PACKAGES (variante
 * online), une session d'installation suffit donc, sans fenêtre de confirmation.
 *
 * Le détail qui débloque tout sur la MG4 : le drapeau caché `INSTALL_INTERNAL` (celui de
 * `pm install -f`) — sans lui, la voiture refuse une appli non système dans la mémoire interne.
 * Repli sur `pm install -r -f` si la session ne peut pas être créée.
 *
 * Android vérifie lui-même que la signature est la même ; [ApkSignatureVerifier] l'a vérifiée
 * avant. L'installation tue MG4Control : [com.mg4.control.receiver.PackageReplacedReceiver]
 * relance le service, et l'écran si la mise à jour a été lancée depuis lui. En cas d'échec,
 * [InstallResultReceiver] revient à l'ancienne voie (dossier Téléchargements).
 */
object SilentInstaller {

    private const val TAG = "MG4_UPDATE"
    /** PackageManager.INSTALL_INTERNAL, caché. */
    private const val INSTALL_INTERNAL = 0x00000010
    const val ACTION_RESULT = "com.mg4.control.UPDATE_INSTALL_RESULT"

    private const val PREFS = "mg4_update"
    private const val KEY_DEPUIS_ECRAN = "update_from_screen_at"
    /** Au-delà, la relance n'est plus celle d'une mise à jour lancée depuis l'écran. */
    private const val FENETRE_MS = 10 * 60_000L

    /** Vrai si l'appli peut installer elle-même (uid système, ou permission accordée). */
    fun possible(ctx: Context): Boolean =
        Process.myUid() == Process.SYSTEM_UID ||
            ctx.checkSelfPermission(android.Manifest.permission.INSTALL_PACKAGES) == PackageManager.PERMISSION_GRANTED

    /**
     * Lance l'installation de [apk]. Fil de travail. Lève une exception si rien n'a pu être lancé
     * (APK refusé, session et `pm` en échec) : l'appelant revient alors à l'installation manuelle.
     */
    fun installer(ctx: Context, apk: File) {
        verifier(ctx, apk)
        marquer(ctx)
        try {
            parSession(ctx, apk)
            AppLogger.i(TAG, "session d'installation envoyée (${apk.length()} octets)")
            return
        } catch (t: Throwable) {
            AppLogger.w(TAG, "session d'installation en échec : ${t.javaClass.simpleName} ${t.message} → pm install")
        }
        try {
            parPm(apk)
        } catch (e: Exception) {
            oublier(ctx)
            throw e
        }
    }

    /**
     * Même paquet, et pas de retour en arrière : versionCode plus grand, ou égal avec un autre
     * versionName — les betas d'une même version partagent leur versionCode (2.7.0-beta.1, -beta.2…),
     * c'est [UpdateChecker] qui a jugé la nouveauté d'après le nom.
     */
    private fun verifier(ctx: Context, apk: File) {
        val pm = ctx.packageManager
        val info = pm.getPackageArchiveInfo(apk.absolutePath, 0) ?: error("APK illisible")
        require(info.packageName == ctx.packageName) { "APK d'un autre paquet : ${info.packageName}" }
        val actuelle = pm.getPackageInfo(ctx.packageName, 0)
        val code = info.longVersionCode
        require(code > actuelle.longVersionCode || (code == actuelle.longVersionCode && info.versionName != actuelle.versionName)) {
            "version ${info.versionName} ($code) pas plus récente que ${actuelle.versionName} (${actuelle.longVersionCode})"
        }
        AppLogger.i(TAG, "APK accepté : ${actuelle.versionName} → ${info.versionName} (versionCode $code)")
    }

    private fun parSession(ctx: Context, apk: File) {
        val installeur = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(ctx.packageName)
            setSize(apk.length())
            setInstallLocation(PackageInfo.INSTALL_LOCATION_INTERNAL_ONLY)
        }
        runCatching {
            val champ = PackageInstaller.SessionParams::class.java.getField("installFlags")
            champ.setInt(params, champ.getInt(params) or INSTALL_INTERNAL)
        }.onFailure { AppLogger.w(TAG, "drapeau INSTALL_INTERNAL non posé : ${it.message}") }
        val id = installeur.createSession(params)
        var session: PackageInstaller.Session? = installeur.openSession(id)
        try {
            apk.inputStream().use { entree ->
                session!!.openWrite("base.apk", 0, apk.length()).use { sortie ->
                    entree.copyTo(sortie, 64 * 1024)
                    session!!.fsync(sortie)
                }
            }
            val retour = Intent(ctx, InstallResultReceiver::class.java).setAction(ACTION_RESULT)
            // Modifiable : l'installeur y ajoute le statut. (Android 9 sur la voiture ; le drapeau
            // n'est exigé qu'à partir d'Android 12.)
            val drapeaux = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (android.os.Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val pi = PendingIntent.getBroadcast(ctx, id, retour, drapeaux)
            session!!.commit(pi.intentSender)
            session = null   // la session appartient désormais à l'installeur
        } finally {
            session?.runCatching { abandon() }
        }
    }

    private fun parPm(apk: File) {
        val p = ProcessBuilder("pm", "install", "-r", "-f", apk.absolutePath).redirectErrorStream(true).start()
        val sortie = p.inputStream.bufferedReader().readText().trim()
        val code = p.waitFor()
        AppLogger.i(TAG, "pm install → code $code : $sortie")
        check(code == 0 && sortie.contains("success", ignoreCase = true)) { sortie.ifEmpty { "pm install code $code" } }
    }

    /** Mise à jour lancée depuis l'écran : il sera rouvert après la relance. */
    private fun marquer(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong(KEY_DEPUIS_ECRAN, System.currentTimeMillis()).commit()
    }

    fun oublier(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_DEPUIS_ECRAN).apply()
    }

    /** Vrai (une seule fois) si la mise à jour qui vient de s'installer a été lancée depuis l'écran. */
    fun lanceeDepuisEcran(ctx: Context): Boolean {
        val quand = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_DEPUIS_ECRAN, 0L)
        oublier(ctx)
        return quand > 0L && System.currentTimeMillis() - quand in 0..FENETRE_MS
    }
}
