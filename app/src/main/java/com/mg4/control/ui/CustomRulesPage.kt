package com.mg4.control.ui

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.mg4.control.R
import com.mg4.control.api.ExternalApi
import com.mg4.control.automation.CustomRuleStore
import com.mg4.control.automation.CustomRules
import com.mg4.control.bluetooth.BluetoothProfileManager
import com.mg4.control.model.CustomRule
import com.mg4.control.model.CustomRuleTemplates
import com.mg4.control.model.RegenLevel
import com.mg4.control.model.RuleEvent
import com.mg4.control.model.RuleLastRun
import com.mg4.control.model.RuleSignal
import com.mg4.control.model.RuleText
import com.mg4.control.model.RuleWords
import com.mg4.control.profile.ProfileManager
import java.text.DateFormat
import java.text.DateFormatSymbols
import java.util.Calendar
import java.util.Date

/**
 * Page « Mes automatisations » de l'onglet Automatisation (issue #201, étape 1 du 2026-11-02) :
 * la liste des automatisations — interrupteur, résumé Quand / Si / Alors, dernière exécution,
 * Tester, Supprimer — et la création depuis un modèle. L'éditeur viendra à l'étape 2.
 */
class CustomRulesPage {

    private var vue: View? = null
    private var fragment: Fragment? = null

    fun bind(fragment: Fragment, root: View) {
        this.fragment = fragment
        vue = root
        root.findViewById<View>(R.id.btn_custom_rule_new).setOnClickListener { choisirModele(fragment.requireContext()) }
        // Une exécution vient de finir : sa ligne « Dernière exécution » change.
        CustomRules.surChangement = { dessiner() }
        dessiner()
    }

    fun detacher() {
        CustomRules.surChangement = null
        vue = null
        fragment = null
    }

    /** Retour sur l'onglet : profils et téléphones ont pu être renommés entre-temps. */
    fun refresh() = dessiner()

    private fun dessiner() {
        val root = vue ?: return
        val ctx = root.context
        val regles = CustomRuleStore.all(ctx)
        val runs = CustomRuleStore.lastRuns(ctx)
        root.findViewById<TextView>(R.id.custom_rules_count).text =
            ctx.getString(R.string.rule_count_fmt, regles.size, CustomRuleStore.MAX_RULES)
        root.findViewById<View>(R.id.custom_rules_empty).visibility = if (regles.isEmpty()) View.VISIBLE else View.GONE
        val liste = root.findViewById<LinearLayout>(R.id.custom_rules_list)
        liste.removeAllViews()
        val texte = RuleText(Mots(ctx))
        val gonfleur = LayoutInflater.from(ctx)
        for (r in regles) {
            val carte = gonfleur.inflate(R.layout.item_custom_rule, liste, false)
            carte.findViewById<TextView>(R.id.rule_name).text = r.name
            carte.findViewById<TextView>(R.id.rule_summary).text = resume(ctx, texte, r)
            carte.findViewById<TextView>(R.id.rule_last_run).text = derniere(ctx, runs[r.id])
            carte.findViewById<Switch>(R.id.rule_switch).apply {
                isChecked = r.enabled
                setOnCheckedChangeListener { _, on ->
                    CustomRuleStore.setEnabled(ctx, r.id, on)
                    CustomRules.refresh(ctx)
                }
            }
            carte.findViewById<View>(R.id.rule_test).setOnClickListener {
                CustomRules.test(ctx, r.id)
                Toast.makeText(ctx, ctx.getString(R.string.rule_test_started, r.name), Toast.LENGTH_SHORT).show()
            }
            carte.findViewById<View>(R.id.rule_delete).setOnClickListener { supprimer(ctx, r) }
            liste.addView(carte)
        }
    }

    private fun resume(ctx: Context, t: RuleText, r: CustomRule): String =
        ctx.getString(R.string.rule_summary_fmt, t.triggers(r), t.conditions(r), t.actions(r))

    private fun derniere(ctx: Context, run: RuleLastRun?): String {
        if (run == null) return ctx.getString(R.string.rule_never_run)
        val quand = run.atMs?.let { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(it)) }
            ?: ctx.getString(R.string.rule_run_time_unknown)
        return ctx.getString(R.string.rule_last_run_fmt, quand, run.done, run.failed)
    }

    private fun supprimer(ctx: Context, r: CustomRule) {
        AppDialog.confirmer(ctx,
            titre = ctx.getString(R.string.rule_delete_confirm_fmt, r.name),
            ton = AppDialog.Ton.DANGER,
            oui = ctx.getString(R.string.rule_delete),
            non = ctx.getString(R.string.profile_cancel),
            onOui = {
                CustomRuleStore.delete(ctx, r.id)
                CustomRules.refresh(ctx)
                dessiner()
            })
    }

    // ── Modèles ──────────────────────────────────────────────────────────────

    private class Modele(val t: CustomRuleTemplates.Template, val nom: Int, val message: Int)

    private val modeles = listOf(
        Modele(CustomRuleTemplates.Template.STARTUP_MESSAGE, R.string.rule_tpl_startup, R.string.rule_tpl_startup_msg),
        Modele(CustomRuleTemplates.Template.WINTER_MORNING, R.string.rule_tpl_morning, R.string.rule_tpl_morning_msg),
        Modele(CustomRuleTemplates.Template.HOT_DAY, R.string.rule_tpl_hot, R.string.rule_tpl_hot_msg),
        Modele(CustomRuleTemplates.Template.WINDOWS_ON_LEAVING, R.string.rule_tpl_windows, R.string.rule_tpl_windows),
    )

    private fun choisirModele(ctx: Context) {
        if (CustomRuleStore.all(ctx).size >= CustomRuleStore.MAX_RULES) {
            Toast.makeText(ctx, ctx.getString(R.string.rule_max_reached, CustomRuleStore.MAX_RULES), Toast.LENGTH_LONG).show()
            return
        }
        AppDialog.choisir(ctx,
            titre = ctx.getString(R.string.rule_templates_title),
            sousTitre = ctx.getString(R.string.rule_templates_subtitle),
            choix = modeles.map { AppDialog.Choix(ctx.getString(it.nom)) },
            annuler = ctx.getString(R.string.profile_cancel),
        ) { rang ->
            val m = modeles[rang]
            val regle = CustomRuleTemplates.build(m.t, ctx.getString(m.nom), ctx.getString(m.message))
            if (!CustomRuleStore.add(ctx, regle)) {
                Toast.makeText(ctx, ctx.getString(R.string.rule_max_reached, CustomRuleStore.MAX_RULES), Toast.LENGTH_LONG).show()
                return@choisir
            }
            CustomRules.refresh(ctx)
            dessiner()
        }
    }

    // ── Les mots ─────────────────────────────────────────────────────────────

    private class Mots(private val ctx: Context) : RuleWords {

        private val profils by lazy { ProfileManager(ctx).getAll().associate { it.id to it.name } }
        private val telephones by lazy {
            runCatching { BluetoothProfileManager.getBondedDevices(ctx) }.getOrNull().orEmpty()
                .associate { it.mac.uppercase() to it.name }
        }

        private fun t(id: Int, vararg a: Any): String = ctx.getString(id, *a)

        override fun signal(s: RuleSignal): String = t(when (s) {
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
        })

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
            else -> text
        }

        override fun yes(label: String) = t(R.string.rule_yes_fmt, label)
        override fun no(label: String) = t(R.string.rule_no_fmt, label)

        override fun event(e: RuleEvent): String = t(when (e) {
            RuleEvent.APP_START -> R.string.rule_event_app_start
            RuleEvent.FRONT_DOOR_OPEN -> R.string.rule_event_door
        })

        override fun forSeconds(text: String, seconds: Int) = t(R.string.rule_for_fmt, text, seconds)
        override fun at(time: String) = t(R.string.rule_at_fmt, time)
        override val and: String get() = t(R.string.rule_and)
        override val or: String get() = t(R.string.rule_or)
        override fun none(text: String) = t(R.string.rule_none_fmt, text)
        override fun range(from: String, to: String) = t(R.string.rule_range_fmt, from, to)

        override fun days(days: List<Int>): String {
            if (days.size == 7) return t(R.string.rule_every_day)
            val noms = DateFormatSymbols.getInstance(ctx.resources.configuration.locales[0]).shortWeekdays
            // 1 = lundi … 7 = dimanche → Calendar : lundi = 2 … dimanche = 1.
            return days.joinToString(", ") { j -> noms[if (j == 7) Calendar.SUNDAY else j + 1] }
        }

        override val always: String get() = t(R.string.rule_always)

        override fun set(key: String, value: String): String {
            val nom = when (key) {
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
            }?.let { t(it) } ?: key
            val valeur = when (key) {
                ExternalApi.SET_STEERING_HEAT, ExternalApi.SET_HVAC_POWER, ExternalApi.SET_HVAC_AC,
                ExternalApi.SET_HVAC_AUTO, ExternalApi.SET_DEFROST_FRONT, ExternalApi.SET_DEFROST_REAR -> when (value) {
                    "1", "true" -> t(R.string.rule_on)
                    "0", "false" -> t(R.string.rule_off)
                    else -> value
                }
                ExternalApi.SET_HVAC_TEMP -> "$value °C"
                ExternalApi.SET_PROFILE -> profils[value] ?: value
                ExternalApi.SET_REGEN -> RegenLevel.entries.firstOrNull { it.name.equals(value, true) }?.let { regen(it) } ?: value
                else -> value
            }
            return t(R.string.rule_set_fmt, nom, valeur)
        }

        private fun regen(n: RegenLevel): String = t(when (n) {
            RegenLevel.OFF -> R.string.regen_off
            RegenLevel.LOW -> R.string.regen_low
            RegenLevel.MEDIUM -> R.string.regen_medium
            RegenLevel.HIGH -> R.string.regen_high
            RegenLevel.ADAPTIVE -> R.string.regen_adaptive
            RegenLevel.ONE_PEDAL -> R.string.regen_one_pedal
        })

        override fun shortcut(name: String): String = when (name) {
            "WINDOWS_CLOSE_ALL" -> t(R.string.shortcuts_action_windows_close)
            "WINDOWS_OPEN_ALL" -> t(R.string.shortcuts_action_windows_open)
            else -> name
        }

        override fun profile(id: String) = t(R.string.rule_act_profile_fmt, profils[id] ?: id)
        override fun waitFor(seconds: Int) = t(R.string.rule_act_wait_fmt, seconds)
        override fun ifThen(condition: String) = t(R.string.rule_act_if_fmt, condition)
        override fun notify(text: String) = t(R.string.rule_act_notify_fmt, text)
        override val stop: String get() = t(R.string.rule_act_stop)
        override val unknown: String get() = t(R.string.rule_unknown)
    }
}
