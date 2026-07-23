package com.mg4.control.profile

import android.os.Environment
import com.google.gson.Gson
import com.mg4.hardware.AppLogger
import com.mg4.hardware.model.DrivingProfile
import com.mg4.hardware.model.ProfileBackup
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Lit/écrit le fichier de sauvegarde des profils — et lui seul.
 *
 * Le fichier vit dans un dossier PARTAGÉ de la voiture qui survit à la désinstallation
 * de l'app (le stockage privé de l'app, lui, est effacé). L'app étant signée plateforme
 * (`uid.system`), elle y écrit directement.
 */
class ProfileBackupManager {

    companion object {
        private const val TAG = "MG4_PROFILE_BACKUP"
        private const val DIR_NAME  = "MG4Control"
        private const val FILE_NAME = "mg4_profiles_backup.json"
    }

    private val gson = Gson()

    private val backupDir: File
        get() = File(Environment.getExternalStorageDirectory(), DIR_NAME)

    private val backupFile: File
        get() = File(backupDir, FILE_NAME)

    /** True si un fichier de sauvegarde non vide est présent. */
    fun backupExists(): Boolean = backupFile.isFile && backupFile.length() > 0

    /**
     * Écrit la sauvegarde de façon ATOMIQUE (fichier temporaire puis rename), afin
     * qu'une écriture interrompue ne laisse jamais un fichier corrompu. Jamais bloquant :
     * toute erreur (stockage non inscriptible, etc.) est loggée et ignorée.
     */
    fun writeBackup(profiles: List<DrivingProfile>, defaultId: String?): Boolean {
        return try {
            if (!backupDir.exists()) backupDir.mkdirs()
            val payload = ProfileBackup(
                schemaVersion = ProfileBackup.CURRENT_SCHEMA_VERSION,
                defaultId = defaultId,
                profiles = profiles
            )
            val json = gson.toJson(payload)
            // Fichier temporaire UNIQUE : deux sauvegardes concurrentes écrivaient le même
            // ".tmp" et entrelaçaient leur contenu.
            val tmp = File.createTempFile(FILE_NAME, ".tmp", backupDir)
            tmp.writeText(json)
            // Remplacement atomique SANS delete() préalable : l'ancienne sauvegarde reste
            // lisible jusqu'à la seconde près où la nouvelle prend sa place.
            try {
                Files.move(tmp.toPath(), backupFile.toPath(),
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: Exception) {
                // Systèmes de fichiers sans move atomique : copie puis suppression du tmp.
                tmp.copyTo(backupFile, overwrite = true)
                tmp.delete()
            }
            AppLogger.i(TAG, "writeBackup → ${profiles.size} profil(s) @ ${backupFile.absolutePath}")
            true
        } catch (e: Exception) {
            AppLogger.w(TAG, "writeBackup échec (${backupFile.absolutePath}): ${e.message}")
            false
        }
    }

    /** Lit la sauvegarde, ou null si absente/illisible/corrompue. */
    fun readBackup(): ProfileBackup? {
        return try {
            if (!backupExists()) return null
            val json = backupFile.readText()
            gson.fromJson(json, ProfileBackup::class.java)
        } catch (e: Exception) {
            AppLogger.w(TAG, "readBackup illisible/corrompu: ${e.message}")
            null
        }
    }
}
