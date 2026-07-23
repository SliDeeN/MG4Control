package com.mg4.control.update

import android.content.Context
import androidx.annotation.VisibleForTesting
import com.mg4.hardware.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Vérifie si une version plus récente est disponible.
 * Stratégie : GitHub en priorité, GitLab en fallback si GitHub est inaccessible
 * (timeout, erreur réseau, rate-limit, repo privé, etc.).
 */
object UpdateChecker {

    private const val TAG = "MG4_UPDATE"

    private const val GITHUB_API_URL =
        "https://api.github.com/repos/SliDeeN/MG4Control/releases/latest"

    private const val GITLAB_API_URL =
        "https://gitlab.com/api/v4/projects/SliDeeN%2Fmg4control/releases/permalink/latest"

    private const val PREFS_SKIP       = "mg4_update_skip"
    private const val KEY_SKIP_VERSION = "skip_version"

    // -------------------------------------------------------------------------
    // Données brutes extraites depuis GitHub ou GitLab
    // -------------------------------------------------------------------------

    private data class RawRelease(
        val tagName: String,
        val apkUrl: String,
        val releaseNotes: String,
        val source: String          // "GitHub" ou "GitLab" — pour les logs
    )

    // -------------------------------------------------------------------------
    // Point d'entrée public
    // -------------------------------------------------------------------------

    fun check(
        context: Context,
        onUpdateAvailable: (UpdateInfo) -> Unit,
        onNoUpdate: (() -> Unit)? = null,
        onError: (() -> Unit)? = null
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val currentVersion = context.packageManager
                    .getPackageInfo(context.packageName, 0).versionName
                    ?: return@launch

                val skippedVersion = context
                    .getSharedPreferences(PREFS_SKIP, Context.MODE_PRIVATE)
                    .getString(KEY_SKIP_VERSION, null)

                // Essai GitHub → fallback GitLab
                val release = fetchFromGitHub()
                    ?: fetchFromGitLab()
                    ?: run {
                        AppLogger.w(TAG, "GitHub et GitLab inaccessibles")
                        withContext(Dispatchers.Main) { onError?.invoke() }
                        return@launch
                    }

                AppLogger.i(TAG, "Release récupérée depuis ${release.source} : ${release.tagName}")

                val versionName = release.tagName.trimStart('v')

                if (isNewer(versionName, currentVersion)) {
                    if (versionName == skippedVersion) {
                        AppLogger.i(TAG, "Mise à jour $versionName ignorée (utilisateur)")
                        withContext(Dispatchers.Main) { onNoUpdate?.invoke() }
                        return@launch
                    }
                    val info = UpdateInfo(
                        versionName, release.tagName, release.apkUrl,
                        release.releaseNotes
                    )
                    withContext(Dispatchers.Main) { onUpdateAvailable(info) }
                } else {
                    AppLogger.i(TAG, "Version actuelle ($currentVersion) à jour — pas de mise à jour")
                    withContext(Dispatchers.Main) { onNoUpdate?.invoke() }
                }

            } catch (_: Exception) {
                withContext(Dispatchers.Main) { onError?.invoke() }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Requête GitHub
    // -------------------------------------------------------------------------

    private fun fetchFromGitHub(): RawRelease? {
        return try {
            val conn = (URL(GITHUB_API_URL).openConnection() as HttpURLConnection).apply {
                setRequestProperty("Accept", "application/vnd.github.v3+json")
                setRequestProperty("User-Agent", "MG4Control-Android")
                connectTimeout = 10_000
                readTimeout    = 10_000
            }
            if (conn.responseCode != 200) {
                AppLogger.w(TAG, "GitHub réponse ${conn.responseCode} → fallback GitLab")
                conn.disconnect()
                return null
            }
            val json = JSONObject(conn.inputStream.bufferedReader().readText())
            conn.disconnect()

            val tagName = json.getString("tag_name")
            val notes   = json.optString("body", "").take(400)
            val assets  = json.optJSONArray("assets") ?: return null
            val apks = mutableListOf<Pair<String, String>>()
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                val name  = asset.getString("name")
                if (name.endsWith(".apk", ignoreCase = true)) {
                    apks += name to asset.getString("browser_download_url")
                }
            }
            val apkUrl = selectApk(apks)
            if (apkUrl.isEmpty()) {
                AppLogger.w(TAG, "GitHub : aucun asset .apk trouvé → fallback GitLab")
                return null
            }
            // L'URL vient d'un JSON distant : elle n'est pas de confiance tant qu'elle
            // n'a pas été confrontée à la liste d'origines autorisées.
            if (!ApkUrlPolicy.isAllowedLogged(apkUrl, "GitHub")) return null
            RawRelease(tagName, apkUrl, notes, "GitHub")

        } catch (e: Exception) {
            AppLogger.w(TAG, "GitHub inaccessible : ${e.message} → fallback GitLab")
            null
        }
    }

    // -------------------------------------------------------------------------
    // Requête GitLab (fallback)
    // -------------------------------------------------------------------------

    private fun fetchFromGitLab(): RawRelease? {
        return try {
            val conn = (URL(GITLAB_API_URL).openConnection() as HttpURLConnection).apply {
                setRequestProperty("User-Agent", "MG4Control-Android")
                connectTimeout = 10_000
                readTimeout    = 10_000
            }
            if (conn.responseCode != 200) {
                AppLogger.w(TAG, "GitLab réponse ${conn.responseCode}")
                conn.disconnect()
                return null
            }
            val json = JSONObject(conn.inputStream.bufferedReader().readText())
            conn.disconnect()

            val tagName = json.getString("tag_name")
            val notes   = json.optString("description", "").take(400)

            // Structure GitLab : assets.links[] (contrairement à assets[] sur GitHub)
            // Le champ "name" est un libellé libre (ex: "MG4Control v1.2.0") — pas forcément
            // le nom de fichier. On vérifie donc aussi l'URL elle-même en fallback.
            val links  = json.optJSONObject("assets")?.optJSONArray("links") ?: return null
            val apks = mutableListOf<Pair<String, String>>()
            for (i in 0 until links.length()) {
                val link      = links.getJSONObject(i)
                val linkName  = link.optString("name")
                val linkUrl   = link.optString("direct_asset_url").ifEmpty {
                    link.optString("url")
                }
                if (linkName.endsWith(".apk", ignoreCase = true)
                    || linkUrl.contains(".apk", ignoreCase = true)) {
                    // Le "name" GitLab est un libellé libre : on combine name + url pour
                    // que la détection du flavor "online" fonctionne dans les deux cas.
                    apks += "$linkName $linkUrl" to linkUrl
                }
            }
            val apkUrl = selectApk(apks)
            if (apkUrl.isEmpty()) {
                AppLogger.w(TAG, "GitLab : aucun asset .apk trouvé dans les links")
                return null
            }
            if (!ApkUrlPolicy.isAllowedLogged(apkUrl, "GitLab")) return null
            RawRelease(tagName, apkUrl, notes, "GitLab")

        } catch (e: Exception) {
            AppLogger.w(TAG, "GitLab inaccessible : ${e.message}")
            null
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Sélectionne l'APK à télécharger parmi les assets `.apk` d'une release.
     *
     * Règle de gestion (releases multi-flavor online/offline) :
     *  - 0 APK   → chaîne vide (déclenche le fallback / l'erreur).
     *  - 1 APK   → on le prend (compat. anciennes releases mono-APK).
     *  - 2+ APK  → on prend celui dont le nom contient "online" (le flavor réseau,
     *              seul à gérer l'OTA). Si aucun ne matche, on prend le premier.
     *
     * Chaque élément est une paire (libellé pour la détection, URL de téléchargement).
     */
    private fun selectApk(apks: List<Pair<String, String>>): String {
        if (apks.isEmpty()) return ""
        if (apks.size == 1) return apks[0].second

        val online = apks.firstOrNull { it.first.contains("online", ignoreCase = true) }
        if (online != null) {
            AppLogger.i(TAG, "${apks.size} APK trouvés → sélection 'online' : ${online.first}")
            return online.second
        }
        AppLogger.w(TAG, "${apks.size} APK trouvés mais aucun 'online' → premier par défaut : ${apks[0].first}")
        return apks[0].second
    }

    /**
     * Sauvegarde la version [version] comme "à ne plus rappeler".
     */
    fun skipVersion(context: Context, version: String) {
        context.getSharedPreferences(PREFS_SKIP, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_SKIP_VERSION, version)
            .apply()
    }

    /**
     * Retourne true si [remote] est une version supérieure à [current].
     *
     * Comparaison sur le seul cœur numérique : les suffixes de build ("2.6.4-offline",
     * "2.7.0-rc1") sont retirés avant comparaison. L'implémentation précédente utilisait
     * mapNotNull { toIntOrNull() }, qui SUPPRIMAIT le segment mal formé — "2.6.4-offline"
     * donnait [2, 6], donc toute release distante paraissait plus récente. Ce n'était
     * masqué que parce que le flavor offline n'a pas la permission INTERNET.
     *
     * Deux versions dont les cœurs numériques sont égaux ne sont jamais "plus récentes"
     * l'une que l'autre : "2.7.0-offline" == "2.7.0".
     */
    @VisibleForTesting
    internal fun isNewer(remote: String, current: String): Boolean {
        val r = segments(remote)
        val c = segments(current)
        for (i in 0 until maxOf(r.size, c.size)) {
            val rv = r.getOrElse(i) { 0 }
            val cv = c.getOrElse(i) { 0 }
            if (rv > cv) return true
            if (rv < cv) return false
        }
        return false
    }

    /**
     * Cœur numérique d'une version : "v2.6.4-offline" → [2, 6, 4].
     *
     * Le préfixe "v" et tout ce qui suit le premier caractère non numérique d'un segment
     * sont ignorés. Un segment sans aucun chiffre vaut 0 — il n'est PAS supprimé, sinon
     * les segments suivants remonteraient d'un rang et fausseraient la comparaison.
     */
    @VisibleForTesting
    internal fun segments(version: String): List<Int> =
        version.trimStart('v', 'V')
            .substringBefore('+')
            .substringBefore('-')
            .split('.')
            .map { part -> part.takeWhile { it.isDigit() }.toIntOrNull() ?: 0 }

}
