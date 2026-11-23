package com.mg4.control.backup

import android.content.Context
import android.content.SharedPreferences
import com.mg4.control.debug.AppLogger
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Sauvegarde de tous les réglages dans un fichier (issue #269, 2026-11-23) : à exporter sur une clé
 * USB avant une réinitialisation de l'écran central, à réimporter ensuite.
 *
 * Ce qui n'est jamais dans le fichier (décision du 2026-11-23) :
 * - sensible : comptes ABRP et accord pour la position, raccourcis perso Webhook ;
 * - propre à cette voiture ou à cet instant : valeurs détectées ou apprises, historiques (sauf les
 *   Statistiques, sur demande), état des mises à jour, journal des automatisations, sons MP3.
 * À l'import, ce qui n'est pas dans le fichier reste tel quel sur l'écran ; le reste est remplacé.
 *
 * Format : `{format, app, firmware, date, stats, prefs: {fichier: {clé: {t, v}}}, files: {nom: texte}}`,
 * `t` = s (texte), i, l, f, b, set (ensemble de textes).
 */
object SettingsBackup {

    private const val TAG = "MG4_BACKUP"
    const val FILE_NAME = "mg4control-reglages.json"
    const val FORMAT = 1

    /** Fichiers de réglages sauvegardés (le reste : état, historique, journaux). */
    val FICHIERS = listOf("mg4_settings", "mg4_shortcuts", "mg4_profiles", "mg4_custom_rules",
        "mg4_quick_settings", "mg4_abrp", "mg4_stats")

    /** Clés de `mg4_settings` qui ne sont pas des réglages : détectées, apprises, ou d'état. */
    private val ETAT_REGLAGES = setOf(
        "restore_prompt_dismissed", "nav_side_detected", "cluster_power_learned", "audio_setting_last_source",
        "clim_last_manual_fan", "stats_export_dest", "stats_export_tree", "update_overlay_last_check",
        "update_overlay_last_ok", KEY_LAST_EXPORT,
        // Démarrage où la fenêtre clim d'origine a été fermée, et nettoyage des gestes du plein écran :
        // des numéros de démarrage de CET écran, sans sens sur un écran réinitialisé.
        "saic_hvac_closed_boot", "immersive_gestures_boot",
    )

    /** Date du dernier export (ms), montrée sous les boutons. */
    const val KEY_LAST_EXPORT = "backup_last_export"

    /** Historique des Statistiques : le fichier des trajets et charges, et ce que la charge a appris. */
    private const val STATS_FICHIER = "stats_history.json"
    private val STATS_APPRIS = setOf("energy_checks", "soc_energy_table_ac", "soc_energy_table_dc")

    private const val CLE_RACCOURCIS = "custom_shortcuts"

    /** Vrai si [cle] de [fichier] reste en dehors du fichier (sensible ou état), quel que soit le choix. */
    fun exclue(fichier: String, cle: String): Boolean = when (fichier) {
        "mg4_settings" -> cle in ETAT_REGLAGES || cle.startsWith("autobri_last")
        "mg4_abrp" -> cle == "accounts_json" || cle == "position_consent"
        "mg4_custom_rules" -> cle == "last_runs_json" || cle == "last_skips_json" || cle == "run_counts_json"
        "mg4_stats" -> cle == "last_reading_ms" || cle == "last_reading_soc" || cle == "pending_state"
        else -> false
    }

    /** Ce que le fichier porte pour [fichier] : sans [exclue], et l'appris des Statistiques seulement avec [stats]. */
    private fun garde(fichier: String, cle: String, stats: Boolean): Boolean =
        !exclue(fichier, cle) && (stats || fichier != "mg4_stats" || cle !in STATS_APPRIS)

    // ── Export ─────────────────────────────────────────────────────────────────

    fun export(ctx: Context, stats: Boolean, app: String, firmware: String, dateIso: String): String {
        val prefs = JSONObject()
        for (f in FICHIERS) {
            val p = ctx.getSharedPreferences(f, Context.MODE_PRIVATE)
            val o = JSONObject()
            for ((cle, v) in p.all.toSortedMap()) {
                if (!garde(f, cle, stats) || v == null) continue
                val valeur = if (f == "mg4_settings" && cle == CLE_RACCOURCIS && v is String) sansWebhooks(v) else v
                encoder(valeur)?.let { o.put(cle, it) }
            }
            prefs.put(f, o)
        }
        val fichiers = JSONObject()
        if (stats) File(ctx.filesDir, STATS_FICHIER).takeIf { it.isFile }?.let { fichiers.put(STATS_FICHIER, it.readText()) }
        return JSONObject()
            .put("format", FORMAT).put("app", app).put("firmware", firmware).put("date", dateIso).put("stats", stats)
            .put("prefs", prefs).put("files", fichiers)
            .toString(1)
    }

    private fun encoder(v: Any): JSONObject? = when (v) {
        is String -> JSONObject().put("t", "s").put("v", v)
        is Int -> JSONObject().put("t", "i").put("v", v)
        is Long -> JSONObject().put("t", "l").put("v", v)
        is Float -> JSONObject().put("t", "f").put("v", v.toDouble())
        is Boolean -> JSONObject().put("t", "b").put("v", v)
        is Set<*> -> JSONObject().put("t", "set").put("v", JSONArray(v.filterIsInstance<String>().sorted()))
        else -> null
    }

    /** La liste des raccourcis perso, sans ceux de type Webhook (adresse et contenu souvent porteurs d'une clé). */
    fun sansWebhooks(json: String): String = runCatching {
        val a = JSONArray(json)
        val r = JSONArray()
        for (i in 0 until a.length()) a.optJSONObject(i)?.takeIf { !estWebhook(it) }?.let { r.put(it) }
        r.toString()
    }.getOrDefault("[]")

    private fun estWebhook(o: JSONObject) = o.optString("kind") == "webhook"

    // ── Lecture ────────────────────────────────────────────────────────────────

    /** Ce qu'un fichier contient, pour la fenêtre de confirmation ; `null` si ce n'en est pas un. */
    data class Resume(
        val app: String,
        val firmware: String,
        val date: String,
        val stats: Boolean,
        val profils: Int,
        val defaut: String?,
        val automatisations: Int,
        val raccourcis: Int,
        val reglages: Int,
        /** Format plus récent que celui que cette version sait lire : importé quand même, avec un mot. */
        val plusRecent: Boolean,
    )

    fun lire(texte: String?): Resume? = runCatching {
        val o = JSONObject(texte ?: return null)
        if (!o.has("prefs") || !o.has("format")) return null
        val prefs = o.getJSONObject("prefs")
        fun texte(f: String, k: String) = prefs.optJSONObject(f)?.optJSONObject(k)?.optString("v")
        fun compte(json: String?) = json?.let { runCatching { JSONArray(it).length() }.getOrNull() } ?: 0
        val profils = texte("mg4_profiles", "profiles_json")
        val defautId = texte("mg4_profiles", "default_profile_id")
        val defaut = profils?.let { runCatching {
            val a = JSONArray(it)
            (0 until a.length()).map { i -> a.getJSONObject(i) }.firstOrNull { p -> p.optString("id") == defautId }?.optString("name")
        }.getOrNull() }
        var n = 0
        val noms = prefs.keys()
        while (noms.hasNext()) n += prefs.optJSONObject(noms.next())?.length() ?: 0
        Resume(
            app = o.optString("app"), firmware = o.optString("firmware"), date = o.optString("date"),
            stats = o.optJSONObject("files")?.has(STATS_FICHIER) == true,
            profils = compte(profils), defaut = defaut,
            automatisations = compte(texte("mg4_custom_rules", "rules_json")),
            raccourcis = compte(texte("mg4_settings", CLE_RACCOURCIS)),
            reglages = n, plusRecent = o.optInt("format", 0) > FORMAT,
        )
    }.getOrNull()

    // ── Import ─────────────────────────────────────────────────────────────────

    /**
     * Remplace les réglages par ceux de [texte]. Ce qui reste hors du fichier (sensible, état) est
     * gardé tel quel, les raccourcis Webhook de cet écran compris. Écriture synchrone : l'appelant
     * redémarre l'application juste après. `false` si le fichier n'est pas lisible (rien n'est touché).
     */
    fun import(ctx: Context, texte: String): Boolean {
        val o = runCatching { JSONObject(texte) }.getOrNull() ?: return false
        val prefs = o.optJSONObject("prefs") ?: return false
        val avecStats = o.optJSONObject("files")?.has(STATS_FICHIER) == true
        for (f in FICHIERS) {
            val donnees = prefs.optJSONObject(f) ?: continue
            val p = ctx.getSharedPreferences(f, Context.MODE_PRIVATE)
            val e = p.edit()
            // Retirer ce que le fichier remplace ; l'appris des Statistiques reste sans historique importé.
            for (cle in p.all.keys) if (garde(f, cle, avecStats)) e.remove(cle)
            val cles = donnees.keys()
            while (cles.hasNext()) {
                val cle = cles.next()
                if (!garde(f, cle, avecStats)) continue
                var v = donnees.optJSONObject(cle) ?: continue
                if (f == "mg4_settings" && cle == CLE_RACCOURCIS) {
                    v = JSONObject().put("t", "s").put("v", avecWebhooksLocaux(v.optString("v"), p.getString(CLE_RACCOURCIS, null)))
                }
                ecrire(e, cle, v)
            }
            e.commit()
        }
        if (avecStats) {
            val contenu = o.getJSONObject("files").getString(STATS_FICHIER)
            val tmp = File(ctx.filesDir, "$STATS_FICHIER.tmp")
            tmp.writeText(contenu)
            if (!tmp.renameTo(File(ctx.filesDir, STATS_FICHIER))) {
                File(ctx.filesDir, STATS_FICHIER).writeText(contenu)
                tmp.delete()
            }
        }
        AppLogger.i(TAG, "réglages importés (exportés en ${o.optString("app")}, ${o.optString("firmware")}, " +
            "${o.optString("date")}) — statistiques ${if (avecStats) "comprises" else "non comprises"}")
        return true
    }

    /** Les raccourcis importés, plus les Webhook déjà sur cet écran (jamais dans le fichier). */
    fun avecWebhooksLocaux(importes: String, locaux: String?): String = runCatching {
        val r = JSONArray(sansWebhooks(importes))
        val l = locaux?.let { JSONArray(it) } ?: JSONArray()
        for (i in 0 until l.length()) l.optJSONObject(i)?.takeIf { estWebhook(it) }?.let { r.put(it) }
        r.toString()
    }.getOrDefault(importes)

    private fun ecrire(e: SharedPreferences.Editor, cle: String, o: JSONObject) {
        when (o.optString("t")) {
            "s" -> e.putString(cle, o.optString("v"))
            "i" -> e.putInt(cle, o.optInt("v"))
            "l" -> e.putLong(cle, o.optLong("v"))
            "f" -> e.putFloat(cle, o.optDouble("v").toFloat())
            "b" -> e.putBoolean(cle, o.optBoolean("v"))
            "set" -> o.optJSONArray("v")?.let { a -> e.putStringSet(cle, (0 until a.length()).map { a.optString(it) }.toSet()) }
        }
    }
}
