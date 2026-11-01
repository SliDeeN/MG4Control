package com.mg4.control.util

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.mg4.control.model.CustomShortcut
import com.mg4.control.model.CustomShortcuts
import java.util.UUID

/**
 * Raccourcis perso (issue #172) : la liste, en JSON, commune à tous les profils — un bouton du
 * volant, un raccourci avancé ou un bouton flottant ne garde que l'identifiant du raccourci perso
 * qu'il lance : le modifier ici le modifie partout.
 */
object CustomShortcutSettings {

    /** Même fichier que les autres réglages. */
    private const val PREFS = "mg4_settings"
    private const val KEY_LIST = "custom_shortcuts"

    private val gson = Gson()
    private val type = object : TypeToken<List<CustomShortcut>>() {}.type

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun all(ctx: Context): List<CustomShortcut> = try {
        prefs(ctx).getString(KEY_LIST, null)?.let { gson.fromJson<List<CustomShortcut>>(it, type) }
            ?.filterNotNull()?.take(CustomShortcuts.MAX) ?: emptyList()
    } catch (_: Exception) {
        emptyList()
    }

    fun get(ctx: Context, id: String?): CustomShortcut? = id?.let { i -> all(ctx).firstOrNull { it.id == i } }

    fun newId(): String = UUID.randomUUID().toString()

    /** Ajoute ou remplace [c] ; `false` si la liste est pleine. */
    fun save(ctx: Context, c: CustomShortcut): Boolean {
        val liste = all(ctx)
        val nouvelle = if (liste.any { it.id == c.id }) liste.map { if (it.id == c.id) c else it }
            else if (liste.size >= CustomShortcuts.MAX) return false else liste + c
        prefs(ctx).edit().putString(KEY_LIST, gson.toJson(nouvelle)).apply()
        return true
    }

    /** Un bouton qui le portait ne lance plus rien : il le dit au premier appui. */
    fun delete(ctx: Context, id: String) {
        prefs(ctx).edit().putString(KEY_LIST, gson.toJson(all(ctx).filter { it.id != id })).apply()
    }
}
