package com.mg4.control.model

/**
 * Module « charge » de l'écran conducteur (2026-10-16, ancien SDK) : il prend la place des jauges
 * dès qu'une charge est détectée. La règle, sans Android.
 *
 * Sources, décompilées sur SWI133 (`vehiclesettingservice`, `VehicleChargingBinder`) et lues par
 * [com.mg4.control.hardware.EnergyReader.chargeRaw] :
 *  - temps restant : `BMS_CHRG_TIME` 0x2140f417 (`getPredictChargingTime`), la valeur de l'écran
 *    d'origine — supposée en minutes, 1023 = pas de valeur ;
 *  - cible : `BMS_CHRG_TRGT_SOC` 0x2140f40c (`getChargingCloseSoc`), un code de 1 à 7 (bornes
 *    `min/max_bms_chrg_trgt_soc` de l'appli SAIC) — supposé être les sept crans du launcher,
 *    40 à 100 % par dix. Les deux suppositions sont vérifiées par la sonde `MG4_CHARGE`.
 */
object ChargeDisplay {

    /** Sentinelle des grandeurs de charge hors charge (relevé du 2026-09-19). */
    const val SENTINELLE = 1023

    /** Code de cible 1..7 → 40..100 %, ou `null` hors de ces codes. */
    fun ciblePourcent(code: Int?): Int? = code?.takeIf { it in 1..7 }?.let { 30 + 10 * it }

    /** Temps restant en minutes, ou `null` : sentinelle, valeur négative ou illisible. */
    fun minutesRestantes(brut: Int?): Int? = brut?.takeIf { it in 0 until SENTINELLE }

    /**
     * Type de borne d'après les deux signaux de prise (`CCU_ONBD` alternatif, `CCU_OFFBD` continu) :
     * la prise active n'est plus à zéro (2 relevé en alternatif). Le continu d'abord, comme l'onglet
     * Statistiques. `null` si aucun ne le dit.
     */
    fun type(ac: Int?, dc: Int?): ChargeType? = when {
        dc != null && dc != 0 && dc != SENTINELLE -> ChargeType.DC
        ac != null && ac != 0 && ac != SENTINELLE -> ChargeType.AC
        else -> null
    }

    /** Une prise est-elle encore signalée (charge terminée mais câble toujours branché) ? */
    fun priseSignalee(ac: Int?, dc: Int?): Boolean = type(ac, dc) != null

    /** Durée en minutes → (heures, minutes). */
    fun heuresMinutes(minutes: Int): Pair<Int, Int> = (minutes.coerceAtLeast(0) / 60) to (minutes.coerceAtLeast(0) % 60)
}

/**
 * Session de charge vue par l'application : début et énergie entrée dans la batterie, intégrée à
 * chaque relevé de puissance. Un trou de plus de [TROU_MAX_MS] entre deux relevés (boîtier en
 * veille, relevés suspendus) n'est pas intégré : on ne sait pas ce qui s'est passé pendant.
 */
class ChargeEnCours {

    var debutMs: Long? = null
        private set
    var energieKwh = 0f
        private set
    private var dernierMs: Long? = null
    private var dernierKw: Float? = null

    val active: Boolean get() = debutMs != null

    fun commencer(maintenantMs: Long) {
        debutMs = maintenantMs
        energieKwh = 0f
        dernierMs = null
        dernierKw = null
    }

    fun terminer() {
        debutMs = null
        dernierMs = null
        dernierKw = null
    }

    /** [kw] : puissance qui ENTRE dans la batterie (positive en charge) ; `null` = illisible. */
    fun ajouter(maintenantMs: Long, kw: Float?) {
        if (debutMs == null) return
        val avant = dernierMs
        val kwAvant = dernierKw
        if (kw != null && avant != null && kwAvant != null) {
            val dt = maintenantMs - avant
            // Trapèze, et seulement l'énergie qui entre : un instant de décharge n'en retire pas.
            if (dt in 1..TROU_MAX_MS) energieKwh += ((kwAvant + kw) / 2f).coerceAtLeast(0f) * dt / 3_600_000f
        }
        dernierMs = maintenantMs
        dernierKw = kw
    }

    /** Durée depuis le début, en minutes entières. */
    fun dureeMinutes(maintenantMs: Long): Int = debutMs?.let { ((maintenantMs - it) / 60_000L).toInt() } ?: 0

    companion object {
        const val TROU_MAX_MS = 10_000L
    }
}
