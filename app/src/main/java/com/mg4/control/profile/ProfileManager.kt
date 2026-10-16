package com.mg4.control.profile

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.mg4.control.model.AirFlow
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
        /** Dix depuis la 2.6.9 (cinq avant) : la page « Cycle profils » en a deux rangées de cinq. */
        const val MAX_PROFILES = 10

        /**
         * [profils] rangés dans l'ordre de [ids] (glisser-déposer de la liste). Ceux qui n'y
         * figurent pas gardent leur place relative, en fin de liste (tri stable) : un profil créé
         * pendant le glisser n'est pas perdu. Un identifiant inconnu est ignoré.
         */
        fun ordonner(profils: List<DrivingProfile>, ids: List<String>): List<DrivingProfile> {
            val rang = ids.withIndex().associate { it.value to it.index }
            return profils.sortedBy { rang[it.id] ?: Int.MAX_VALUE }
        }

        /**
         * Profil par défaut après la suppression de [supprime] : si c'était lui, le premier des
         * [restants] (ordre de la liste) prend le relais (2026-10-16). Sans relais, la liste
         * n'affichait plus aucun « Par défaut » et le démarrage n'appliquait plus aucun profil.
         * Sinon le défaut ne change pas. `null` quand il ne reste aucun profil.
         */
        fun defautApresSuppression(defaut: String?, supprime: String, restants: List<String>): String? =
            if (defaut == supprime) restants.firstOrNull() else defaut

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
            }.map { p ->
                // Migration : anciennes lignes « Dég. AV / Dég. AR » (retirées le 2026-09-17) →
                // ligne « Air ». Faite à la lecture, comme l'AEB : sans elle, un réglage devenu
                // invisible dans l'éditeur continuerait de s'appliquer sans qu'on puisse le changer.
                // Une ligne Air déjà renseignée l'emporte : c'est le choix le plus récent.
                if (p.hvacDefrostFront == null && p.hvacDefrostRear == null) p
                else p.copy(
                    hvacAirFlow      = p.hvacAirFlow ?: AirFlow.fromLegacyDefrost(p.hvacDefrostFront, p.hvacDefrostRear),
                    hvacDefrostFront = null,
                    hvacDefrostRear  = null
                )
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
            // Profil par défaut supprimé : le premier de la liste le remplace (defautApresSuppression).
            val defaut = prefs.getString(KEY_DEFAULT_ID, null)
            val relais = defautApresSuppression(defaut, profileId, list.map { it.id })
            if (relais != defaut) {
                prefs.edit().apply {
                    if (relais != null) putString(KEY_DEFAULT_ID, relais) else remove(KEY_DEFAULT_ID)
                }.apply()
            }
            // Idem pour le profil ACTIF : le laisser pointer sur un profil disparu ferait
            // résoudre les raccourcis qui lui sont associés sur un identifiant fantôme — ils
            // ne se déclencheraient jamais, sans que rien ne l'explique.
            ActiveProfile.forgetIfMissing(context, list.map { it.id })
        }
        triggerBackup()
    }

    /**
     * Range les profils dans l'ordre de [ids] (glisser-déposer de la liste, 2026-10-15). Cet ordre
     * est celui du sélecteur, du cycle par défaut et, en conflit Bluetooth, la priorité. Un profil
     * absent de [ids] garde sa place relative, en fin de liste (tri stable).
     */
    fun reorder(ids: List<String>) {
        synchronized(MUTATION_LOCK) {
            persist(ordonner(getAll(), ids))
        }
        triggerBackup()
    }

    fun setDefault(profileId: String) {
        prefs.edit().putString(KEY_DEFAULT_ID, profileId).apply()
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
