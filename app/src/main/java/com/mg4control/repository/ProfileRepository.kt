package com.mg4control.repository

import android.content.Context
import android.content.SharedPreferences
import com.mg4control.model.VehicleProfile

private const val PREFS_NAME = "mg4_profiles"
private const val KEY_PROFILE = "profile_"        // + id (1,2,3)
private const val MAX_PROFILES = 3

class ProfileRepository(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ── Lecture ───────────────────────────────────────────────────────────

    fun getAll(): List<VehicleProfile> {
        return (1..MAX_PROFILES).map { id ->
            val json = prefs.getString("$KEY_PROFILE$id", null)
            json?.let { VehicleProfile.fromJson(it) } ?: VehicleProfile.empty(id)
        }
    }

    fun getById(id: Int): VehicleProfile {
        val json = prefs.getString("$KEY_PROFILE$id", null)
        return json?.let { VehicleProfile.fromJson(it) } ?: VehicleProfile.empty(id)
    }

    fun getFavorite(): VehicleProfile? = getAll().firstOrNull { it.isFavorite }

    // ── Écriture ──────────────────────────────────────────────────────────

    fun save(profile: VehicleProfile) {
        prefs.edit().putString("$KEY_PROFILE${profile.id}", profile.toJson()).apply()
    }

    /** Définir un profil comme favori — retire le favori des autres */
    fun setFavorite(id: Int) {
        getAll().forEach { p ->
            val updated = p.copy(isFavorite = (p.id == id))
            save(updated)
        }
    }

    fun clearFavorite() {
        getAll().forEach { p -> save(p.copy(isFavorite = false)) }
    }

    /** Renommer un profil */
    fun rename(id: Int, newName: String) {
        val profile = getById(id)
        save(profile.copy(name = newName.trim().ifEmpty { "Profil $id" }))
    }

    /** Sauvegarder l'état courant du véhicule dans un profil existant */
    fun saveCurrentState(
        id: Int,
        driveMode: Int,
        regenLevel: Int,
        onePedal: Boolean,
        overspeedAlarm: Boolean,
        speedLimitChangeTone: Boolean
    ) {
        val existing = getById(id)
        save(existing.copy(
            driveMode            = driveMode,
            regenLevel           = regenLevel,
            onePedal             = onePedal,
            overspeedAlarm       = overspeedAlarm,
            speedLimitChangeTone = speedLimitChangeTone
        ))
    }
}
