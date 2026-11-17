package com.mg4.control.model

import java.util.UUID

/**
 * Dupliquer un profil (2026-11-08, menu ⋮ de la carte, maquette C). La copie garde tous les
 * réglages — catégories coupées, boutons flottants et écran conducteur compris, qui sont dans le
 * profil — mais pas le téléphone associé : deux profils pour le même téléphone seraient ambigus.
 * Elle n'est ni « par défaut » ni « active » : elle a un nouvel identifiant.
 */
object ProfileCopy {

    /** Longueur maximale d'un nom de profil (champ de l'éditeur). */
    const val NAME_MAX = 30

    /**
     * « Paul Copie », puis « Paul Copie 2 », « Paul Copie 3 »… si le nom est déjà pris (sans tenir
     * compte de la casse). Le nom d'origine est raccourci pour que l'ensemble tienne en [NAME_MAX].
     * [word] : « Copie » dans la langue de l'appli.
     */
    fun name(original: String, word: String, existing: Collection<String>): String {
        val pris = existing.map { it.trim().lowercase() }.toSet()
        var i = 1
        while (true) {
            val suffixe = if (i == 1) " $word" else " $word $i"
            val n = original.trim().take((NAME_MAX - suffixe.length).coerceAtLeast(1)).trimEnd() + suffixe
            if (n.lowercase() !in pris) return n
            i++
        }
    }

    /** La copie de [p] sous le nom [name] : nouvel identifiant, sans téléphone associé. */
    fun duplicate(p: DrivingProfile, name: String, newId: String = UUID.randomUUID().toString()): DrivingProfile =
        p.copy(id = newId, name = name, btDeviceMac = null)

    /** [copy] rangée juste après le profil [afterId] (l'ordre est la priorité Bluetooth) ; à la fin s'il a disparu. */
    fun insertAfter(list: List<DrivingProfile>, afterId: String, copy: DrivingProfile): List<DrivingProfile> {
        val i = list.indexOfFirst { it.id == afterId }
        return list.toMutableList().apply { add(if (i < 0) size else i + 1, copy) }
    }
}
