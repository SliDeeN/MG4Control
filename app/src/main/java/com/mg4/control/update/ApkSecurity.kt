package com.mg4.control.update

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.mg4.hardware.AppLogger
import java.io.File
import java.net.URI
import java.security.MessageDigest

/**
 * Contrôles de sécurité de la chaîne OTA.
 *
 * L'app tourne en `uid.system` : un APK d'origine non vérifiée installé sous cette
 * identité compromet le véhicule. Deux verrous, tous deux en "fail closed" :
 *   1. [ApkUrlPolicy]        — d'où l'APK a le droit de venir.
 *   2. [ApkSignatureVerifier] — que l'APK est bien signé par la même clé que nous.
 */

/** Origines autorisées pour un APK de mise à jour. */
object ApkUrlPolicy {

    private const val TAG = "MG4_UPDATE"

    /**
     * Hôtes autorisés. Les deux domaines `githubusercontent.com` sont les CDN vers
     * lesquels github.com redirige le téléchargement d'un asset de release ; sans eux
     * la redirection est refusée et la mise à jour échoue.
     */
    private val ALLOWED_HOSTS = setOf(
        "github.com",
        "api.github.com",
        "objects.githubusercontent.com",
        "release-assets.githubusercontent.com",
        "gitlab.com"
    )

    /**
     * Vrai si [url] est en https et pointe vers un hôte autorisé.
     *
     * Refuse tout le reste : http (y compris une rétrogradation https -> http en
     * cours de redirection), hôte inconnu, URL non parsable, et les sous-domaines
     * non listés explicitement (`evil-github.com`, `github.com.attacker.net`).
     */
    fun isAllowed(url: String): Boolean {
        val uri = try { URI(url) } catch (_: Exception) { return false }
        if (!uri.scheme.equals("https", ignoreCase = true)) return false
        val host = uri.host?.lowercase() ?: return false
        return host in ALLOWED_HOSTS
    }

    /** Comme [isAllowed], mais journalise le refus — pour les points d'entrée. */
    fun isAllowedLogged(url: String, where: String): Boolean {
        val ok = isAllowed(url)
        if (!ok) AppLogger.w(TAG, "$where : URL d'APK refusée (origine non autorisée) : $url")
        return ok
    }
}

/** Vérifie qu'un APK est signé par la même clé que l'app en cours d'exécution. */
object ApkSignatureVerifier {

    private const val TAG = "MG4_UPDATE"

    /**
     * Compare deux jeux d'empreintes de certificats.
     *
     * Fail closed : un jeu vide (archive illisible, signature absente, API qui a
     * échoué) ne correspond jamais, même face à un autre jeu vide.
     */
    fun certsMatch(archive: Set<String>, installed: Set<String>): Boolean =
        archive.isNotEmpty() && installed.isNotEmpty() && archive == installed

    /**
     * Vrai si [apk] est signé exactement par la même clé que l'app installée.
     * Toute erreur (archive corrompue, API indisponible) renvoie false.
     */
    fun matchesRunningApp(context: Context, apk: File): Boolean {
        val archive = fingerprintsOfArchive(context, apk)
        val installed = fingerprintsOfInstalled(context)
        val ok = certsMatch(archive, installed)
        if (!ok) {
            AppLogger.w(TAG, "Signature de l'APK non conforme — installation refusée " +
                    "(archive=${archive.size} cert(s), installée=${installed.size} cert(s))")
        }
        return ok
    }

    /** Empreintes SHA-256 des certificats signant le fichier APK [apk]. */
    private fun fingerprintsOfArchive(context: Context, apk: File): Set<String> = try {
        @Suppress("DEPRECATION")
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
            PackageManager.GET_SIGNING_CERTIFICATES
        else
            PackageManager.GET_SIGNATURES
        val info = context.packageManager.getPackageArchiveInfo(apk.absolutePath, flags)
        signatureDigests(info)
    } catch (e: Exception) {
        AppLogger.w(TAG, "Lecture de la signature de l'archive impossible : ${e.message}")
        emptySet()
    }

    /** Empreintes SHA-256 des certificats signant l'app en cours d'exécution. */
    private fun fingerprintsOfInstalled(context: Context): Set<String> = try {
        @Suppress("DEPRECATION")
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
            PackageManager.GET_SIGNING_CERTIFICATES
        else
            PackageManager.GET_SIGNATURES
        val info = context.packageManager.getPackageInfo(context.packageName, flags)
        signatureDigests(info)
    } catch (e: Exception) {
        AppLogger.w(TAG, "Lecture de notre propre signature impossible : ${e.message}")
        emptySet()
    }

    private fun signatureDigests(info: android.content.pm.PackageInfo?): Set<String> {
        if (info == null) return emptySet()
        @Suppress("DEPRECATION")
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val signingInfo = info.signingInfo ?: return emptySet()
            if (signingInfo.hasMultipleSigners()) signingInfo.apkContentsSigners
            else signingInfo.signingCertificateHistory
        } else {
            info.signatures
        } ?: return emptySet()

        val md = MessageDigest.getInstance("SHA-256")
        return signatures.mapNotNull { sig ->
            sig?.toByteArray()?.let { md.digest(it).joinToString("") { b -> "%02x".format(b) } }
        }.toSet()
    }
}
