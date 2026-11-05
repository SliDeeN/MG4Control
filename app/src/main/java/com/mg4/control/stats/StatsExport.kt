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
 * Téléchargements s'écrit en fichiers ordinaires, dans un sous-dossier `MG4Control`. La clé USB et
 * le dossier choisi passent par le fournisseur de documents d'Android (ExternalStorageProvider) :
 * une appli ne peut pas écrire elle-même à la racine d'un volume amovible sur Android 9 — essai du
 * 2026-11-03, `mkdirs` refusé sur /storage/34B0-FBA7 alors que la même clé prise dans le sélecteur
 * s'écrivait sans souci. Pour la clé, l'appli (signée plateforme, MANAGE_DOCUMENTS) s'adresse au
 * fournisseur sans passer par le sélecteur. Un fichier du même nom est remplacé, partout.
 * Le dernier choix est retenu.
 */
object StatsExport {

    private const val TAG = "MG4_STATS"
    const val DOSSIER = "MG4Control"
    private const val PREFS = "mg4_settings"
    private const val KEY_DEST = "stats_export_dest"
    private const val KEY_TREE = "stats_export_tree"
    private const val MIME = "text/csv"
    private const val STOCKAGE = "com.android.externalstorage.documents"

    enum class Destination { DOWNLOADS, USB, FOLDER }

    /** La clé USB montée : son identifiant de volume (`34B0-FBA7`), son nom, la place libre. */
    data class Usb(val uuid: String, val nom: String, val libreOctets: Long)

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
        val uuid = v.uuid ?: return null
        Usb(uuid, v.getDescription(ctx) ?: uuid, racine(v)?.usableSpace ?: 0L)
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
                val racine = DocumentsContract.buildTreeDocumentUri(STOCKAGE, "${cle.uuid}:")
                versArbre(ctx, racine, fichiers, sousDossier = DOSSIER)?.let { Resultat.Ok("${cle.nom}/$DOSSIER", it) }
                    ?: Resultat.Echec(cle.nom)
            }
            Destination.FOLDER -> {
                val arbre = dossierChoisi(ctx) ?: return Resultat.Echec("?")
                versArbre(ctx, arbre, fichiers)?.let { Resultat.Ok(libelleDossier(arbre), it) }
                    ?: Resultat.Echec(libelleDossier(arbre))
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

    /**
     * Écrit [fichiers] dans [arbre], ou dans son [sousDossier] (créé au besoin), par le fournisseur
     * de documents. Un fichier du même nom est réécrit plutôt que doublé en « nom (1).csv ».
     * Renvoie les noms écrits, ou null si le fournisseur n'a rien créé.
     */
    private fun versArbre(ctx: Context, arbre: Uri, fichiers: List<Pair<String, String>>, sousDossier: String? = null): List<String>? {
        val r = ctx.contentResolver
        var parentId = DocumentsContract.getTreeDocumentId(arbre)
        if (sousDossier != null) {
            parentId = enfant(ctx, arbre, parentId, sousDossier)
                ?: DocumentsContract.createDocument(r, DocumentsContract.buildDocumentUriUsingTree(arbre, parentId),
                    DocumentsContract.Document.MIME_TYPE_DIR, sousDossier)?.let { DocumentsContract.getDocumentId(it) }
                ?: return null
        }
        val parent = DocumentsContract.buildDocumentUriUsingTree(arbre, parentId)
        return fichiers.map { (nom, texte) ->
            val doc = enfant(ctx, arbre, parentId, nom)?.let { DocumentsContract.buildDocumentUriUsingTree(arbre, it) }
                ?: DocumentsContract.createDocument(r, parent, mime(nom), nom) ?: return null
            r.openOutputStream(doc, "wt")?.use { it.write(texte.toByteArray(Charsets.UTF_8)) } ?: return null
            nom
        }
    }

    /**
     * Type du fichier créé : le fournisseur ajoute l'extension du type si le nom ne la porte pas
     * (« x.json » en text/csv deviendrait « x.json.csv »). Inconnu : octet-stream, nom gardé tel quel.
     */
    private fun mime(nom: String): String = when (nom.substringAfterLast('.', "").lowercase()) {
        "csv" -> MIME
        "txt" -> "text/plain"
        else -> "application/octet-stream"
    }

    /**
     * Lecture pour l'import (2026-11-05) : le fichier [nom] dans Téléchargements/MG4Control (ou
     * directement dans Téléchargements, où allaient les anciens exports), ou dans MG4Control sur
     * la clé USB. Null s'il n'y est pas. Fil de travail.
     */
    fun lire(ctx: Context, d: Destination, nom: String): String? = runCatching {
        when (d) {
            Destination.DOWNLOADS -> listOf(File(telechargements(), nom),
                File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), nom))
                .firstOrNull { it.isFile }?.readText(Charsets.UTF_8)
            Destination.USB -> {
                val cle = usb(ctx) ?: return null
                val arbre = DocumentsContract.buildTreeDocumentUri(STOCKAGE, "${cle.uuid}:")
                val racine = DocumentsContract.getTreeDocumentId(arbre)
                val dossier = enfant(ctx, arbre, racine, DOSSIER)
                val id = dossier?.let { enfant(ctx, arbre, it, nom) } ?: enfant(ctx, arbre, racine, nom) ?: return null
                lireUri(ctx, DocumentsContract.buildDocumentUriUsingTree(arbre, id))
            }
            Destination.FOLDER -> null
        }
    }.onFailure { AppLogger.w(TAG, "import $d : ${it.javaClass.simpleName} ${it.message}") }.getOrNull()

    fun lireUri(ctx: Context, uri: Uri): String? = runCatching {
        ctx.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
    }.getOrNull()

    /** Identifiant du document nommé [nom] directement sous [parentId], ou null. */
    private fun enfant(ctx: Context, arbre: Uri, parentId: String, nom: String): String? = runCatching {
        val enfants = DocumentsContract.buildChildDocumentsUriUsingTree(arbre, parentId)
        ctx.contentResolver.query(enfants, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
            var id: String? = null
            while (id == null && c.moveToNext()) if (c.getString(1) == nom) id = c.getString(0)
            id
        }
    }.getOrNull()
}
