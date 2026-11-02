package com.mg4.control.service

import android.content.Context
import com.mg4.control.api.ExternalApi
import com.mg4.control.debug.AppLogger
import com.mg4.control.hardware.MG4Hardware
import com.mg4.control.model.DriveMode
import com.mg4.control.model.RegenLevel
import com.mg4.control.profile.ProfileApplier
import com.mg4.control.profile.ProfileManager

/**
 * Écritures « clé = valeur » sur la voiture (clés `ExternalApi.SET_*`), partagées par l'API
 * externe (issue #79) et Mes automatisations (issue #201) : mêmes validations, même clampage aux
 * bornes réelles du véhicule, même verrou de conduite (toutes passent par [MG4Hardware], donc par
 * VehicleWriteGate). Sorties du service le 2026-11-02, sans changement de comportement.
 *
 * ⚠️ Bloquant : les commandes clim SAIC avancent d'un cran à la fois. Jamais sur le fil principal.
 */
object VehicleCommands {

    /**
     * Écrit [key] = [value] ; NEXT / PREV / TOGGLE sont résolus d'après l'état lu. Vrai si la
     * commande est partie (ou la clé connue mais sans objet sur ce firmware), faux si refusée.
     */
    fun set(ctx: Context, key: String, value: String, tag: String): Boolean {
        // NEXT/PREV/TOGGLE sont résolus en valeur concrète AVANT le dispatch : le cycle
        // emprunte ensuite exactement le même chemin d'écriture qu'une consigne explicite,
        // donc les mêmes validations, le même clampage et le même verrou de vitesse.
        val dir = ExternalApi.cycleDirection(value)
        val v   = if (dir == null) value else resolveCycle(key, dir)
        if (v == null) {
            AppLogger.w(tag,
                "CYCLE $key=$value refusé — clé non cyclable ou état courant illisible")
            return false
        }
        if (dir != null) AppLogger.i(tag, "CYCLE $key : $value → $v")

        // Toutes ces écritures passent par MG4Hardware, donc par VehicleWriteGate : refusées
        // en roulant sans que l'API ait à s'en préoccuper.
        val ok = when (key) {
            ExternalApi.SET_DRIVE_MODE -> DriveMode.entries
                .firstOrNull { it.name.equals(v, true) }
                ?.let { MG4Hardware.setDriveMode(it); true } ?: false
            ExternalApi.SET_REGEN -> RegenLevel.entries
                .firstOrNull { it.name.equals(v, true) }
                ?.let { MG4Hardware.setRegenLevel(it); true } ?: false
            ExternalApi.SET_SEAT_HEAT_LEFT ->
                v.toIntOrNull()?.takeIf { it in 0..3 }
                    ?.let { MG4Hardware.setSeatHeatLeft(it); true } ?: false
            ExternalApi.SET_SEAT_HEAT_RIGHT ->
                v.toIntOrNull()?.takeIf { it in 0..3 }
                    ?.let { MG4Hardware.setSeatHeatRight(it); true } ?: false
            ExternalApi.SET_STEERING_HEAT -> {
                val on = v.equals("true", true) || v == "1"
                MG4Hardware.setSteeringHeat(on); true
            }
            ExternalApi.SET_PROFILE -> { applyProfile(ctx, v, tag); true }
            else -> setClimate(key, v, tag)
        }
        if (!ok) AppLogger.w(tag, "SET refusé — clé ou valeur invalide ($key=$v)")
        return ok
    }

    /**
     * Traduit NEXT/PREV en valeur concrète pour [key], en partant de l'état LU sur le véhicule.
     * [dir] vaut +1 ou -1. Retourne null quand la clé n'est pas cyclable ou que son état courant
     * est illisible — et dans ce cas on n'écrit RIEN.
     *
     * ⚠️ Ne jamais « supposer » un point de départ pour sauver l'appel : sur un siège réellement
     * à 3 dont la propriété est muette, partir de 0 ferait descendre la valeur alors que
     * l'utilisateur appuie pour monter. Un refus journalisé est diagnosticable, pas ça.
     *
     * Le cycle boucle (max → min) : c'est indispensable sur un bouton de volant, qui n'a pas de
     * sens « descendre ». Appelée depuis [set], hors du fil principal.
     */
    private fun resolveCycle(key: String, dir: Int): String? {
        if (key !in ExternalApi.CYCLABLE_KEYS) return null

        fun step(cur: Int, min: Int, max: Int): String =
            ExternalApi.cycleStep(cur, min, max, dir).toString()
        fun flip(cur: Boolean?): String? = cur?.let { if (it) "0" else "1" }

        when (key) {
            ExternalApi.SET_SEAT_HEAT_LEFT  ->
                return MG4Hardware.getSeatHeatLeftOrNull()?.let { step(it, 0, 3) }
            ExternalApi.SET_SEAT_HEAT_RIGHT ->
                return MG4Hardware.getSeatHeatRightOrNull()?.let { step(it, 0, 3) }
            ExternalApi.SET_STEERING_HEAT   ->
                return flip(MG4Hardware.getSteeringHeatOrNull())
        }

        // Reste : la clim. Un SEUL getClimateState() — c'est une lecture binder, pas un champ.
        if (!MG4Hardware.hasClimateControl()) return null
        val st = MG4Hardware.getClimateState() ?: return null
        return when (key) {
            ExternalApi.SET_HVAC_POWER    -> flip(st.powerOn)
            ExternalApi.SET_HVAC_AC       -> flip(st.acOn)
            ExternalApi.SET_HVAC_AUTO     -> flip(if (st.reglages().enAuto) true else st.autoOn)
            ExternalApi.SET_DEFROST_FRONT -> flip(st.defrostFront)
            ExternalApi.SET_DEFROST_REAR  -> flip(st.defrostRear)
            // Bornes lues sur le véhicule, jamais codées en dur : elles varient d'un firmware
            // à l'autre, et c'est sur elles que le cycle reboucle.
            ExternalApi.SET_HVAC_TEMP     -> st.tempC?.let { step(it, st.tempMin, st.tempMax) }
            // En AUTO le niveau annoncé (15) n'est pas un cran : on part du dernier réglé à la main.
            ExternalApi.SET_HVAC_FAN      -> (st.reglages().ventilationReelle ?: st.lastManualFan)
                ?.let { step(it, st.fanMin, st.fanMax) }
            ExternalApi.SET_HVAC_RECIRC   -> st.loopMode?.let { step(it, 0, 2) }
            else -> null
        }
    }

    /**
     * Clés `SET` de climatisation. Retourne false si [key] n'en est pas une — l'appelant
     * s'en sert pour distinguer « clé inconnue » de « valeur invalide ».
     *
     * ⚠️ Bloquant : les commandes clim SAIC sont des bascules qui avancent d'un cran à la fois,
     * donc plusieurs secondes possibles. Appelée depuis [set], hors du fil principal.
     *
     * Consigne et ventilation sont clampées aux **bornes réelles du véhicule**, pas à des
     * valeurs codées en dur : elles diffèrent d'un firmware à l'autre.
     */
    private fun setClimate(key: String, value: String, tag: String): Boolean {
        val hvacKeys = setOf(
            ExternalApi.SET_HVAC_POWER, ExternalApi.SET_HVAC_AC, ExternalApi.SET_HVAC_AUTO,
            ExternalApi.SET_HVAC_TEMP, ExternalApi.SET_HVAC_FAN, ExternalApi.SET_HVAC_RECIRC,
            ExternalApi.SET_DEFROST_FRONT, ExternalApi.SET_DEFROST_REAR
        )
        if (key !in hvacKeys) return false
        if (!MG4Hardware.hasClimateControl()) {
            AppLogger.w(tag, "SET $key ignoré — clim non pilotable sur ce firmware")
            return true   // clé connue : ce n'est pas une erreur de syntaxe
        }
        val on = value.equals("true", true) || value == "1"
        return when (key) {
            ExternalApi.SET_HVAC_POWER    -> { MG4Hardware.setClimatePower(on); true }
            ExternalApi.SET_HVAC_AC       -> { MG4Hardware.setClimateAc(on); true }
            ExternalApi.SET_HVAC_AUTO     -> { MG4Hardware.setClimateAuto(on); true }
            ExternalApi.SET_DEFROST_FRONT -> { MG4Hardware.setClimateDefrostFront(on); true }
            ExternalApi.SET_DEFROST_REAR  -> { MG4Hardware.setClimateDefrostRear(on); true }
            ExternalApi.SET_HVAC_RECIRC   -> {
                val mode = when (value.uppercase()) {
                    "INNER", "0"   -> MG4Hardware.LoopMode.INNER
                    "OUTSIDE", "1" -> MG4Hardware.LoopMode.OUTSIDE
                    "AUTO", "2"    -> MG4Hardware.LoopMode.AUTO
                    else           -> return true.also {
                        AppLogger.w(tag, "SET $key : valeur invalide '$value'")
                    }
                }
                MG4Hardware.setClimateLoopMode(mode); true
            }
            ExternalApi.SET_HVAC_TEMP, ExternalApi.SET_HVAC_FAN -> {
                val n = value.toIntOrNull() ?: return true.also {
                    AppLogger.w(tag, "SET $key : valeur non numérique '$value'")
                }
                val state = MG4Hardware.getClimateState() ?: return true.also {
                    AppLogger.w(tag, "SET $key : état clim illisible")
                }
                if (key == ExternalApi.SET_HVAC_TEMP)
                    MG4Hardware.setClimateTemp(n.coerceIn(state.tempMin, state.tempMax))
                else
                    MG4Hardware.setClimateFan(n.coerceIn(state.fanMin, state.fanMax))
                true
            }
            else -> false
        }
    }

    /**
     * Applique un profil désigné par son NOM (insensible à la casse) ou son id. Vrai si le profil
     * existe et que son application est lancée ; [onDone] reçoit son résultat. Hors du fil principal.
     */
    fun applyProfile(ctx: Context, nameOrId: String?, tag: String, onDone: ((Boolean) -> Unit)? = null): Boolean {
        val wanted = nameOrId?.trim().orEmpty()
        if (wanted.isEmpty()) {
            AppLogger.w(tag, "APPLY_PROFILE sans nom de profil")
            return false
        }
        val pm = ProfileManager(ctx)
        val profile = pm.getById(wanted)
            ?: pm.getAll().firstOrNull { it.name.equals(wanted, ignoreCase = true) }
        if (profile == null) {
            AppLogger.w(tag, "profil introuvable : '$wanted'")
            return false
        }
        AppLogger.i(tag, "application du profil '${profile.name}'")
        ProfileApplier.apply(profile, autoStart = true) { ok ->
            AppLogger.i(tag, "profil '${profile.name}' — ok=$ok")
            onDone?.invoke(ok)
        }
        return true
    }
}
