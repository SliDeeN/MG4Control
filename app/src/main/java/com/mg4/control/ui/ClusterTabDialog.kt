package com.mg4.control.ui

import android.content.Context
import android.content.res.ColorStateList
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.Switch
import android.widget.TextView
import com.mg4.control.R
import com.mg4.control.debug.AppLogger
import com.mg4.control.hardware.ClusterReadySignal
import com.mg4.control.hardware.ClusterTabReader
import com.mg4.control.model.ClusterTab
import com.mg4.control.service.ClusterModuleOverlay
import com.mg4.control.service.OverlayKit
import com.mg4.control.util.ClusterModuleSettings
import com.mg4.control.util.FirmwareInfo
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Fenêtre « Onglet du combiné » (Réglages, débloquée avec le Diagnostic — demande du 2026-10-14).
 *
 * Montre en direct la valeur de l'onglet choisi au volant, pour relever une à une celles de
 * chaque voiture : le code SAIC prévoit huit onglets, une SWI133 en montre cinq, dont un (la
 * pression des pneus) qu'il ne nomme pas. Chaque changement est noté dans le journal, et donc
 * dans le rapport du Diagnostic.
 *
 * Sur A9 seulement, un interrupteur d'essai envoie au combiné « image prête » pour la navigation
 * ([ClusterReadySignal]) et force l'affichage du module sur tous les onglets. Il est coupé — signal
 * remis à 0 — à la fermeture de la fenêtre.
 */
object ClusterTabDialog {

    private const val TAG = "MG4_CLUSTER"

    /** Assez vif pour suivre un changement d'onglet au volant, assez lâche pour ne rien coûter. */
    private const val CADENCE_MS = 300L

    /** Pendant l'essai, le signal est renvoyé s'il n'est plus à 1 — au plus une fois par seconde. */
    private const val RENVOI_MS = 1_000L

    private val NOMS = mapOf(
        ClusterTab.TRIP_COMPUTER to R.string.cluster_tab_0,
        ClusterTab.NAVIGATION to R.string.cluster_tab_1,
        ClusterTab.MEDIA to R.string.cluster_tab_2,
        ClusterTab.CALL to R.string.cluster_tab_3,
        ClusterTab.WARNING_CENTRE to R.string.cluster_tab_4,
        ClusterTab.SETTINGS to R.string.cluster_tab_5,
        ClusterTab.ACTIVE_SAFETY to R.string.cluster_tab_6,
        ClusterTab.RESERVED to R.string.cluster_tab_7,
    )

    fun afficher(ctx: Context) {
        val vue = LayoutInflater.from(ctx).inflate(R.layout.dialog_cluster_tab, null)
        OverlayKit.fond(vue.findViewById(R.id.cluster_tab_badge), ctx.getColor(R.color.dash_accent_dim), ovale = true)
        vue.findViewById<ImageView>(R.id.cluster_tab_icon).imageTintList =
            ColorStateList.valueOf(ctx.getColor(R.color.dash_accent))
        vue.findViewById<ImageView>(R.id.cluster_tab_close).imageTintList =
            ColorStateList.valueOf(ctx.getColor(R.color.text_primary))
        val numero = vue.findViewById<TextView>(R.id.cluster_tab_number)
        val nom = vue.findViewById<TextView>(R.id.cluster_tab_name)
        val detail = vue.findViewById<TextView>(R.id.cluster_tab_detail)

        val dialog = AppDialog.afficher(ctx, vue, 760f)
        vue.findViewById<View>(R.id.cluster_tab_close).setOnClickListener { dialog.dismiss() }

        // Les lectures passent par le binder : sur un fil à elles, arrêté avec la fenêtre.
        val fil = HandlerThread("mg4-cluster-tab").also { it.start() }
        val lecteur = Handler(fil.looper)
        val main = Handler(Looper.getMainLooper())
        val swi133 = FirmwareInfo.getGeneration() == FirmwareInfo.Gen.SWI133
        var precedente: ClusterTabReader.Lecture? = null

        // ── Essai A9 : « image prête » pour la navigation, affichage forcé ──
        val essaiActif = AtomicBoolean(false)
        var dernierEnvoi = 0L
        if (ClusterReadySignal.applicable()) {
            vue.findViewById<View>(R.id.cluster_test).visibility = View.VISIBLE
            vue.findViewById<Switch>(R.id.cluster_test_switch).setOnCheckedChangeListener { _, actif ->
                essaiActif.set(actif)
                ClusterModuleOverlay.essaiForce = actif
                AppLogger.i(TAG, "essai A9 ${if (actif) "lancé" else "arrêté"} (module du combiné " +
                    "${if (ClusterModuleSettings.isEnabled(ctx)) "activé" else "DÉSACTIVÉ — rien ne sera dessiné"})")
                lecteur.post {
                    ClusterReadySignal.envoyer(if (actif) ClusterReadySignal.READY else ClusterReadySignal.NOT_READY)
                    dernierEnvoi = SystemClock.elapsedRealtime()
                }
            }
        }

        val releve = object : Runnable {
            override fun run() {
                val lecture = ClusterTabReader.lire()
                if (lecture != precedente) {
                    AppLogger.i(TAG, "Onglet du combiné : ${precedente?.let { decrire(it) } ?: "ouverture"} → ${decrire(lecture)}")
                    precedente = lecture
                }
                // Le combiné ou le service SAIC peuvent remettre le signal à 0 : on le renvoie.
                val maintenant = SystemClock.elapsedRealtime()
                if (essaiActif.get() && lecture.pret != ClusterReadySignal.READY && maintenant - dernierEnvoi >= RENVOI_MS) {
                    ClusterReadySignal.envoyer(ClusterReadySignal.READY)
                    dernierEnvoi = maintenant
                }
                main.post { if (dialog.isShowing) montrer(ctx, lecture, swi133, numero, nom, detail) }
                lecteur.postDelayed(this, CADENCE_MS)
            }
        }
        lecteur.post(releve)
        dialog.setOnDismissListener {
            lecteur.removeCallbacksAndMessages(null)
            // L'essai ne survit pas à la fenêtre : signal remis à 0, affichage rendu aux onglets.
            if (essaiActif.getAndSet(false)) {
                ClusterModuleOverlay.essaiForce = false
                lecteur.post { ClusterReadySignal.envoyer(ClusterReadySignal.NOT_READY) }
                AppLogger.i(TAG, "essai A9 arrêté à la fermeture de la fenêtre")
            }
            fil.quitSafely()
            AppLogger.i(TAG, "Onglet du combiné : fenêtre fermée")
        }
    }

    private fun montrer(
        ctx: Context, l: ClusterTabReader.Lecture, swi133: Boolean,
        numero: TextView, nom: TextView, detail: TextView,
    ) {
        val valeur = l.valeur
        numero.text = valeur?.toString() ?: "–"
        nom.text = when (valeur) {
            null -> ctx.getString(R.string.cluster_tab_unreadable)
            else -> ClusterTab.fromValue(valeur)?.let { ctx.getString(R.string.cluster_tab_code_name, ctx.getString(NOMS.getValue(it))) }
                ?: ctx.getString(R.string.cluster_tab_unknown)
        }
        // Noms techniques, à recopier tels quels : pas de traduction.
        detail.text = buildString {
            append("IPK_CLUSTER_TAB_BTN_STS 0x21407b18 : ${l.cpm ?: "–"}")
            if (swi133) append("\nVPM 0x2030013 : ${l.vpm ?: "–"}")
            append("\nIPK_CNTR_AREA 0x21407b0a : ${l.centre ?: "–"}")
            append("\nFICM_NAVI_READY_STS 0x21407b16 : ${l.pret ?: "–"}")
        }
    }

    private fun decrire(l: ClusterTabReader.Lecture): String =
        "${l.valeur ?: "illisible"} (${ClusterTab.fromValue(l.valeur)?.name ?: "hors du code"}) " +
            "[cpm=${l.cpm} vpm=${l.vpm} centre=${l.centre} prête=${l.pret}]"
}
