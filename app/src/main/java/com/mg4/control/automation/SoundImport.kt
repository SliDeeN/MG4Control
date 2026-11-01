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
     * Copie [f] dans l'appli : comme son [index] (son fichier est remplacé, volume et événements
     * gardés), ou — [index] nul — comme nouveau son en fin de liste, s'il reste de la place sous
     * [max]. Rend le rang du son, ou `null` si la copie a échoué ou la liste est pleine.
     *
     * Chaque copie a un nom UNIQUE : les rangs bougent quand un son est retiré, un nom tiré du
     * rang (`son2.mp3`) écraserait le fichier d'un autre son.
     */
    fun importer(ctx: Context, index: Int?, f: Fichier, max: Int): Int? = try {
        val dossier = SoundAutomationSettings.dossier(ctx)
        val cible = File(dossier, "son_${System.currentTimeMillis()}.${f.file.extension.lowercase()}")
        f.file.inputStream().use { entree -> cible.outputStream().use { entree.copyTo(it) } }
        val rang = if (index == null) {
            SoundAutomationSettings.ajouter(ctx, SoundSlot(path = cible.path, title = f.file.name, durationMs = f.durationMs), max)
        } else {
            val s = SoundAutomationSettings.slot(ctx, index).copy(path = cible.path, title = f.file.name, durationMs = f.durationMs)
            SoundAutomationSettings.saveSlot(ctx, index, s)
            index
        }
        nettoyer(ctx)
        if (rang == null) {
            AppLogger.w(TAG, "import de ${f.file.name} refusé : déjà $max son(s)")
        } else {
            AppLogger.i(TAG, "son ${rang + 1} ${if (index == null) "ajouté" else "remplacé"} : ${f.file.name} " +
                "(${f.durationMs} ms, ${cible.length()} o)")
        }
        rang
    } catch (e: Exception) {
        AppLogger.w(TAG, "import ${index?.let { "du son ${it + 1}" } ?: "d'un nouveau son"} impossible : " +
            "${e.javaClass.simpleName}: ${e.message}")
        null
    }

    /** Retire le son [index] de la liste, avec son fichier ; les sons suivants remontent d'un rang. */
    fun retirer(ctx: Context, index: Int) {
        SoundAutomationSettings.supprimer(ctx, index)
        nettoyer(ctx)
        AppLogger.i(TAG, "son ${index + 1} retiré")
    }

    /**
     * Efface tout fichier de son qu'aucun son de la liste ne désigne plus — l'ancien fichier d'un
     * son remplacé, celui d'un son retiré —, et sa copie de repli dans la mémoire partagée (voir
     * SoundAutomation). Rien ne reste dans la mémoire de la voiture.
     */
    private fun nettoyer(ctx: Context) {
        val gardes = SoundAutomationSettings.slots(ctx).mapNotNull { it.path?.let { p -> File(p).name } }.toSet()
        @Suppress("DEPRECATION")
        val partage = File(android.os.Environment.getExternalStorageDirectory(), "MG4Control/sounds")
        listOf(SoundAutomationSettings.dossier(ctx), partage).forEach { dossier ->
            dossier.listFiles()?.filter { it.isFile && it.name !in gardes }?.forEach {
                if (it.delete()) AppLogger.i(TAG, "fichier effacé : ${it.parentFile?.name}/${it.name}")
            }
        }
    }

    private fun f(path: String) = File(path).name
}
