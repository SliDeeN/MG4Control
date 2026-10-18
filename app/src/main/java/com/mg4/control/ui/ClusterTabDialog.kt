package com.mg4.control.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Point
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import com.mg4.control.R
import com.mg4.control.debug.AppLogger
import com.mg4.control.hardware.ClusterTabReader
import com.mg4.control.model.ClusterTab
import com.mg4.control.service.ClusterModuleOverlay
import com.mg4.control.service.OverlayKit
import com.mg4.control.util.ClusterModuleSettings
import com.mg4.control.util.FirmwareInfo

/**
 * Fenêtre « Onglet du combiné » (Réglages, débloquée avec le Diagnostic — 2026-10-14, remise le
 * 2026-10-18 pour les relevés d'un testeur SWI165).
 *
 * Montre en direct la valeur de l'onglet choisi au volant, pour relever une à une celles de
 * chaque voiture : le code SAIC prévoit huit onglets, une SWI133 en montre cinq. Dessous, les
 * valeurs brutes, l'écran du combiné trouvé et le module que l'appli y dessinerait. Chaque
 * changement est noté dans le journal, et donc dans le rapport du Diagnostic.
 *
 * Lecture seule : l'essai A9 « image prête » qui l'accompagnait a été retiré avec le dossier A9.
 */
object ClusterTabDialog {

    private const val TAG = "MG4_CLUSTER"

    /** Assez vif pour suivre un changement d'onglet au volant, assez lâche pour ne rien coûter. */
    private const val CADENCE_MS = 300L

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

        // Ce qui ne bouge pas pendant que la fenêtre est ouverte : firmware et écran du combiné.
        val gen = FirmwareInfo.getGeneration()
        val ecran = runCatching { ClusterModuleOverlay.ecranDuCombine(ctx) }.getOrNull()?.let { e ->
            // Taille physique de l'écran du combiné, comme la ligne d'état d'autrefois.
            @Suppress("DEPRECATION") val taille = Point().also { e.getRealSize(it) }
            "#${e.displayId} ${taille.x} × ${taille.y}"
        } ?: "—"
        AppLogger.i(TAG, "Onglet du combiné : fenêtre ouverte — firmware ${gen.name}, écran du combiné $ecran")

        // Les lectures passent par le binder : sur un fil à elles, arrêté avec la fenêtre.
        val fil = HandlerThread("mg4-cluster-tab").also { it.start() }
        val lecteur = Handler(fil.looper)
        val main = Handler(Looper.getMainLooper())
        var precedente: ClusterTabReader.Lecture? = null

        val releve = object : Runnable {
            override fun run() {
                val lecture = ClusterTabReader.lire()
                if (lecture != precedente) {
                    AppLogger.i(TAG, "Onglet du combiné : ${precedente?.let { decrire(it) } ?: "ouverture"} → ${decrire(lecture)}")
                    precedente = lecture
                }
                main.post { if (dialog.isShowing) montrer(ctx, lecture, gen, ecran, numero, nom, detail) }
                lecteur.postDelayed(this, CADENCE_MS)
            }
        }
        lecteur.post(releve)
        dialog.setOnDismissListener {
            lecteur.removeCallbacksAndMessages(null)
            fil.quitSafely()
            AppLogger.i(TAG, "Onglet du combiné : fenêtre fermée")
        }
    }

    private fun montrer(
        ctx: Context, l: ClusterTabReader.Lecture, gen: FirmwareInfo.Gen, ecran: String,
        numero: TextView, nom: TextView, detail: TextView,
    ) {
        val valeur = l.valeur
        numero.text = valeur?.toString() ?: "–"
        nom.text = when (valeur) {
            null -> ctx.getString(R.string.cluster_tab_unreadable)
            else -> ClusterTab.fromValue(valeur)?.let { ctx.getString(R.string.cluster_tab_code_name, ctx.getString(NOMS.getValue(it))) }
                ?: ctx.getString(R.string.cluster_tab_unknown)
        }
        // Le module que le service dessinerait sur cet onglet, d'après les réglages du moment.
        val module = ClusterModuleSettings.moduleToShow(valeur,
            ClusterModuleSettings.moduleFor(ctx, ClusterTab.NAVIGATION), ClusterModuleSettings.moduleFor(ctx, ClusterTab.MEDIA))
        val etat = when {
            !ClusterModuleSettings.isSupported() -> "firmware not supported"
            !ClusterModuleSettings.isEnabled(ctx) -> "disabled in Settings"
            else -> "enabled"
        }
        // Noms techniques, à recopier tels quels : pas de traduction.
        detail.text = buildString {
            append("IPK_CLUSTER_TAB_BTN_STS 0x21407b18 : ${l.cpm ?: "–"}")
            if (gen == FirmwareInfo.Gen.SWI133) append("\nVPM 0x2030013 : ${l.vpm ?: "–"}")
            append("\nIPK_CNTR_AREA 0x21407b0a : ${l.centre ?: "–"}")
            append("\nFICM_NAVI_READY_STS 0x21407b16 : ${l.pret ?: "–"}")
            append("\nFICM_START_UP_STS 0x21407b00 : ${l.demarre ?: "–"}")
            append("\nLVDS_ELEC_READY 0x21407b4a : ${l.lvds ?: "–"}")
            append("\n\nFirmware ${gen.name} · display $ecran")
            append("\nModule on this tab : ${module?.name ?: "none"} ($etat)")
        }
    }

    private fun decrire(l: ClusterTabReader.Lecture): String =
        "${l.valeur ?: "illisible"} (${ClusterTab.fromValue(l.valeur)?.name ?: "hors du code"}) " +
            "[cpm=${l.cpm} vpm=${l.vpm} centre=${l.centre} prête=${l.pret} démarré=${l.demarre} lvds=${l.lvds}]"
}
