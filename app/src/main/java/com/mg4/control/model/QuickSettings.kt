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

    // ── Valeurs proposées (variante B du 2026-11-06) ─────────────────────────
    // Pour une tuile à plus de deux valeurs, l'utilisateur garde celles qu'il veut voir dans le
    // pop-up (deux au moins) ; à deux, la tuile bascule d'un seul toucher. Par défaut : toutes.

    /** « REGEN:2,5;ELK_MODE:1,5 » ; une tuile absente garde toutes ses valeurs. */
    fun encodeValues(kept: Map<QuickTile, List<Int>>): String =
        kept.entries.joinToString(";") { (t, l) -> t.name + ":" + l.joinToString(",") }

    fun decodeValues(texte: String?): Map<QuickTile, List<Int>> {
        if (texte.isNullOrBlank()) return emptyMap()
        return texte.split(';').mapNotNull { part ->
            val (nom, vals) = part.split(':', limit = 2).takeIf { it.size == 2 } ?: return@mapNotNull null
            val t = QuickTile.entries.firstOrNull { it.name == nom.trim() } ?: return@mapNotNull null
            t to vals.split(',').mapNotNull { it.trim().toIntOrNull() }
        }.toMap()
    }

    /**
     * Les valeurs proposées parmi [all] (dans leur ordre) : celles gardées, ou toutes s'il en reste
     * moins de deux (valeur disparue avec une version, réglage jamais fait).
     */
    fun kept(all: List<Int>, kept: List<Int>?): List<Int> {
        val l = all.filter { kept == null || it in kept }
        return if (l.size < 2) all else l
    }

    /** Propose ou retire [v] ; jamais sous deux valeurs. */
    fun toggleValue(all: List<Int>, kept: List<Int>?, v: Int): List<Int> {
        val actuel = kept(all, kept)
        return if (v in actuel) { if (actuel.size > 2) actuel - v else actuel }
        else all.filter { it in actuel || it == v }
    }

    /** À deux valeurs, le toucher passe à l'autre ; une valeur inconnue ou hors liste mène à la première. */
    fun nextOfTwo(pair: List<Int>, current: Int?): Int = if (current == pair[0]) pair[1] else pair[0]

    /** La tuile du rang [de] passe au rang [vers]. */
    fun deplacer(tiles: List<QuickTile>, de: Int, vers: Int): List<QuickTile> {
        if (de !in tiles.indices || vers !in tiles.indices || de == vers) return tiles
        return tiles.toMutableList().apply { add(vers, removeAt(de)) }
    }
}
