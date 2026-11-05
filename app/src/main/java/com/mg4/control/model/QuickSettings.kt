package com.mg4.control.model

/**
 * Pop-up Réglages rapides (issue #197, maquette C du 2026-11-05) : les tuiles que l'utilisateur a
 * choisies, dans son ordre, 8 au plus. Ici seulement la liste et sa sauvegarde ; ce que chaque
 * tuile lit et écrit sur la voiture est dans `service/QuickTiles.kt`.
 */
enum class QuickTile {
    ELK_MODE, ELK_SENSITIVITY,
    /** ACC / TJA (ICA) et limiteur : un seul réglage, la voiture les rend exclusifs (comme l'écran ADAS). */
    CRUISE,
    AEB, TSR, OVERSPEED_ALARM, AUTO_HIGH_BEAM,
    /** La récupération comprend la conduite à une pédale, qui est l'un de ses niveaux. */
    REGEN, DRIVE_MODE, ENERGY_SAVING,
    BATTERY_HEAT,
    AC, RECIRC, DEFROST_FRONT, DEFROST_REAR,
    SEAT_LEFT, SEAT_RIGHT, STEERING_HEAT,
    BRIGHTNESS,
}

object QuickSettings {

    const val MAX_TILES = 8

    /**
     * Les réglages cités par l'issue #197 — sortie de voie, ACC / TJA et limiteur (une tuile),
     * récupération, chauffe de la batterie — plus le mode de conduite.
     */
    val DEFAULT = listOf(QuickTile.ELK_MODE, QuickTile.CRUISE, QuickTile.REGEN, QuickTile.BATTERY_HEAT, QuickTile.DRIVE_MODE)

    /** « ELK_MODE,CRUISE,… » ; null = jamais réglé (les tuiles par défaut). */
    fun encode(tiles: List<QuickTile>): String = tiles.joinToString(",") { it.name }

    /**
     * Relu tel quel, sans doublon ni nom inconnu (tuile d'une version plus récente), et coupé à
     * [MAX_TILES]. Une chaîne vide est une liste vide : l'utilisateur a tout retiré.
     */
    fun decode(texte: String?): List<QuickTile> {
        texte ?: return DEFAULT
        return texte.split(',').mapNotNull { n -> QuickTile.entries.firstOrNull { it.name == n.trim() } }
            .distinct().take(MAX_TILES)
    }

    /** [tile] ajoutée à la fin, si elle n'y est pas et qu'il reste de la place. */
    fun ajouter(tiles: List<QuickTile>, tile: QuickTile): List<QuickTile> =
        if (tile in tiles || tiles.size >= MAX_TILES) tiles else tiles + tile

    /** La tuile du rang [de] passe au rang [vers]. */
    fun deplacer(tiles: List<QuickTile>, de: Int, vers: Int): List<QuickTile> {
        if (de !in tiles.indices || vers !in tiles.indices || de == vers) return tiles
        return tiles.toMutableList().apply { add(vers, removeAt(de)) }
    }
}
