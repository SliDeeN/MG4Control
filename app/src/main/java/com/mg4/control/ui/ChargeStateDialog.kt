package com.mg4.control.ui

import android.content.Context
import android.content.res.ColorStateList
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import com.mg4.control.R
import com.mg4.control.debug.AppLogger
import com.mg4.control.hardware.EnergyReader
import com.mg4.control.model.ChargeDisplay
import com.mg4.control.model.ChargeStateNames
import com.mg4.control.model.ChargeStateSignals
import com.mg4.control.service.OverlayKit
import com.mg4.control.util.FirmwareInfo
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Fenêtre « État de charge » (Réglages, débloquée avec le Diagnostic — 2026-10-23, à la place de
 * « Onglet du combiné »).
 *
 * Montre en direct l'état `BMS_CHRG_STS`, son nom d'après les lanceurs d'origine
 * ([ChargeStateNames]) et ce que MG4Control en conclut ([ChargeDisplay.enCharge]) : de quoi
 * vérifier en voiture les états 2 (« charge terminée », comptée en charge par SAIC) et 11, et
 * relever ceux qu'on n'a jamais vus. À côté, les valeurs brutes ; dessous, chaque changement depuis
 * l'ouverture, avec l'heure et le pourcentage. Chaque changement est aussi noté dans le journal,
 * et donc dans le rapport du Diagnostic.
 *
 * Lecture seule.
 */
object ChargeStateDialog {

    private const val TAG = "MG4_CHARGE"

    /** Un état de charge ne change pas vite : deux relevés par seconde suffisent largement. */
    private const val CADENCE_MS = 500L

    /** Lignes d'historique gardées : ce que la fenêtre montre sans dépasser la hauteur de l'écran. */
    private const val HISTORIQUE_MAX = 6

    fun afficher(ctx: Context) {
        val vue = LayoutInflater.from(ctx).inflate(R.layout.dialog_charge_state, null)
        OverlayKit.fond(vue.findViewById(R.id.charge_state_badge), ctx.getColor(R.color.dash_accent_dim), ovale = true)
        vue.findViewById<ImageView>(R.id.charge_state_icon).imageTintList =
            ColorStateList.valueOf(ctx.getColor(R.color.dash_accent))
        vue.findViewById<ImageView>(R.id.charge_state_close).imageTintList =
            ColorStateList.valueOf(ctx.getColor(R.color.text_primary))
        val champs = Champs(
            numero = vue.findViewById(R.id.charge_state_number),
            nom = vue.findViewById(R.id.charge_state_name),
            nous = vue.findViewById(R.id.charge_state_ours),
            detail = vue.findViewById(R.id.charge_state_detail),
            historique = vue.findViewById(R.id.charge_state_history),
        )

        val dialog = AppDialog.afficher(ctx, vue, 1100f)
        vue.findViewById<View>(R.id.charge_state_close).setOnClickListener { dialog.dismiss() }

        val gen = FirmwareInfo.getGeneration()
        AppLogger.i(TAG, "État de charge : fenêtre ouverte — firmware ${gen.name}")

        // Les lectures passent par le binder : sur un fil à elles, arrêté avec la fenêtre. Les
        // lignes d'historique ne sont touchées que sur ce fil ; l'affichage en reçoit une copie.
        val fil = HandlerThread("mg4-charge-state").also { it.start() }
        val lecteur = Handler(fil.looper)
        val main = Handler(Looper.getMainLooper())
        val heure = SimpleDateFormat("HH:mm:ss", Locale.US)
        val lignes = ArrayDeque<String>()
        var precedents: ChargeStateSignals? = null

        val releve = object : Runnable {
            override fun run() {
                val r = runCatching { EnergyReader.chargeDebug() }.getOrNull()
                if (r != null) {
                    val signaux = signaux(r)
                    val avant = precedents
                    if (avant == null) {
                        AppLogger.i(TAG, "État de charge : ouverture — ${decrire(r)}")
                    } else {
                        val changes = ChargeStateNames.changements(avant, signaux)
                        if (changes.isNotEmpty()) {
                            val soc = r.soc?.let { "  (${un(it)} %)" } ?: ""
                            lignes.addFirst("${heure.format(Date())}  $changes$soc")
                            while (lignes.size > HISTORIQUE_MAX) lignes.removeLast()
                            AppLogger.i(TAG, "État de charge : $changes — ${decrire(r)}")
                        }
                    }
                    precedents = signaux
                }
                val copie = lignes.toList()
                main.post { if (dialog.isShowing) montrer(ctx, r, gen, copie, champs) }
                lecteur.postDelayed(this, CADENCE_MS)
            }
        }
        lecteur.post(releve)
        dialog.setOnDismissListener {
            lecteur.removeCallbacksAndMessages(null)
            fil.quitSafely()
            AppLogger.i(TAG, "État de charge : fenêtre fermée")
        }
    }

    private class Champs(
        val numero: TextView,
        val nom: TextView,
        val nous: TextView,
        val detail: TextView,
        val historique: TextView,
    )

    private fun signaux(r: EnergyReader.ChargeDebug) = ChargeStateSignals(
        statut = r.brut.statut, prise = r.brut.prise, ac = r.brut.ac, dc = r.brut.dc,
        raison = r.raison, v2x = r.v2x,
    )

    private fun montrer(
        ctx: Context, r: EnergyReader.ChargeDebug?, gen: FirmwareInfo.Gen, historique: List<String>, c: Champs,
    ) {
        val statut = r?.brut?.statut
        val enCharge = ChargeDisplay.enCharge(statut)
        c.numero.text = statut?.toString() ?: "–"
        c.numero.setTextColor(ctx.getColor(if (enCharge == true) R.color.dash_accent else R.color.text_primary))
        c.nom.text = when (statut) {
            null -> ctx.getString(R.string.charge_state_unreadable)
            else -> ChargeStateNames.etat(statut)?.let { ctx.getString(R.string.charge_state_saic_name, it) }
                ?: ctx.getString(R.string.charge_state_unknown)
        }
        val type = r?.brut?.let { ChargeDisplay.type(it.ac, it.dc) }
        c.nous.text = when (enCharge) {
            true -> ctx.getString(R.string.charge_state_ours_charging) + (type?.let { " · ${it.name}" } ?: "")
            false -> ctx.getString(R.string.charge_state_ours_idle)
            null -> ""
        }
        c.historique.text = if (historique.isEmpty()) ctx.getString(R.string.charge_state_history_empty)
            else historique.joinToString("\n")

        // Noms techniques, à recopier tels quels : pas de traduction.
        c.detail.text = buildString {
            if (r == null) {
                append("—\n\nFirmware ${gen.name}")
                return@buildString
            }
            val b = r.brut
            append("BMS_CHRG_STS 0x2140f409 : ${v(b.statut)}")
            append("\nPlug 0x2140f408 : ${v(b.prise)} · door 0x2140f43b : ${v(b.trappe)}")
            append("\nAC 0x2140f43e : ${v(b.ac)} · DC 0x2140f43f : ${v(b.dc)} · DC V 0x2140f456 : ${v(b.dcInvalide)}")
            append("\nRemaining 0x2140f417 : ${v(b.restant)}")
            ChargeDisplay.minutesRestantes(b.restant)?.let { append(" ($it min)") }
            append("\nTarget 0x2140f40c : ${v(b.cible)}")
            ChargeDisplay.ciblePourcent(b.cible)?.let { append(" ($it %)") }
            append("\nStop reason 0x2140f415 : ${v(r.raison)}")
            ChargeStateNames.raisonArret(r.raison)?.let { append(" ($it ?)") }
            append("\nV2X 0x2140f430 : ${v(r.v2x)}")
            append("\nPack 0x2160f406 × 0x2160f407 : ${un(r.volts)} V × ${un(r.amperes)} A")
            if (r.volts != null && r.amperes != null) {
                // Le courant est négatif quand la batterie se remplit.
                val kw = -r.volts * r.amperes / 1000f
                append(" = ${un(kotlin.math.abs(kw))} kW ${if (kw >= 0f) "in" else "out"}")
            }
            append("\n0x2160f43c : ${un(r.bms43c)} · 0x2160f43d : ${un(r.bms43d)}")
            append("\nSoC 0x2160f404 : ${un(r.soc)} %")
            append("\n\nFirmware ${gen.name}")
        }
    }

    private fun v(x: Int?) = x?.toString() ?: "–"

    /** Au dixième, point décimal quelle que soit la langue (voir le piège de Locale.ROOT). */
    private fun un(x: Float?) = x?.let { String.format(Locale.US, "%.1f", it) } ?: "–"

    private fun decrire(r: EnergyReader.ChargeDebug): String {
        val b = r.brut
        return "statut=${b.statut} (${ChargeStateNames.etat(b.statut) ?: "hors des tables"}, " +
            "en charge pour nous : ${ChargeDisplay.enCharge(b.statut)}) prise=${b.prise} " +
            "AC=${b.ac} DC=${b.dc} (V=${b.dcInvalide}) trappe=${b.trappe} restant=${b.restant} " +
            "cible=${b.cible} arrêt=${r.raison} V2X=${r.v2x} pack=${un(r.volts)} V × ${un(r.amperes)} A " +
            "43c=${un(r.bms43c)} 43d=${un(r.bms43d)} SoC=${un(r.soc)}"
    }
}
