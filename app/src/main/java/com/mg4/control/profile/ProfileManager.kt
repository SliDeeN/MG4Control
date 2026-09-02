package com.mg4.control.profile

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.mg4.control.model.DrivingProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class ProfileManager(private val context: Context) {

    companion object {
        private const val PREFS_NAME = "mg4_profiles"
        private const val KEY_PROFILES = "profiles_json"
        private const val KEY_DEFAULT_ID = "default_profile_id"
        const val MAX_PROFILES = 5

        /**
         * Verrou de processus, PAS d'instance : l'UI et le service construisent chacun leur
         * ProfileManager sur le même fichier de préférences. Chaque mutation est un
         * lire-modifier-écrire sur un blob JSON — sans ce verrou, deux sauvegardes
         * simultanées en perdent une silencieusement.
         */
        private val MUTATION_LOCK = Any()

        /** Scope unique pour les sauvegardes en arrière-plan (un seul par processus). */
        private val backupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()
    private val backupManager = ProfileBackupManager()

    // -------------------------------------------------------------------------
    // CRUD
    // -------------------------------------------------------------------------

    fun getAll(): List<DrivingProfile> {
        val json = prefs.getString(KEY_PROFILES, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<DrivingProfile>>() {}.type
            val list: List<DrivingProfile> = gson.fromJson(json, type) ?: emptyList()
            // Migration : profils créés avant l'ajout AEB ont aebMode=0 (valeur JVM par défaut).
            // On les initialise à AEB activé + mode Alerte+Freinage par défaut.
            list.map { p ->
                if (p.aebMode == 0) p.copy(aebEnabled = true, aebMode = 2) else p
            }
        } catch (_: Exception) { emptyList() }
    }

    fun save(profile: DrivingProfile): Boolean {
        synchronized(MUTATION_LOCK) {
            val list = getAll().toMutableList()
            val existing = list.indexOfFirst { it.id == profile.id }
            if (existing >= 0) {
                list[existing] = profile
            } else {
                if (list.size >= MAX_PROFILES) return false
                list.add(profile)
            }
            persist(list)
        }
        triggerBackup()
        return true
    }

    fun delete(profileId: String) {
        synchronized(MUTATION_LOCK) {
            val list = getAll().toMutableList()
            list.removeAll { it.id == profileId }
            persist(list)
            // Clear default if it was this profile
            if (prefs.getString(KEY_DEFAULT_ID, null) == profileId) {
                prefs.edit().remove(KEY_DEFAULT_ID).apply()
            }
            // Idem pour le profil ACTIF : le laisser pointer sur un profil disparu ferait
            // résoudre les raccourcis qui lui sont associés sur un identifiant fantôme — ils
            // ne se déclencheraient jamais, sans que rien ne l'explique.
            ActiveProfile.forgetIfMissing(context, list.map { it.id })
        }
        triggerBackup()
    }

    fun setDefault(profileId: String) {
        prefs.edit().putString(KEY_DEFAULT_ID, profileId).apply()
        triggerBackup()
    }

    fun clearDefault() {
        prefs.edit().remove(KEY_DEFAULT_ID).apply()
        triggerBackup()
    }

    fun getDefaultProfile(): DrivingProfile? {
        val defaultId = prefs.getString(KEY_DEFAULT_ID, null) ?: return null
        return getAll().firstOrNull { it.id == defaultId }
    }

    fun getDefaultId(): String? = prefs.getString(KEY_DEFAULT_ID, null)

    fun getById(id: String): DrivingProfile? = getAll().firstOrNull { it.id == id }

    // [BT-PROFILES] Retourne le premier profil dont le btDeviceMac correspond au MAC donné.
    fun getProfileForBtDevice(mac: String): DrivingProfile? =
        getAll().firstOrNull { it.btDeviceMac.equals(mac, ignoreCase = true) }

    // -------------------------------------------------------------------------
    // Sauvegarde / restauration (fichier mémoire voiture — survit à la désinstallation)
    // -------------------------------------------------------------------------

    fun hasBackup(): Boolean = backupManager.backupExists()

    /** Lit la sauvegarde sans rien modifier (pour proposer la restauration). */
    fun readBackup() = backupManager.readBackup()

    /**
     * Restaure les profils depuis une sauvegarde : remplace les profils locaux par ceux
     * de la sauvegarde (plafonné à [MAX_PROFILES]) et applique le profil par défaut.
     * Retourne le nombre de profils restaurés.
     */
    fun restoreFrom(backup: com.mg4.control.model.ProfileBackup): Int {
        val restored = backup.profiles.take(MAX_PROFILES)
        synchronized(MUTATION_LOCK) {
            persist(restored)
            val defId = backup.defaultId
            if (defId != null && restored.any { it.id == defId }) {
                prefs.edit().putString(KEY_DEFAULT_ID, defId).apply()
            } else {
                prefs.edit().remove(KEY_DEFAULT_ID).apply()
            }
        }
        triggerBackup()
        return restored.size
    }

    /** Réécrit le fichier de sauvegarde en arrière-plan (état courant des profils). */
    private fun triggerBackup() {
        val snapshot = getAll()
        val defaultId = getDefaultId()
        backupScope.launch {
            backupManager.writeBackup(snapshot, defaultId)
        }
    }

    // -------------------------------------------------------------------------
    // Persistence
    // -------------------------------------------------------------------------

    private fun persist(list: List<DrivingProfile>) {
        // commit() et non apply() : la mutation suivante relit immédiatement le blob,
        // une écriture asynchrone rouvrirait la fenêtre de perte de mise à jour.
        prefs.edit().putString(KEY_PROFILES, gson.toJson(list)).commit()
    }
}
