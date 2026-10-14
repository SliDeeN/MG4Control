package com.mg4.control.ui

import android.app.AlertDialog
import android.content.Context
import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import com.mg4.control.R
import com.mg4.control.model.ChargeSession
import com.mg4.control.model.ChargeTimes
import com.mg4.control.model.ChargeType
import com.mg4.control.model.MoneyFormat
import com.mg4.control.model.PriceStepper
import com.mg4.control.model.StatsTracker
import java.util.Locale

/**
 * Les deux saisies de l'onglet Statistiques, SANS clavier — proposition « B » choisie sur maquette
 * le 2026-10-13. Le clavier de la voiture couvre la moitié de l'écran ; des boutons − et + font le
 * même travail sans rien cacher, et se maintiennent pour aller vite ([AppDialog.repeter]).
 *
 * Ce que deviennent les valeurs — placement des heures sur le calendrier, enregistrement — reste
 * dans [StatsFragment] : ici on ne fait que les demander.
 */
internal object StatsDialogs {

    private const val LARGEUR_SESSION_DP = 820f
    private const val LARGEUR_TARIF_DP = 680f

    /** Le pas choisi à l'ouverture de « Corriger le tarif » : le deuxième, le centième en euros. */
    private const val PAS_PAR_DEFAUT = 1

    /** Heure proposée quand le relevé n'en donne pas de fiable. */
    private val MIDI = 12 to 0

    /**
     * « Compléter la session » : type de prise et horaires réels d'une charge que l'application
     * n'a pas vue.
     *
     * @param onEnregistrer le type choisi et les deux heures, « hh:mm » ou vides si elles n'ont pas
     *                      été renseignées — ce que relit [ChargeTimes.resolve]
     */
    fun completer(
        context: Context,
        session: ChargeSession,
        onEnregistrer: (type: ChargeType?, debut: String, fin: String) -> Unit,
    ): AlertDialog {
        val vue = LayoutInflater.from(context).inflate(R.layout.dialog_stats_complete, null)
        vue.findViewById<ImageView>(R.id.complete_icon).imageTintList =
            ColorStateList.valueOf(context.getColor(R.color.dash_accent))

        val types = listOf(
            ChargeType.AC to R.string.stats_charge_ac,
            ChargeType.DC to R.string.stats_charge_dc,
            null to R.string.stats_charge_unknown,
        )
        var type = session.type
        AppDialog.segments(
            vue.findViewById(R.id.complete_types),
            types.map { context.getString(it.second) },
            choisi = types.indexOfFirst { it.first == type },
        ) { rang -> type = types[rang].first }

        // Une heure déjà saisie est reprise ; sinon elle reste « non renseignée », et le relevé de
        // l'application sert de point de départ au premier appui.
        val debut = Horaire(
            vue.findViewById(R.id.complete_start), libelle(context, R.string.stats_complete_start),
            session.userStartMs?.let(::heureDe), proposee(session.startMs))
        val fin = Horaire(
            vue.findViewById(R.id.complete_end), libelle(context, R.string.stats_complete_end),
            session.userEndMs?.let(::heureDe), proposee(session.endMs))

        val dialog = AppDialog.afficher(context, vue, LARGEUR_SESSION_DP)
        vue.findViewById<View>(R.id.complete_close).setOnClickListener { dialog.dismiss() }
        vue.findViewById<View>(R.id.complete_save).setOnClickListener {
            dialog.dismiss()
            onEnregistrer(type, debut.texte(), fin.texte())
        }
        return dialog
    }

    /**
     * « Corriger le tarif » d'une session.
     *
     * @param prix      le tarif appliqué aujourd'hui à la session
     * @param reference un prix du même ordre de grandeur, qui décide des pas proposés
     * @param unite     la monnaie par kWh, telle qu'on l'affiche
     * @param onDefaut  revenir au tarif du type de prise
     */
    fun tarif(
        context: Context,
        prix: Float,
        reference: Float,
        unite: String,
        onEnregistrer: (Float) -> Unit,
        onDefaut: () -> Unit,
    ): AlertDialog {
        val vue = LayoutInflater.from(context).inflate(R.layout.dialog_stats_tariff, null)
        vue.findViewById<ImageView>(R.id.tariff_icon).imageTintList =
            ColorStateList.valueOf(context.getColor(R.color.dash_accent))
        vue.findViewById<TextView>(R.id.tariff_unit).text = unite

        val langue = Locale.getDefault()
        val pas = PriceStepper.steps(reference)
        var rangPas = PAS_PAR_DEFAUT
        AppDialog.segments(
            vue.findViewById(R.id.tariff_steps),
            pas.map { "± " + MoneyFormat.price(it, langue) },
            choisi = rangPas,
        ) { rang -> rangPas = rang }

        var valeur = prix
        val affichage = vue.findViewById<TextView>(R.id.tariff_value)
        fun montrer() { affichage.text = MoneyFormat.price(valeur, langue) }
        fun bouger(sens: Int) {
            valeur = PriceStepper.move(valeur, pas[rangPas], sens)
            montrer()
        }
        teinter(context, vue, R.id.tariff_minus, R.id.tariff_plus)
        AppDialog.repeter(vue.findViewById(R.id.tariff_minus)) { bouger(-1) }
        AppDialog.repeter(vue.findViewById(R.id.tariff_plus)) { bouger(1) }
        montrer()

        val dialog = AppDialog.afficher(context, vue, LARGEUR_TARIF_DP)
        vue.findViewById<View>(R.id.tariff_close).setOnClickListener { dialog.dismiss() }
        vue.findViewById<View>(R.id.tariff_default).setOnClickListener {
            dialog.dismiss()
            onDefaut()
        }
        vue.findViewById<View>(R.id.tariff_save).setOnClickListener {
            dialog.dismiss()
            onEnregistrer(valeur)
        }
        return dialog
    }

    /**
     * Une heure à régler (part_time_stepper.xml). Elle est inconnue — [valeur] nulle — tant qu'on
     * n'y a pas touché : compléter le type de prise ne doit pas inventer des horaires.
     */
    private class Horaire(
        panneau: View,
        libelle: CharSequence,
        saisie: Pair<Int, Int>?,
        private val proposee: Pair<Int, Int>,
    ) {
        private val context = panneau.context
        private val heures = panneau.findViewById<TextView>(R.id.time_hours)
        private val minutes = panneau.findViewById<TextView>(R.id.time_minutes)
        private val effacer = panneau.findViewById<ImageView>(R.id.time_clear)
        private var valeur: Pair<Int, Int>? = saisie

        init {
            panneau.findViewById<TextView>(R.id.time_caption).text = libelle
            teinter(context, panneau, R.id.time_hours_minus, R.id.time_hours_plus,
                R.id.time_minutes_minus, R.id.time_minutes_plus)
            effacer.imageTintList = ColorStateList.valueOf(context.getColor(R.color.text_secondary))
            AppDialog.repeter(panneau.findViewById(R.id.time_hours_minus)) { pas(-1, 0) }
            AppDialog.repeter(panneau.findViewById(R.id.time_hours_plus)) { pas(1, 0) }
            AppDialog.repeter(panneau.findViewById(R.id.time_minutes_minus)) { pas(0, -1) }
            AppDialog.repeter(panneau.findViewById(R.id.time_minutes_plus)) { pas(0, 1) }
            effacer.setOnClickListener {
                valeur = null
                montrer()
            }
            montrer()
        }

        /** Au premier appui l'heure proposée apparaît, sans bouger : on la voit avant de la régler. */
        private fun pas(h: Int, m: Int) {
            valeur = valeur?.let { ChargeTimes.step(it, h, m) } ?: proposee
            montrer()
        }

        private fun montrer() {
            val v = valeur
            val couleur = context.getColor(if (v == null) R.color.text_secondary else R.color.text_primary)
            heures.text = v?.let { "%02d".format(it.first) } ?: VIDE
            minutes.text = v?.let { "%02d".format(it.second) } ?: VIDE
            heures.setTextColor(couleur)
            minutes.setTextColor(couleur)
            // Rien à effacer tant que rien n'est renseigné ; la croix garde sa place.
            effacer.visibility = if (v == null) View.INVISIBLE else View.VISIBLE
        }

        /** « hh:mm », ou vide si l'heure n'est pas renseignée. */
        fun texte(): String = valeur?.let { ChargeTimes.text(it) } ?: ""

        private companion object {
            const val VIDE = "– –"
        }
    }

    /** Les pictogrammes − et + prennent la couleur du texte : la mise en page les laisse blancs. */
    private fun teinter(context: Context, racine: View, vararg ids: Int) {
        val teinte = ColorStateList.valueOf(context.getColor(R.color.text_primary))
        ids.forEach { racine.findViewById<ImageView>(it).imageTintList = teinte }
    }

    /** Les libellés disent « Début (hh:mm) », pour qui tapait l'heure : ici, le mot suffit. */
    private fun libelle(context: Context, texte: Int): String =
        context.getString(texte).substringBefore("(").trim()

    private fun heureDe(ms: Long): Pair<Int, Int>? = ChargeTimes.parse(ChargeTimes.hhmm(ms))

    /** L'heure du relevé si son horloge est crédible, midi sinon ([StatsTracker.clockPlausible]). */
    private fun proposee(ms: Long): Pair<Int, Int> =
        ms.takeIf { StatsTracker.clockPlausible(it) }?.let(::heureDe) ?: MIDI
}
