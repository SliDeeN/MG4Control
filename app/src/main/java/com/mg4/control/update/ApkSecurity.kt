package com.mg4.control.update

import android.content.Context
import android.content.pm.PackageManager
import com.mg4.control.debug.AppLogger
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
     * Vrai si [apk] est signé exactement par la même clé que l'app installée — et journalise le
     * verdict dans les deux cas. Toute erreur (archive illisible, API indisponible) renvoie false.
     *
     * Une seule fonction fait la mesure ET la décision : les avoir séparées ferait analyser
     * l'archive et calculer son sha256 deux fois par installation, pour un résultat identique.
     *
     * ⚠️ Le verdict ne repose QUE sur les empreintes de certificats. Ni la taille ni le sha256
     * n'y participent — ils ne servent qu'au log, pour se confronter aux lignes publiées par la
     * CI. Un APK légitime change forcément de poids d'une version à l'autre.
     */
    fun matchesRunningApp(context: Context, apk: File): Boolean {
        val taille = runCatching { apk.length() }.getOrDefault(-1L)
        val empreinte = sha256(apk)
        val archive = fingerprintsOfArchive(context, apk)
        val installed = fingerprintsOfInstalled(context)
        val ok = certsMatch(archive, installed)
        if (ok) {
            AppLogger.i(TAG, "Signature CONFORME — ${apk.name}, $taille octets, sha256=$empreinte")
        } else {
            AppLogger.w(TAG, "Signature NON CONFORME — installation refusée : ${apk.name}, " +
                "$taille octets, sha256=$empreinte, " +
                "archive=${archive.size} cert(s), installee=${installed.size} cert(s)")
            // Les compteurs seuls ne distinguent pas les trois causes réelles : archive illisible,
            // clé réellement différente, ou lecture de notre propre signature en échec.
            AppLogger.w(TAG, "  archive   : ${describe(archive)}")
            AppLogger.w(TAG, "  installee : ${describe(installed)}")
            if (archive.isEmpty()) AppLogger.w(TAG, "  archive=0 cert : archive illisible " +
                "(APK non signé, ou v1 absente) — ce n'est PAS la preuve d'une clé différente")
        }
        return ok
    }

    /** Empreinte du fichier reçu, à confronter à celle publiée par la CI. */
    private fun sha256(f: File): String = try {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input ->
            val buf = ByteArray(8_192)
            var read: Int
            while (input.read(buf).also { read = it } != -1) md.update(buf, 0, read)
        }
        md.digest().joinToString("") { "%02x".format(it) }
    } catch (e: Exception) {
        "(illisible: ${e.message})"
    }

    /** Empreintes tronquées, suffisantes pour identifier une clé dans un log. */
    private fun describe(certs: Set<String>): String =
        if (certs.isEmpty()) "(aucune — archive illisible, non signée, ou signée v2 sans v1)"
        else certs.joinToString(", ") { it.take(16) + "…" }

    /** Empreintes SHA-256 des certificats signant le fichier APK [apk]. */
    private fun fingerprintsOfArchive(context: Context, apk: File): Set<String> = try {
        // ⚠️ CAUSE RACINE du « archive=0 cert » observé le 18/08 sur un APK pourtant complet et
        // signé v1+v2+v3 : sur API 28, getPackageArchiveInfo() ne renseigne PAS `signingInfo`
        // pour une ARCHIVE — seul le champ déprécié `signatures` l'est, et uniquement si
        // GET_SIGNATURES est demandé. Ne demander que GET_SIGNING_CERTIFICATES rendait donc
        // zéro certificat, alors que la même lecture sur le paquet INSTALLÉ fonctionnait : d'où
        // le « archive=0, installee=1 » qui a fait accuser tour à tour la clé puis le réseau.
        // On demande les DEUX drapeaux et [signatureDigests] prend celui qui est rempli.
        // minSdk 28 (= P) : GET_SIGNING_CERTIFICATES est toujours disponible.
        @Suppress("DEPRECATION")
        val flags = PackageManager.GET_SIGNATURES or PackageManager.GET_SIGNING_CERTIFICATES
        val info = context.packageManager.getPackageArchiveInfo(apk.absolutePath, flags)
        signatureDigests(info)
    } catch (e: Exception) {
        AppLogger.w(TAG, "Lecture de la signature de l'archive impossible : ${e.message}")
        emptySet()
    }

    /** Empreintes SHA-256 des certificats signant l'app en cours d'exécution. */
    private fun fingerprintsOfInstalled(context: Context): Set<String> = try {
        // minSdk 28 (= P) : la branche GET_SIGNATURES n'était jamais prise.
        val flags = PackageManager.GET_SIGNING_CERTIFICATES
        val info = context.packageManager.getPackageInfo(context.packageName, flags)
        signatureDigests(info)
    } catch (e: Exception) {
        AppLogger.w(TAG, "Lecture de notre propre signature impossible : ${e.message}")
        emptySet()
    }

    private fun signatureDigests(info: android.content.pm.PackageInfo?): Set<String> {
        if (info == null) return emptySet()
        // minSdk 28 (= P) : signingInfo existe toujours — il peut seulement être null (archive).
        val moderne = info.signingInfo?.let { si ->
            if (si.hasMultipleSigners()) si.apkContentsSigners else si.signingCertificateHistory
        }
        // Repli volontaire sur l'API dépréciée : pour une archive sur API 28, `signatures` est le
        // SEUL champ renseigné. Ne pas « moderniser » ce repli sans l'avoir testé sur véhicule.
        @Suppress("DEPRECATION")
        val signatures = moderne ?: info.signatures ?: return emptySet()

        val md = MessageDigest.getInstance("SHA-256")
        return signatures.mapNotNull { sig ->
            sig?.toByteArray()?.let { md.digest(it).joinToString("") { b -> "%02x".format(b) } }
        }.toSet()
    }
}
