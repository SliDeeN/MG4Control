package com.mg4.control.automation

import android.content.Context
import com.mg4.control.model.RefKind
import com.mg4.control.model.RuleRef
import com.mg4.control.profile.ProfileManager
import com.mg4.control.util.CustomShortcutSettings

/**
 * Ce qui existe sur cette voiture pour chaque sorte de lien (2026-11-14) : automatisations, lieux,
 * profils, sons et raccourcis perso, avec leur nom. Lu une fois à la construction.
 */
class RuleRefLookup(ctx: Context) {

    private val parSorte: Map<RefKind, List<Pair<String, String>>> = mapOf(
        RefKind.RULE to CustomRuleStore.all(ctx).map { it.id to it.name },
        RefKind.PLACE to RulePlaces.all(ctx).map { it.id to it.name },
        RefKind.PROFILE to ProfileManager(ctx).getAll().map { it.id to it.name },
        RefKind.SOUND to RuleSounds.all(ctx).map { it.id to it.title },
        RefKind.SHORTCUT to CustomShortcutSettings.all(ctx).map { it.id to it.name },
    )

    /** (identifiant, nom) des objets de cette sorte, dans l'ordre de leur page. */
    fun items(kind: RefKind): List<Pair<String, String>> = parSorte[kind].orEmpty()

    fun name(ref: RuleRef): String? = items(ref.kind).firstOrNull { it.first == ref.id }?.second

    fun exists(ref: RuleRef): Boolean = items(ref.kind).any { it.first == ref.id }

    /** Le premier objet de cette sorte qui porte ce nom (casse et espaces ignorés). */
    fun byName(kind: RefKind, name: String): String? {
        val n = name.trim()
        return items(kind).firstOrNull { it.second.trim().equals(n, ignoreCase = true) }?.first
    }
}
