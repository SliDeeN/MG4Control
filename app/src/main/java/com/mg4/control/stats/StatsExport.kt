package com.mg4.control.stats

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import android.provider.DocumentsContract
import com.mg4.control.debug.AppLogger
import java.io.File

/**
 * Export des Statistiques (issue #206) : écrit les deux fichiers CSV ([com.mg4.control.model.StatsCsv])
 * là où l'utilisateur l'a choisi — Téléchargements (défaut), la clé USB branchée, ou un dossier pris
 * dans le sélecteur d'Android (DocumentsUI, présent sur SWI69/132/133).
 *
 * Téléchargements et la clé s'écrivent en fichiers ordinaires, dans un sous-dossier `MG4Control` ;
 * le dossier choisi, par le fournisseur de documents, avec la permission gardée d'un export à
 * l'autre. Le dernier choix est retenu.
 */
object StatsExport {

    private const val TAG = "MG4_STATS"
    const val DOSSIER = "MG4Control"
    private const val PREFS = "mg4_settings"
    private const val KEY_DEST = "stats_export_dest"
    private const val KEY_TREE = "stats_export_tree"
    private const val MIME = "text/csv"

    enum class Destination { DOWNLOADS, USB, FOLDER }

    /** La clé USB montée : son dossier racine, son nom, la place libre. */
    data class Usb(val racine: File, val nom: String, val libreOctets: Long)

    sealed class Resultat {
        /** [ou] : le dossier tel qu'on peut le montrer ; [fichiers] : les noms écrits. */
        data class Ok(val ou: String, val fichiers: List<String>) : Resultat()
        data class Echec(val raison: String) : Resultat()
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Le dernier choix, ramené à Téléchargements s'il n'est plus possible (clé retirée, dossier oublié). */
    fun destination(ctx: Context, usb: Usb?): Destination {
        val d = runCatching { Destination.valueOf(prefs(ctx).getString(KEY_DEST, null) ?: "") }.getOrNull()
        return when {
            d == Destination.USB && usb == null -> Destination.DOWNLOADS
            d == Destination.FOLDER && dossierChoisi(ctx) == null -> Destination.DOWNLOADS
            else -> d ?: Destination.DOWNLOADS
        }
    }

    fun retenir(ctx: Context, d: Destination) = prefs(ctx).edit().putString(KEY_DEST, d.name).apply()

    fun dossierChoisi(ctx: Context): Uri? = prefs(ctx).getString(KEY_TREE, null)?.let { Uri.parse(it) }

    /** Dossier rendu par le sélecteur : permission gardée pour les exports suivants. */
    fun retenirDossier(ctx: Context, arbre: Uri) {
        val drapeaux = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        runCatching { ctx.contentResolver.takePersistableUriPermission(arbre, drapeaux) }
            .onFailure { AppLogger.w(TAG, "export : permission du dossier non gardée (${it.message})") }
        prefs(ctx).edit().putString(KEY_TREE, arbre.toString()).putString(KEY_DEST, Destination.FOLDER.name).apply()
    }

    /** « Documents/Stats », ou le nom du volume pour sa racine (`primary:Documents/Stats`). */
    fun libelleDossier(arbre: Uri): String {
        val id = runCatching { DocumentsContract.getTreeDocumentId(arbre) }.getOrNull() ?: return arbre.toString()
        val chemin = id.substringAfter(':', "")
        return chemin.ifEmpty { id.substringBefore(':') }
    }

    fun telechargements(): File = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), DOSSIER)

    /** Première clé USB (volume amovible) montée, ou null. */
    fun usb(ctx: Context): Usb? = runCatching {
        val sm = ctx.getSystemService(StorageManager::class.java) ?: return null
        val v = sm.storageVolumes.firstOrNull { it.isRemovable && it.state == Environment.MEDIA_MOUNTED } ?: return null
        val racine = racine(v) ?: return null
        Usb(racine, v.getDescription(ctx) ?: racine.name, racine.usableSpace)
    }.getOrNull()

    /** `getPathFile` est caché sur Android 9 (l'appli système y a droit) ; repli sur /storage/<uuid>. */
    private fun racine(v: StorageVolume): File? =
        runCatching { v.javaClass.getMethod("getPathFile").invoke(v) as File }.getOrNull()
            ?: v.uuid?.let { File("/storage/$it") }?.takeIf { it.isDirectory }

    /** Les deux fichiers, [fichiers] = (nom, contenu). Fil de travail. */
    fun exporter(ctx: Context, d: Destination, fichiers: List<Pair<String, String>>): Resultat = try {
        when (d) {
            Destination.DOWNLOADS -> versDossier(telechargements(), fichiers, "Download/$DOSSIER")
            Destination.USB -> {
                val cle = usb(ctx) ?: return Resultat.Echec("USB ?")
                versDossier(File(cle.racine, DOSSIER), fichiers, "${cle.nom}/$DOSSIER")
            }
            Destination.FOLDER -> {
                val arbre = dossierChoisi(ctx) ?: return Resultat.Echec("?")
                versArbre(ctx, arbre, fichiers)
            }
        }.also { AppLogger.i(TAG, "export $d → $it") }
    } catch (e: Exception) {
        AppLogger.w(TAG, "export $d : ${e.javaClass.simpleName} ${e.message}")
        Resultat.Echec(e.message ?: e.javaClass.simpleName)
    }

    private fun versDossier(dossier: File, fichiers: List<Pair<String, String>>, montre: String): Resultat {
        if (!dossier.isDirectory && !dossier.mkdirs()) return Resultat.Echec(dossier.absolutePath)
        fichiers.forEach { (nom, texte) -> File(dossier, nom).writeText(texte, Charsets.UTF_8) }
        return Resultat.Ok(montre, fichiers.map { it.first })
    }

    private fun versArbre(ctx: Context, arbre: Uri, fichiers: List<Pair<String, String>>): Resultat {
        val r = ctx.contentResolver
        val parent = DocumentsContract.buildDocumentUriUsingTree(arbre, DocumentsContract.getTreeDocumentId(arbre))
        val noms = fichiers.map { (nom, texte) ->
            // Un fichier du même nom existe déjà : le fournisseur en crée un autre, « nom (1).csv ».
            val doc = DocumentsContract.createDocument(r, parent, MIME, nom) ?: return Resultat.Echec(nom)
            r.openOutputStream(doc)?.use { it.write(texte.toByteArray(Charsets.UTF_8)) } ?: return Resultat.Echec(nom)
            nomDe(ctx, doc) ?: nom
        }
        return Resultat.Ok(libelleDossier(arbre), noms)
    }

    private fun nomDe(ctx: Context, doc: Uri): String? = runCatching {
        ctx.contentResolver.query(doc, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }.getOrNull()
}
