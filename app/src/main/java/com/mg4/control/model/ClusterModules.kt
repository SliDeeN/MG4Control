package com.mg4.control.model

/**
 * Modules de l'écran conducteur créés par l'utilisateur (2026-11-20, prototype validé) : une
 * bibliothèque commune à tous les profils, et pour chaque profil une liste ordonnée de modules par
 * onglet (Navigation, Musique). Le premier s'affiche ; le raccourci « Module suivant » passe au
 * suivant ([ClusterRotation]).
 *
 * Un module porte tout ce qui le dessine : ce qu'il montre au centre ([type] : consommation,
 * puissance, pneus), son style, son unité de consommation et sa disposition (cases, place de la
 * jauge, épaisseur, couleurs…). Kotlin pur.
 */
data class ClusterModuleDef(
    val id: String,
    val name: String,
    /** Clé de [ClusterModule]. */
    val type: String = ClusterModule.CONSUMPTION.key,
    /** Clé de [ClusterGaugeStyle] ; `null` = dynamique. */
    val style: String? = null,
    /** Clé de [ConsumptionUnit] ; `null` = Wh/km. */
    val unit: String? = null,
    /** `null` = la disposition par défaut de son type ([ClusterModuleDefs.layout]). */
    val layout: ClusterLayout? = null,
    /** Graphique (issue #217) : clé de [ClusterHistory.Info] ; `null` = consommation. */
    val graphInfo: String? = null,
    /** Graphique : distance montrée, 20, 50 ou 100 km ; `null` = 50. */
    val graphKm: Int? = null,
    /** Graphique : une courbe au lieu des barres. */
    val graphLine: Boolean? = null,
    /** Graphique : ligne de la moyenne ; `null` = affichée. */
    val graphAverage: Boolean? = null,
    /** Graphique : lignes du minimum et du maximum ; `null` = affichées (2026-11-20). */
    val graphMinMax: Boolean? = null,
    /** Graphique : couleur des barres hors consommation et récupération ; `null` = gris clair. */
    val graphColor: Int? = null,
    /** Graphique : nombre de points au plus, 10 à 50 ; `null` = 50 (2026-11-20). */
    val graphPoints: Int? = null,
)

object ClusterModuleDefs {

    fun module(d: ClusterModuleDef): ClusterModule = ClusterModule.fromKey(d.type)

    /** Disposition d'un module : la sienne, sinon celle d'avant ; les pneus, sans case (tout le carré). */
    fun layout(d: ClusterModuleDef): ClusterLayout = d.layout ?: defaultLayout(module(d), d.style, d.unit)

    fun defaultLayout(type: ClusterModule, style: String? = null, unit: String? = null): ClusterLayout =
        if (type == ClusterModule.TYRES) ClusterLayout(gauge = ClusterLayouts.GAUGE_MIDDLE, slots = List(ClusterLayouts.SLOTS) { null })
        else ClusterLayouts.defaults(ClusterConfig(style = style, unit = unit))

    /**
     * Réglages équivalents pour dessiner un module avec le code d'avant (aperçu, combiné) : style,
     * unité et disposition du module, le reste — puissance maxi, actualisation, animation — de [commun].
     */
    fun asConfig(d: ClusterModuleDef, commun: ClusterConfig = ClusterConfig()): ClusterConfig =
        commun.copy(style = d.style, unit = d.unit, layout = layout(d), layoutMedia = null)

    /** Modules de [tab] pour ces réglages de profil, dans l'ordre ; les identifiants perdus sont écartés. */
    fun listFor(c: ClusterConfig, tab: ClusterTab, library: List<ClusterModuleDef>): List<ClusterModuleDef> =
        ids(c, tab).mapNotNull { id -> library.firstOrNull { it.id == id } }

    fun ids(c: ClusterConfig, tab: ClusterTab): List<String> = when (tab) {
        ClusterTab.NAVIGATION -> c.navModules.orEmpty()
        ClusterTab.MEDIA -> c.mediaModules.orEmpty()
        else -> emptyList()
    }

    fun withIds(c: ClusterConfig, tab: ClusterTab, ids: List<String>): ClusterConfig = when (tab) {
        ClusterTab.NAVIGATION -> c.copy(navModules = ids)
        ClusterTab.MEDIA -> c.copy(mediaModules = ids)
        else -> c
    }

    /** Le module retiré de toutes les listes (suppression). */
    fun without(c: ClusterConfig, id: String): ClusterConfig =
        c.copy(navModules = c.navModules?.filter { it != id }, mediaModules = c.mediaModules?.filter { it != id })

    /** La liste avec l'élément [i] déplacé de [delta] (−1 = avancer, +1 = reculer) ; inchangée en bout de liste. */
    fun move(ids: List<String>, i: Int, delta: Int): List<String> {
        val j = i + delta
        if (i !in ids.indices || j !in ids.indices) return ids
        return ids.toMutableList().also { val x = it[i]; it[i] = it[j]; it[j] = x }
    }

    /**
     * Reprise des réglages d'avant (une fois) : pour chaque profil et chaque onglet qui avait un
     * module, un module de la bibliothèque — type, style, unité et disposition de cet onglet. Deux
     * onglets ou profils identiques partagent le même module. Les noms viennent de [nom] (le type),
     * numérotés s'il y en a plusieurs du même type. Rien ne change à l'écran.
     *
     * @return la bibliothèque et les réglages de chaque profil avec leurs listes, dans le même ordre
     */
    fun migrate(
        configs: List<ClusterConfig>, newId: () -> String, nom: (ClusterModule) -> String,
    ): Pair<List<ClusterModuleDef>, List<ClusterConfig>> {
        val biblio = mutableListOf<ClusterModuleDef>()
        val repris = configs.map { c ->
            fun pour(tab: ClusterTab): List<String> {
                val type = moduleOf(c, tab) ?: return emptyList()
                val brouillon = ClusterModuleDef("", "", type.key, c.style, c.unit, ClusterLayouts.of(c, tab))
                val meme = biblio.firstOrNull { it.copy(id = "", name = "") == brouillon }
                if (meme != null) return listOf(meme.id)
                val deja = biblio.count { it.type == type.key }
                val def = brouillon.copy(id = newId(), name = nom(type) + if (deja > 0) " ${deja + 1}" else "")
                biblio += def
                return listOf(def.id)
            }
            c.copy(navModules = c.navModules ?: pour(ClusterTab.NAVIGATION), mediaModules = c.mediaModules ?: pour(ClusterTab.MEDIA))
        }
        return biblio to repris
    }

    /** Le module d'avant de [tab] (avant la bibliothèque) : « none » ou absent = aucun. */
    private fun moduleOf(c: ClusterConfig, tab: ClusterTab): ClusterModule? {
        val key = when (tab) { ClusterTab.NAVIGATION -> c.navigation; ClusterTab.MEDIA -> c.media; else -> null }
        return ClusterModule.entries.firstOrNull { it.key == key }
    }
}

/**
 * Module affiché par profil et par onglet (raccourci « Module suivant », 2026-11-20). En mémoire :
 * un redémarrage de l'écran repart du premier. Changer de profil montre le module où ce profil en
 * était — le premier la première fois. Kotlin pur ; l'appelant synchronise.
 */
class ClusterRotation {
    private val rangs = HashMap<String, Int>()

    private fun cle(profil: String?, tab: ClusterTab) = "${profil ?: "-"}:${tab.name}"

    /** Rang du module à montrer parmi [taille] ; 0 sans liste. */
    fun index(profil: String?, tab: ClusterTab, taille: Int): Int =
        if (taille <= 0) 0 else (rangs[cle(profil, tab)] ?: 0).mod(taille)

    /** Passe au suivant (puis revient au premier) ; renvoie le nouveau rang. */
    fun next(profil: String?, tab: ClusterTab, taille: Int): Int {
        if (taille <= 0) return 0
        val n = (index(profil, tab, taille) + 1).mod(taille)
        rangs[cle(profil, tab)] = n
        return n
    }

    fun reset() = rangs.clear()
}
