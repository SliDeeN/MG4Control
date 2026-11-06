package com.mg4.control.service

import android.content.Context
import com.mg4.control.R
import com.mg4.control.hardware.MG4Hardware
import com.mg4.control.hardware.MG4Hardware.Swi68Mode
import com.mg4.control.model.DriveMode
import com.mg4.control.model.QuickSettings
import com.mg4.control.model.QuickTile
import com.mg4.control.model.RegenLevel
import com.mg4.control.util.FirmwareInfo

/**
 * Ce que chaque tuile du pop-up Réglages rapides (issue #197) sait faire : son nom, sa catégorie,
 * ses valeurs, et comment les lire et les écrire sur la voiture — avec les fonctions déjà
 * employées par le tableau de bord et les raccourcis, mêmes valeurs, mêmes voies.
 *
 * Les lectures et écritures passent par des binders : à appeler hors du fil principal.
 */
object QuickTiles {

    private const val PREFS = "mg4_quick_settings"
    private const val KEY = "tiles"

    /** Une valeur proposée : ce qui s'écrit, et son libellé (ressource). */
    class Option(val valeur: Int, val libelle: Int)

    /** Catégories de la page de réglage, dans l'ordre (libellés et pictos des automatisations). */
    enum class Cat(val libelle: Int, val icone: Int) {
        ADAS(R.string.rule_cat_adas, R.drawable.ic_cat_shield),
        DRIVING(R.string.rule_cat_driving, R.drawable.ic_cat_steering),
        BATTERY(R.string.rule_cat_battery, R.drawable.ic_cat_battery),
        CLIMATE(R.string.rule_cat_climate, R.drawable.ic_cat_snow),
        COMFORT(R.string.rule_cat_comfort, R.drawable.ic_cat_seat),
        SCREEN(R.string.rule_cat_screen_sound, R.drawable.ic_cat_screen),
    }

    // ── Liste choisie ─────────────────────────────────────────────────────────

    fun tiles(ctx: Context): List<QuickTile> =
        QuickSettings.decode(ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null))
            .filter { disponible(it) }

    fun save(ctx: Context, tiles: List<QuickTile>) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, QuickSettings.encode(tiles)).apply()
    }

    private const val KEY_VALUES = "values"

    private fun valeursGardees(ctx: Context): Map<QuickTile, List<Int>> =
        QuickSettings.decodeValues(ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_VALUES, null))

    /** Les valeurs que le pop-up propose pour [t] : celles gardées sur la page de réglage, sinon toutes. */
    fun proposees(ctx: Context, t: QuickTile): List<Option> {
        val toutes = options(t)
        val garde = QuickSettings.kept(toutes.map { it.valeur }, valeursGardees(ctx)[t])
        return toutes.filter { it.valeur in garde }
    }

    /** Propose ou retire la valeur [v] de [t] (deux au moins) ; enregistré aussitôt. */
    fun basculerValeur(ctx: Context, t: QuickTile, v: Int) {
        val toutes = options(t).map { it.valeur }
        val m = valeursGardees(ctx).toMutableMap()
        val l = QuickSettings.toggleValue(toutes, m[t], v)
        if (l == toutes) m.remove(t) else m[t] = l
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_VALUES, QuickSettings.encodeValues(m)).apply()
    }

    // ── Description ───────────────────────────────────────────────────────────

    fun nom(t: QuickTile): Int = when (t) {
        QuickTile.ELK_MODE -> R.string.quick_tile_elk
        QuickTile.ELK_SENSITIVITY -> R.string.quick_tile_elk_sens
        QuickTile.CRUISE -> R.string.quick_tile_cruise
        QuickTile.AEB -> R.string.quick_tile_aeb
        QuickTile.TSR -> R.string.quick_tile_tsr
        QuickTile.OVERSPEED_ALARM -> R.string.quick_tile_overspeed
        QuickTile.AUTO_HIGH_BEAM -> R.string.quick_tile_high_beam
        QuickTile.REGEN -> R.string.quick_tile_regen
        QuickTile.DRIVE_MODE -> R.string.quick_tile_drive
        QuickTile.ENERGY_SAVING -> R.string.quick_tile_energy
        QuickTile.BATTERY_HEAT -> R.string.quick_tile_battery_heat
        QuickTile.AC -> R.string.quick_tile_ac
        QuickTile.RECIRC -> R.string.quick_tile_recirc
        QuickTile.DEFROST_FRONT -> R.string.quick_tile_defrost_front
        QuickTile.DEFROST_REAR -> R.string.quick_tile_defrost_rear
        QuickTile.SEAT_LEFT -> R.string.quick_tile_seat_left
        QuickTile.SEAT_RIGHT -> R.string.quick_tile_seat_right
        QuickTile.STEERING_HEAT -> R.string.quick_tile_steering
        QuickTile.BRIGHTNESS -> R.string.quick_tile_brightness
    }

    fun cat(t: QuickTile): Cat = when (t) {
        QuickTile.ELK_MODE, QuickTile.ELK_SENSITIVITY, QuickTile.CRUISE, QuickTile.AEB, QuickTile.TSR,
        QuickTile.OVERSPEED_ALARM, QuickTile.AUTO_HIGH_BEAM -> Cat.ADAS
        QuickTile.REGEN, QuickTile.DRIVE_MODE, QuickTile.ENERGY_SAVING -> Cat.DRIVING
        QuickTile.BATTERY_HEAT -> Cat.BATTERY
        QuickTile.AC, QuickTile.RECIRC, QuickTile.DEFROST_FRONT, QuickTile.DEFROST_REAR -> Cat.CLIMATE
        QuickTile.SEAT_LEFT, QuickTile.SEAT_RIGHT, QuickTile.STEERING_HEAT -> Cat.COMFORT
        QuickTile.BRIGHTNESS -> Cat.SCREEN
    }

    /** La voiture refuse ce changement en roulant ([com.mg4.control.hardware.VehicleWriteGate], AEB). */
    fun aLArret(t: QuickTile): Boolean = t == QuickTile.AEB

    /** Mêmes conditions que les cartes du tableau de bord et le catalogue des raccourcis. */
    fun disponible(t: QuickTile): Boolean {
        val connu = FirmwareInfo.getGeneration() != FirmwareInfo.Gen.UNKNOWN
        return when (t) {
            QuickTile.OVERSPEED_ALARM -> connu && (!FirmwareInfo.isVsmBased() || FirmwareInfo.getGeneration() == FirmwareInfo.Gen.SWI132)
            QuickTile.AUTO_HIGH_BEAM -> MG4Hardware.hasAutoHighBeam()
            QuickTile.BATTERY_HEAT -> MG4Hardware.hasBatteryHeating()
            QuickTile.AC, QuickTile.RECIRC, QuickTile.DEFROST_FRONT, QuickTile.DEFROST_REAR -> MG4Hardware.hasClimateControl()
            QuickTile.SEAT_LEFT, QuickTile.SEAT_RIGHT, QuickTile.STEERING_HEAT -> FirmwareInfo.hasHeatFeatures()
            QuickTile.BRIGHTNESS -> MG4Hardware.hasBrightnessControl()
            else -> connu
        }
    }

    private val OUI_NON = listOf(Option(1, R.string.segment_yes), Option(0, R.string.segment_no))

    fun options(t: QuickTile): List<Option> = when (t) {
        QuickTile.ELK_MODE -> listOf(Option(1, R.string.regen_off), Option(2, R.string.elk_mode_alert),
            Option(3, R.string.elk_mode_assist), Option(5, R.string.elk_mode_emergency))
        QuickTile.ELK_SENSITIVITY -> listOf(Option(1, R.string.elk_sensitivity_low), Option(2, R.string.elk_sensitivity_standard),
            Option(3, R.string.elk_sensitivity_high))
        // Indices de l'écran ADAS : 0 Off, 1 limiteur manuel, 2 limiteur auto, 3 ACC, 4 ICA (TJA sur VSM).
        QuickTile.CRUISE -> listOf(Option(0, R.string.adas_off), Option(1, R.string.adas_limiteur_short), Option(2, R.string.adas_auto),
            Option(3, R.string.adas_acc), Option(4, if (FirmwareInfo.isVsmBased()) R.string.adas_tja else R.string.adas_ica))
        QuickTile.REGEN -> listOf(RegenLevel.OFF to R.string.regen_off, RegenLevel.LOW to R.string.regen_low,
            RegenLevel.MEDIUM to R.string.regen_medium, RegenLevel.HIGH to R.string.regen_high,
            RegenLevel.ADAPTIVE to R.string.regen_adaptive, RegenLevel.ONE_PEDAL to R.string.regen_one_pedal)
            .map { (n, l) -> Option(n.value, l) }
        QuickTile.DRIVE_MODE -> listOf(DriveMode.ECO to R.string.drive_eco, DriveMode.NORMAL to R.string.drive_normal,
            DriveMode.SPORT to R.string.drive_sport, DriveMode.SNOW to R.string.drive_snow, DriveMode.CUSTOM to R.string.drive_custom)
            .map { (m, l) -> Option(m.value, l) }
        QuickTile.RECIRC -> listOf(Option(MG4Hardware.LoopMode.INNER, R.string.recirc_inner),
            Option(MG4Hardware.LoopMode.OUTSIDE, R.string.recirc_outside), Option(MG4Hardware.LoopMode.AUTO, R.string.recirc_auto))
        QuickTile.SEAT_LEFT, QuickTile.SEAT_RIGHT -> listOf(Option(0, R.string.rule_seat_off), Option(1, R.string.quick_level_1),
            Option(2, R.string.quick_level_2), Option(3, R.string.quick_level_3))
        QuickTile.BRIGHTNESS -> listOf(20, 40, 60, 80, 100).map { Option(it, R.string.quick_tile_brightness) }
        else -> OUI_NON
    }

    /** Libellé d'une option (les pourcentages de la luminosité s'écrivent tels quels). */
    fun libelle(ctx: Context, t: QuickTile, o: Option): String =
        if (t == QuickTile.BRIGHTNESS) "${o.valeur} %" else ctx.getString(o.libelle)

    // ── Voiture ───────────────────────────────────────────────────────────────

    private fun b(v: Boolean?): Int? = v?.let { if (it) 1 else 0 }

    /** Valeur actuelle, parmi celles de [options] ; null si illisible. Bloquant. */
    fun lire(t: QuickTile): Int? = runCatching {
        when (t) {
            QuickTile.ELK_MODE -> MG4Hardware.getElkMode().takeIf { it > 0 }
            QuickTile.ELK_SENSITIVITY -> MG4Hardware.getElkSensitivity().takeIf { it > 0 }
            QuickTile.CRUISE -> indexAdas()
            QuickTile.AEB -> b(MG4Hardware.isAebEnabled())
            QuickTile.TSR -> b(MG4Hardware.isTsrOn())
            QuickTile.OVERSPEED_ALARM -> b(MG4Hardware.isOverspeedAlarmOn())
            QuickTile.AUTO_HIGH_BEAM -> b(MG4Hardware.isAutoHighBeamOn())
            QuickTile.REGEN -> MG4Hardware.getRegenLevel()?.value
            QuickTile.DRIVE_MODE -> MG4Hardware.getDriveMode()?.value
            QuickTile.ENERGY_SAVING -> b(MG4Hardware.isEnergySavingOn())
            QuickTile.BATTERY_HEAT -> b(MG4Hardware.isBatteryHeatingOn())
            QuickTile.AC -> b(MG4Hardware.getClimateState()?.acOn)
            QuickTile.RECIRC -> MG4Hardware.getClimateState()?.loopMode
            QuickTile.DEFROST_FRONT -> b(MG4Hardware.getClimateState()?.defrostFront)
            QuickTile.DEFROST_REAR -> b(MG4Hardware.getClimateState()?.defrostRear)
            QuickTile.SEAT_LEFT -> MG4Hardware.getSeatHeatLeftOrNull()
            QuickTile.SEAT_RIGHT -> MG4Hardware.getSeatHeatRightOrNull()
            QuickTile.STEERING_HEAT -> b(MG4Hardware.getSteeringHeatOrNull())
            // La plus proche des valeurs proposées : 63 % s'affiche sur 60.
            QuickTile.BRIGHTNESS -> MG4Hardware.getScreenBrightnessPercent(journal = false).takeIf { it >= 0 }
                ?.let { p -> options(t).minByOrNull { kotlin.math.abs(it.valeur - p) }?.valeur }
        }
    }.getOrNull()

    /** Écrit [v] ; faux si la voiture (ou le garde de conduite) le refuse. Bloquant. */
    fun ecrire(t: QuickTile, v: Int): Boolean = runCatching {
        val on = v == 1
        when (t) {
            QuickTile.ELK_MODE -> MG4Hardware.setElkMode(v)
            QuickTile.ELK_SENSITIVITY -> MG4Hardware.setElkSensitivity(v)
            QuickTile.CRUISE -> { ecrireAdas(v); true }
            QuickTile.AEB -> MG4Hardware.setAebEnabled(on)
            QuickTile.TSR -> MG4Hardware.setTsrMode(on)
            QuickTile.OVERSPEED_ALARM -> MG4Hardware.setOverspeedAlarm(on)
            QuickTile.AUTO_HIGH_BEAM -> MG4Hardware.setAutoHighBeam(on)
            QuickTile.REGEN -> MG4Hardware.setRegenLevel(RegenLevel.fromValue(v))
            QuickTile.DRIVE_MODE -> DriveMode.fromValueOrNull(v)?.let { MG4Hardware.setDriveMode(it) } ?: false
            QuickTile.ENERGY_SAVING -> MG4Hardware.setEnergySavingMode(on)
            QuickTile.BATTERY_HEAT -> MG4Hardware.setBatteryHeating(on)
            QuickTile.AC -> MG4Hardware.setClimateAc(on)
            QuickTile.RECIRC -> MG4Hardware.setClimateLoopMode(v)
            QuickTile.DEFROST_FRONT -> MG4Hardware.setClimateDefrostFront(on)
            QuickTile.DEFROST_REAR -> MG4Hardware.setClimateDefrostRear(on)
            QuickTile.SEAT_LEFT -> MG4Hardware.setSeatHeatLeft(v)
            QuickTile.SEAT_RIGHT -> MG4Hardware.setSeatHeatRight(v)
            QuickTile.STEERING_HEAT -> MG4Hardware.setSteeringHeat(on)
            QuickTile.BRIGHTNESS -> MG4Hardware.setScreenBrightnessPercent(v)
        }
    }.getOrDefault(false)

    /** Index ADAS courant, même conversion que l'écran ADAS et le raccourci « cycle ADAS ». */
    private fun indexAdas(): Int? {
        if (!FirmwareInfo.isVsmBased()) return MG4Hardware.getMixedIntelligentDrive().takeIf { it >= 0 }
        val accTja = MG4Hardware.getAccTjaMode().takeIf { it >= 0 } ?: return null
        val sas = MG4Hardware.getSpeedLimiterMode()
        return when {
            sas == MG4Hardware.SasMode.MANUEL -> 1
            sas == MG4Hardware.SasMode.INTELLIGENT -> 2
            accTja == Swi68Mode.ACC -> 3
            accTja == Swi68Mode.TJA -> 4
            else -> 0
        }
    }

    /** Sur VSM, mode ACC/TJA et limiteur sont exclusifs : les deux sont posés à chaque fois. */
    private fun ecrireAdas(index: Int) {
        if (!FirmwareInfo.isVsmBased()) { MG4Hardware.setMixedIntelligentDrive(index); return }
        when (index) {
            1 -> { MG4Hardware.setSpeedLimiterMode(MG4Hardware.SasMode.MANUEL); MG4Hardware.setAccTjaMode(Swi68Mode.OFF) }
            2 -> { MG4Hardware.setSpeedLimiterMode(MG4Hardware.SasMode.INTELLIGENT); MG4Hardware.setAccTjaMode(Swi68Mode.OFF) }
            3 -> { MG4Hardware.setAccTjaMode(Swi68Mode.ACC); MG4Hardware.setSpeedLimiterMode(MG4Hardware.SasMode.OFF) }
            4 -> { MG4Hardware.setAccTjaMode(Swi68Mode.TJA); MG4Hardware.setSpeedLimiterMode(MG4Hardware.SasMode.OFF) }
            else -> { MG4Hardware.setAccTjaMode(Swi68Mode.OFF); MG4Hardware.setSpeedLimiterMode(MG4Hardware.SasMode.OFF) }
        }
    }
}
