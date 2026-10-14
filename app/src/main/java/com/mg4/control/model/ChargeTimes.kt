package com.mg4.control.model

import java.util.Calendar
import java.util.TimeZone

/**
 * Heures d'une charge saisies à la main, et leur placement sur le calendrier.
 *
 * Seules les heures sont demandées, jamais les dates : une charge de nuit enjambe deux jours, et
 * saisir une date sur un écran de voiture serait une corvée pour rien. Tout l'enjeu est donc de
 * retrouver la bonne date — et c'est ici qu'une session s'est retrouvée datée du 31/12/2018 le
 * 2026-09-21. Pur et testable à dessein.
 */
object ChargeTimes {

    private val HEURE = Regex("^\\D*(\\d{1,2})\\D+(\\d{2})\\D*$")

    /** « 22:30 », « 22h30 », « 22 30 » → (22, 30). Null si illisible ou hors bornes. */
    fun parse(saisie: String): Pair<Int, Int>? {
        val m = HEURE.find(saisie.trim()) ?: return null
        val h = m.groupValues[1].toInt()
        val min = m.groupValues[2].toInt()
        return if (h in 0..23 && min in 0..59) h to min else null
    }

    /**
     * Heure sur vingt-quatre heures, quelle que soit la langue : le champ est relu tel quel, et un
     * « 10:30 PM » relu comme 10 h 30 fausserait la durée de douze heures.
     */
    fun hhmm(ms: Long, tz: TimeZone = TimeZone.getDefault()): String {
        val cal = Calendar.getInstance(tz).apply { timeInMillis = ms }
        return "%02d:%02d".format(cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE))
    }

    /**
     * Place les deux heures saisies ; rend (début, fin), chacun null s'il est illisible.
     *
     * La **fin** est calée sur un relevé bien daté — celui du réveil de préférence —, et le
     * **début** sur la fin : une charge dure moins d'une journée, donc le début est la dernière
     * occurrence de son heure avant la fin.
     *
     * L'ancienne règle calait le début sur son propre relevé, ce qui supposait ce relevé bien daté.
     * Un boîtier réveillé par la charge avec son horloge d'usine l'a démenti : le début est tombé
     * le 31/12/2018, la fin le 21/09/2026, et la puissance à 0,0 kW. Caler le début sur la fin
     * rend le placement indépendant du relevé le plus fragile des deux.
     */
    fun resolve(
        debut: String,
        fin: String,
        startMs: Long,
        endMs: Long,
        tz: TimeZone = TimeZone.getDefault(),
    ): Pair<Long?, Long?> {
        val plausible = { ms: Long -> StatsTracker.clockPlausible(ms) }
        val ancreFin = endMs.takeIf(plausible) ?: startMs.takeIf(plausible)
        val ancreDebut = startMs.takeIf(plausible) ?: endMs.takeIf(plausible)

        val f = ancreFin?.let { ancre -> parse(fin)?.let { proche(it, ancre, tz) } }
        val d = parse(debut)?.let { hm ->
            if (f != null) avant(hm, f, tz) else ancreDebut?.let { proche(hm, it, tz) }
        }
        return d to f
    }

    /**
     * Pas des minutes aux boutons − / +. Cinq : à la minute près il faudrait trente appuis pour
     * une demi-heure, et cinq minutes d'écart sur une charge de plusieurs heures ne changent pas
     * la puissance moyenne.
     */
    const val MINUTE_STEP = 5

    /**
     * L'heure après un appui sur un bouton − / + : [hours] et [minutes] valent −1, 0 ou +1.
     *
     * Chaque compteur tourne sur lui-même, sans retenue : 23 h + 1 donne 0 h, 55 min + 5 donne
     * 0 min à la même heure — c'est ce qu'on attend d'un réglage à deux compteurs. Une minute qui
     * n'est pas un multiple de [MINUTE_STEP] (saisie au clavier, du temps où il y en avait un)
     * rejoint le multiple voisin dans le sens demandé.
     */
    fun step(hm: Pair<Int, Int>, hours: Int, minutes: Int): Pair<Int, Int> {
        val h = Math.floorMod(hm.first + hours, 24)
        val cran = Math.floorDiv(hm.second, MINUTE_STEP)
        val min = when {
            minutes > 0 -> (cran + 1) * MINUTE_STEP
            minutes < 0 -> (if (hm.second % MINUTE_STEP == 0) cran - 1 else cran) * MINUTE_STEP
            else -> hm.second
        }
        return h to Math.floorMod(min, 60)
    }

    /** « 06:05 » : l'heure telle que [parse] la relit. */
    fun text(hm: Pair<Int, Int>): String = "%02d:%02d".format(hm.first, hm.second)

    /** Occurrence de l'heure la plus proche de [ancre], à moins de douze heures de part ou d'autre. */
    private fun proche(hm: Pair<Int, Int>, ancre: Long, tz: TimeZone): Long {
        val cal = caler(hm, ancre, tz)
        val douzeHeures = 12 * 3_600_000L
        if (cal.timeInMillis - ancre > douzeHeures) cal.add(Calendar.DAY_OF_MONTH, -1)
        else if (ancre - cal.timeInMillis > douzeHeures) cal.add(Calendar.DAY_OF_MONTH, 1)
        return cal.timeInMillis
    }

    /** Dernière occurrence de l'heure strictement avant [limite]. */
    private fun avant(hm: Pair<Int, Int>, limite: Long, tz: TimeZone): Long {
        val cal = caler(hm, limite, tz)
        if (cal.timeInMillis >= limite) cal.add(Calendar.DAY_OF_MONTH, -1)
        return cal.timeInMillis
    }

    private fun caler(hm: Pair<Int, Int>, jour: Long, tz: TimeZone): Calendar =
        Calendar.getInstance(tz).apply {
            timeInMillis = jour
            set(Calendar.HOUR_OF_DAY, hm.first)
            set(Calendar.MINUTE, hm.second)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
}
