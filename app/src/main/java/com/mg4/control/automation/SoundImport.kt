package com.mg4.control.automation

import android.content.Context
import android.media.MediaMetadataRetriever
import com.mg4.control.debug.AppLogger
import com.mg4.control.hardware.MG4Hardware
import com.mg4.control.model.SoundAutomationRules
import com.mg4.control.model.SoundSlot
import java.io.File

/**
 * Import d'un son depuis une clé USB (issue #161). Fenêtre maison plutôt que le sélecteur de
 * fichiers d'Android : rien ne dit que la voiture en a un. Les fichiers audio de la clé sont
 * listés avec leur durée ; au-delà de 30 s ils ne sont pas proposés. Le fichier choisi est COPIÉ
 * dans l'appli — un chemin vers la clé ne survivrait pas à son retrait. Hors du fil principal.
 */
object SoundImport {

    private const val TAG = SoundAutomation.TAG
    private val EXTENSIONS = setOf("mp3", "m4a", "aac", "ogg", "wav", "flac")
    private const val PROFONDEUR_MAX = 4
    private const val FICHIERS_MAX = 300

    class Fichier(val file: File, val durationMs: Long)

    /** [cles] : clés USB lisibles trouvées ; [tropLongs] : fichiers écartés (plus de 30 s). */
    class Resultat(val cles: Int, val fichiers: List<Fichier>, val tropLongs: Int)

    fun chercher(): Resultat {
        val racines = MG4Hardware.usbVolumeRoots()
        val vus = mutableListOf<File>()
        racines.forEach { parcourir(it, 0, vus) }
        var tropLongs = 0
        val fichiers = vus.distinctBy { it.name.lowercase() + it.length() }.mapNotNull { f ->
            val d = duree(f.path) ?: return@mapNotNull null
            if (d > SoundAutomationRules.MAX_DURATION_MS) { tropLongs++; null } else Fichier(f, d)
        }.sortedBy { it.file.name.lowercase() }
        AppLogger.i(TAG, "import : ${racines.size} clé(s) ${racines.joinToString { it.path }}, " +
            "${vus.size} fichier(s) audio, ${fichiers.size} proposé(s), $tropLongs trop long(s)")
        return Resultat(racines.size, fichiers, tropLongs)
    }

    private fun parcourir(dossier: File, profondeur: Int, vus: MutableList<File>) {
        if (profondeur > PROFONDEUR_MAX || vus.size >= FICHIERS_MAX) return
        val enfants = runCatching { dossier.listFiles() }.getOrNull() ?: return
        for (f in enfants) {
            if (vus.size >= FICHIERS_MAX) return
            if (f.name.startsWith(".")) continue
            when {
                f.isDirectory && f.name != "LOST.DIR" && f.name != "Android" -> parcourir(f, profondeur + 1, vus)
                f.isFile && f.extension.lowercase() in EXTENSIONS -> vus += f
            }
        }
    }

    /** Durée en ms, `null` si le fichier n'est pas lisible comme du son. */
    fun duree(path: String): Long? {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(path)
            r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.takeIf { it > 0 }
        } catch (e: Exception) {
            AppLogger.w(TAG, "durée illisible : ${f(path)} — ${e.javaClass.simpleName}")
            null
        } finally {
            runCatching { r.release() }
        }
    }

    /**
     * Copie [f] comme son [index] (l'ancien fichier est remplacé) ; volume et événements sont
     * gardés. Rend le son mis à jour, ou `null` si la copie a échoué.
     */
    fun importer(ctx: Context, index: Int, f: Fichier): SoundSlot? = try {
        val dossier = SoundAutomationSettings.dossier(ctx)
        val cible = File(dossier, "son${index + 1}.${f.file.extension.lowercase()}")
        dossier.listFiles()?.filter { it.nameWithoutExtension == "son${index + 1}" && it != cible }?.forEach { it.delete() }
        oublierCopiePartagee(index)
        f.file.inputStream().use { entree -> cible.outputStream().use { entree.copyTo(it) } }
        val s = SoundAutomationSettings.slot(ctx, index).copy(path = cible.path, title = f.file.name, durationMs = f.durationMs)
        SoundAutomationSettings.saveSlot(ctx, index, s)
        AppLogger.i(TAG, "son ${index + 1} importé : ${f.file.name} (${f.durationMs} ms, ${cible.length()} o)")
        s
    } catch (e: Exception) {
        AppLogger.w(TAG, "import du son ${index + 1} impossible : ${e.javaClass.simpleName}: ${e.message}")
        null
    }

    /** Retire le fichier du son [index] ; ses événements et son volume restent. */
    fun retirer(ctx: Context, index: Int) {
        SoundAutomationSettings.slot(ctx, index).path?.let { File(it).delete() }
        oublierCopiePartagee(index)
        SoundAutomationSettings.saveSlot(ctx, index,
            SoundAutomationSettings.slot(ctx, index).copy(path = null, title = null, durationMs = 0L))
        AppLogger.i(TAG, "son ${index + 1} retiré")
    }

    /** La copie de repli du lecteur (mémoire partagée, voir SoundAutomation) suit le son importé. */
    private fun oublierCopiePartagee(index: Int) {
        @Suppress("DEPRECATION")
        File(android.os.Environment.getExternalStorageDirectory(), "MG4Control/sounds").listFiles()
            ?.filter { it.nameWithoutExtension == "son${index + 1}" }?.forEach { it.delete() }
    }

    private fun f(path: String) = File(path).name
}
