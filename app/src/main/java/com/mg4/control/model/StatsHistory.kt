package com.mg4.control.model

/**
 * Historique enregistré : trajets et sessions de charge.
 *
 * Volontairement dans `model` plutôt qu'à côté du magasin : le fichier `proguard-rules.pro`
 * conserve tout ce paquet, champs compris. Placée ailleurs, cette classe voyait son type générique
 * `List<Trip>` effacé par R8, et Gson rendait alors une liste de `LinkedTreeMap` — la release
 * plantait à la première ouverture de l'onglet, exactement comme les profils l'avaient fait.
 */
data class StatsHistory(
    val trips: List<Trip> = emptyList(),
    val charges: List<ChargeSession> = emptyList(),
)
