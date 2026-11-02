package com.mg4.control.ui

import android.content.Context
import com.mg4.control.R
import com.mg4.control.api.ExternalApi
import com.mg4.control.bluetooth.BluetoothProfileManager
import com.mg4.control.model.DriveMode
import com.mg4.control.model.RegenLevel
import com.mg4.control.model.RuleEvent
import com.mg4.control.model.RuleSignal
import com.mg4.control.model.RuleWords
import com.mg4.control.profile.ProfileManager
import com.mg4.control.shortcut.ShortcutAction
import java.text.DateFormatSymbols
import java.util.Calendar

/**
 * Les mots de Mes automatisations dans la langue de l'appli (issue #201) : chaînes traduites, noms
 * des profils, des téléphones et des raccourcis. Servent à la liste, à l'éditeur et au journal du
 * moteur.
 */
class AndroidRuleWords(private val ctx: Context) : RuleWords {

    private val profils by lazy { ProfileManager(ctx).getAll().associate { it.id to it.name } }
    private val telephones by lazy {
        runCatching { BluetoothProfileManager.getBondedDevices(ctx) }.getOrNull().orEmpty()
            .associate { it.mac.uppercase() to it.name }
    }
    private val raccourcis by lazy { ShortcutCatalog.items(ctx).associate { it.action.name to it.label } }

    private fun t(id: Int, vararg a: Any): String = ctx.getString(id, *a)

    override fun signal(s: RuleSignal): String = t(signalRes(s))

    override fun unit(s: RuleSignal): String = when (s) {
        RuleSignal.SPEED -> " km/h"
        RuleSignal.SOC -> " %"
        RuleSignal.RANGE -> " km"
        RuleSignal.OUTSIDE_TEMP -> " °C"
        else -> ""
    }

    override fun value(s: RuleSignal, text: String): String = when (s) {
        RuleSignal.GEAR -> if (text == RuleSignal.GEAR_OTHER) t(R.string.rule_gear_other) else text
        RuleSignal.PROFILE -> profils[text] ?: text
        RuleSignal.BT_DEVICE -> telephones[text.uppercase()] ?: text
        RuleSignal.DRIVE_MODE -> DriveMode.entries.firstOrNull { it.name.equals(text, true) }?.let { driveMode(it) } ?: text
        else -> text
    }

    override fun yes(label: String) = t(R.string.rule_yes_fmt, label)
    override fun no(label: String) = t(R.string.rule_no_fmt, label)
    override fun event(e: RuleEvent): String = t(eventRes(e))
    override fun forSeconds(text: String, seconds: Int) = t(R.string.rule_for_fmt, text, seconds)
    override fun at(time: String) = t(R.string.rule_at_fmt, time)
    override val and: String get() = t(R.string.rule_and)
    override val or: String get() = t(R.string.rule_or)
    override fun none(text: String) = t(R.string.rule_none_fmt, text)
    override fun range(from: String, to: String) = t(R.string.rule_range_fmt, from, to)

    override fun days(days: List<Int>): String {
        if (days.size == 7) return t(R.string.rule_every_day)
        return days.joinToString(", ") { dayName(it) }
    }

    /** 1 = lundi … 7 = dimanche, en abrégé dans la langue de l'appli. */
    fun dayName(j: Int): String {
        val noms = DateFormatSymbols.getInstance(ctx.resources.configuration.locales[0]).shortWeekdays
        // Calendar : lundi = 2 … dimanche = 1.
        return noms[if (j == 7) Calendar.SUNDAY else j + 1]
    }

    override val always: String get() = t(R.string.rule_always)

    override fun set(key: String, value: String): String {
        val nom = keyRes(key)?.let { t(it) } ?: key
        val valeur = when (key) {
            ExternalApi.SET_STEERING_HEAT, ExternalApi.SET_HVAC_POWER, ExternalApi.SET_HVAC_AC,
            ExternalApi.SET_HVAC_AUTO, ExternalApi.SET_DEFROST_FRONT, ExternalApi.SET_DEFROST_REAR -> when (value) {
                "1", "true" -> t(R.string.rule_on)
                "0", "false" -> t(R.string.rule_off)
                else -> value
            }
            ExternalApi.SET_SEAT_HEAT_LEFT, ExternalApi.SET_SEAT_HEAT_RIGHT -> if (value == "0") t(R.string.rule_seat_off) else value
            ExternalApi.SET_HVAC_TEMP -> "$value °C"
            ExternalApi.SET_PROFILE -> profils[value] ?: value
            ExternalApi.SET_REGEN -> RegenLevel.entries.firstOrNull { it.name.equals(value, true) }?.let { regen(it) } ?: value
            ExternalApi.SET_DRIVE_MODE -> DriveMode.entries.firstOrNull { it.name.equals(value, true) }?.let { driveMode(it) } ?: value
            ExternalApi.SET_HVAC_RECIRC -> recircRes(value)?.let { t(it) } ?: value
            else -> value
        }
        return t(R.string.rule_set_fmt, nom, valeur)
    }

    fun regen(n: RegenLevel): String = t(when (n) {
        RegenLevel.OFF -> R.string.regen_off
        RegenLevel.LOW -> R.string.regen_low
        RegenLevel.MEDIUM -> R.string.regen_medium
        RegenLevel.HIGH -> R.string.regen_high
        RegenLevel.ADAPTIVE -> R.string.regen_adaptive
        RegenLevel.ONE_PEDAL -> R.string.regen_one_pedal
    })

    fun driveMode(m: DriveMode): String = t(when (m) {
        DriveMode.ECO -> R.string.drive_eco
        DriveMode.NORMAL -> R.string.drive_normal
        DriveMode.SPORT -> R.string.drive_sport
        DriveMode.SNOW -> R.string.drive_snow
        DriveMode.CUSTOM -> R.string.drive_custom
    })

    override fun shortcut(name: String): String = raccourcis[name]
        ?: ShortcutAction.entries.firstOrNull { it.name == name }?.name ?: name

    override fun profile(id: String) = t(R.string.rule_act_profile_fmt, profils[id] ?: id)
    override fun waitFor(seconds: Int) = t(R.string.rule_act_wait_fmt, seconds)
    override fun ifThen(condition: String) = t(R.string.rule_act_if_fmt, condition)
    override fun repeat(times: Int) = t(R.string.rule_repeat_fmt, times)
    override fun notify(text: String) = t(R.string.rule_act_notify_fmt, text)
    override val stop: String get() = t(R.string.rule_act_stop)
    override val unknown: String get() = t(R.string.rule_unknown)

    companion object {
        fun signalRes(s: RuleSignal): Int = when (s) {
            RuleSignal.READY -> R.string.rule_sig_ready
            RuleSignal.GEAR -> R.string.rule_sig_gear
            RuleSignal.SPEED -> R.string.rule_sig_speed
            RuleSignal.SOC -> R.string.rule_sig_soc
            RuleSignal.RANGE -> R.string.rule_sig_range
            RuleSignal.CHARGING -> R.string.rule_sig_charging
            RuleSignal.OUTSIDE_TEMP -> R.string.rule_sig_outside_temp
            RuleSignal.HVAC_ON -> R.string.rule_sig_hvac
            RuleSignal.BT_DEVICE -> R.string.rule_sig_bt
            RuleSignal.PROFILE -> R.string.rule_sig_profile
            RuleSignal.DRIVE_MODE -> R.string.rule_sig_drive_mode
        }

        fun eventRes(e: RuleEvent): Int = when (e) {
            RuleEvent.APP_START -> R.string.rule_event_app_start
            RuleEvent.FRONT_DOOR_OPEN -> R.string.rule_event_door
        }

        fun keyRes(key: String): Int? = when (key) {
            ExternalApi.SET_DRIVE_MODE -> R.string.rule_sig_drive_mode
            ExternalApi.SET_REGEN -> R.string.rule_key_regen
            ExternalApi.SET_SEAT_HEAT_LEFT -> R.string.rule_key_seat_left
            ExternalApi.SET_SEAT_HEAT_RIGHT -> R.string.rule_key_seat_right
            ExternalApi.SET_STEERING_HEAT -> R.string.rule_key_steering
            ExternalApi.SET_PROFILE -> R.string.rule_sig_profile
            ExternalApi.SET_HVAC_POWER -> R.string.rule_key_hvac
            ExternalApi.SET_HVAC_AC -> R.string.rule_key_ac
            ExternalApi.SET_HVAC_AUTO -> R.string.rule_key_hvac_auto
            ExternalApi.SET_HVAC_TEMP -> R.string.rule_key_hvac_temp
            ExternalApi.SET_HVAC_FAN -> R.string.rule_key_hvac_fan
            ExternalApi.SET_HVAC_RECIRC -> R.string.rule_key_recirc
            ExternalApi.SET_DEFROST_FRONT -> R.string.rule_key_defrost_front
            ExternalApi.SET_DEFROST_REAR -> R.string.rule_key_defrost_rear
            else -> null
        }

        fun recircRes(v: String): Int? = when (v.uppercase()) {
            "INNER", "0" -> R.string.rule_recirc_inner
            "OUTSIDE", "1" -> R.string.rule_recirc_outside
            "AUTO", "2" -> R.string.rule_recirc_auto
            else -> null
        }
    }
}
