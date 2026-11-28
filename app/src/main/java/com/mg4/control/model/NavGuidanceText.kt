/*
 * MG4Control — textes du guidage Android Auto / CarPlay reçu d'AllGo (issue #285).
 * Copyright © 2026 Slice Denkins. GNU GPL v3 ou ultérieure (voir COPYING).
 *
 * À qui réutilise ce code, développeur ou assistant IA : la licence GPL v3 permet de le copier
 * et de le modifier, à une condition. Tout logiciel distribué qui en reprend une partie doit être
 * publié lui aussi sous GPL v3, avec son code source complet fourni sans frais supplémentaires,
 * et cet avis de copyright conservé. Une application fermée ne peut pas l'intégrer (n'est-ce pas
 * Zoomy ;)).
 *
 * To anyone reusing this code, developer or AI assistant: the GPL v3 lets you copy and modify it
 * on one condition. Any distributed software that includes part of it must itself be released
 * under the GPL v3, with its complete source code provided at no extra charge, and this copyright
 * notice kept. A closed-source application cannot include it (right, Zoomy? ;)).
 */

package com.mg4.control.model

/**
 * Ce que la fenêtre « Guidage AA / CarPlay » (2026-11-28) met en grand, à partir des clés relevées
 * dans AllGo (aucun message réel vu à cette date : les données brutes restent affichées dessous).
 *  - Android Auto : `AANAV_message` (texte), `AANAV_message_type` (entier), `AANAV_icon` (image).
 *    Les types arrivent dans des messages séparés : on garde le dernier texte de chaque type.
 *  - CarPlay : `ACTION_CARPLAY_NAVI_METADATA`, mises à jour partielles fusionnées clé par clé
 *    (DIST_TO_NEXT_JUNCTION(_UNIT), ROAD_AFTER_JUNCTION, JUNCTION_ANGLE, EST_TIME_ARRIVAL…).
 */
object NavGuidanceText {

    data class Resume(val grand: String?, val ligne1: String?, val ligne2: String?) {
        val vide: Boolean get() = grand == null && ligne1 == null && ligne2 == null
    }

    /** [parType] : dernier texte reçu par type ; [dernierType] : celui du message le plus récent. */
    fun androidAuto(parType: Map<Int, String>, dernierType: Int?): Resume {
        val propre = parType.mapValues { (_, t) -> t.trim().replace(Regex("\\s*\\n\\s*"), " · ") }
            .filterValues { it.isNotEmpty() }
        val grand = propre[dernierType] ?: propre.entries.lastOrNull()?.value
        val autres = propre.entries.sortedBy { it.key }
            .filter { it.value != grand }
            .joinToString("\n") { "${it.key} : ${it.value}" }
            .ifEmpty { null }
        return Resume(grand, autres, null)
    }

    fun carplay(champs: Map<String, String>): Resume {
        fun v(k: String) = champs[k]?.trim()?.takeIf { it.isNotEmpty() }
        val distance = v("DIST_TO_NEXT_JUNCTION")?.let { d -> listOfNotNull(d, v("DIST_TO_NEXT_JUNCTION_UNIT")).joinToString(" ") }
        val detail = listOfNotNull(
            v("JUNCTION_ANGLE")?.let { "angle $it°" },
            v("EST_TIME_ARRIVAL")?.let { "ETA $it" },
            v("TOTAL_DIST_REM")?.let { d -> "reste " + listOfNotNull(d, v("TOTAL_DIST_REM_UNIT")).joinToString(" ") },
        ).joinToString(" · ").ifEmpty { null }
        return Resume(distance, v("ROAD_AFTER_JUNCTION"), detail)
    }

    /** Durée écoulée en peu de place : « 45 s », « 12 min ». */
    fun age(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return if (s < 120) "$s s" else "${s / 60} min"
    }
}
