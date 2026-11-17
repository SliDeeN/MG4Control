package com.mg4.control.ui

import android.content.Context
import com.mg4.control.R
import com.mg4.control.model.RuleLastRun
import com.mg4.control.model.RuleSkip

/** Textes d'une exécution et d'un refus, partagés par la carte, le journal et le moteur. */
object RuleRunText {

    /** « 3 actions réussies » puis, s'il y en a, « 1 envoyée » (2026-11-14). */
    fun actions(ctx: Context, run: RuleLastRun): String {
        val ok = ctx.resources.getQuantityString(R.plurals.rule_actions_ok, run.done, run.done)
        return if (run.sent <= 0) ok else ok + ", " + ctx.resources.getQuantityString(R.plurals.rule_actions_sent, run.sent, run.sent)
    }

    /** Raison d'un déclenchement refusé ; [longue] : avec la condition en cause et sa valeur. */
    fun skip(ctx: Context, s: RuleSkip, longue: Boolean): String {
        val cond = s.detail?.let { d -> s.value?.let { ctx.getString(R.string.rule_skip_read_fmt, d, it) } ?: d }
        return when (s.reason) {
            RuleSkip.Reason.CONDITIONS -> if (longue && cond != null) ctx.getString(R.string.rule_skip_conditions_fmt, cond)
                else ctx.getString(R.string.rule_skip_short_conditions)
            RuleSkip.Reason.UNKNOWN -> if (longue && cond != null) ctx.getString(R.string.rule_skip_unknown_fmt, cond)
                else ctx.getString(R.string.rule_skip_short_unknown)
            RuleSkip.Reason.NOT_READY -> ctx.getString(if (longue) R.string.rule_skip_not_ready else R.string.rule_skip_short_not_ready)
            RuleSkip.Reason.BUSY -> ctx.getString(if (longue) R.string.rule_skip_busy else R.string.rule_skip_short_busy)
            RuleSkip.Reason.COOLDOWN -> if (longue) ctx.getString(R.string.rule_skip_cooldown_fmt, s.minutes ?: 0)
                else ctx.getString(R.string.rule_skip_short_cooldown)
            RuleSkip.Reason.GARAGE -> ctx.getString(R.string.settings_garage_mode)
            null -> ctx.getString(R.string.rule_unknown)
        }
    }
}
