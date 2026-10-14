package com.mg4.control.ui

import android.content.Context
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.mg4.control.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Les fenêtres de l'application refaites le 2026-10-13 se gonflent, et leurs mises en page
 * tiennent ce que le code leur prête : chaque vue cherchée existe, du type attendu.
 *
 * Plusieurs ne s'ouvrent que rarement — le choix de la langue une fois, le diagnostic derrière
 * cinq appuis, la mise à jour quand il y en a une : une vue introuvable ne se verrait pas avant.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppWindowsLayoutTest {

    private val themed: Context = ContextThemeWrapper(
        ApplicationProvider.getApplicationContext<Context>(), R.style.Theme_MG4Control)

    private fun gonfler(miseEnPage: Int): View = LayoutInflater.from(themed).inflate(miseEnPage, null)

    @Test
    fun `choix de la langue - une tuile par langue`() {
        val vue = gonfler(R.layout.dialog_language_picker)
        assertNotNull(vue.findViewById<ImageView>(R.id.iv_language_icon))
        assertNotNull(vue.findViewById<TextView>(R.id.tv_language_subtitle))
        listOf(
            R.id.btn_pick_fr, R.id.btn_pick_en, R.id.btn_pick_de, R.id.btn_pick_es,
            R.id.btn_pick_pt, R.id.btn_pick_it, R.id.btn_pick_tr,
        ).forEach { assertNotNull(vue.findViewById<View>(it)) }
    }

    @Test
    fun `grille de choix - la fenetre et sa tuile`() {
        val vue = gonfler(R.layout.dialog_choice)
        assertNotNull(vue.findViewById<ImageView>(R.id.choice_icon))
        assertNotNull(vue.findViewById<TextView>(R.id.choice_title))
        assertNotNull(vue.findViewById<TextView>(R.id.choice_subtitle))
        assertNotNull(vue.findViewById<ScrollView>(R.id.choice_scroll))
        val lignes = vue.findViewById<LinearLayout>(R.id.choice_rows)
        assertNotNull(vue.findViewById<TextView>(R.id.choice_cancel))

        // La tuile est posée dans une rangée horizontale, qui lui donne sa largeur par son poids.
        val rangee = LinearLayout(themed).also { lignes.addView(it) }
        val tuile = LayoutInflater.from(themed).inflate(R.layout.item_dialog_choice, rangee, false)
        assertTrue(tuile.layoutParams is LinearLayout.LayoutParams)
        assertNotNull(tuile.findViewById<ImageView>(R.id.choice_item_icon))
        assertNotNull(tuile.findViewById<TextView>(R.id.choice_item_pill))
        assertNotNull(tuile.findViewById<TextView>(R.id.choice_item_label))
    }

    @Test
    fun `listes a choix unique et segments - leurs lignes sont des textes`() {
        // AppDialog.options et AppDialog.segments convertissent la racine de ces mises en page.
        val liste = LinearLayout(themed)
        val gonfleur = LayoutInflater.from(themed)
        assertTrue(gonfleur.inflate(R.layout.item_dialog_option, liste, false) is TextView)
        assertTrue(gonfleur.inflate(R.layout.item_dialog_segment, liste, false) is TextView)
    }

    @Test
    fun `une liste a choix unique rend le rang touche`() {
        val vue = gonfler(R.layout.dialog_edit_shortcut)
        val liste = vue.findViewById<LinearLayout>(R.id.edit_list_action)
        val rang = AppDialog.options(
            liste, vue.findViewById(R.id.edit_scroll_action), listOf("A", "B", "C"), choisi = 1)

        assertEquals(3, liste.childCount)
        assertEquals(1, rang())
        liste.getChildAt(2).performClick()
        assertEquals(2, rang())
    }

    @Test
    fun `des segments signalent la case touchee, pas celle deja choisie`() {
        val vue = gonfler(R.layout.dialog_stats_tariff)
        val rangee = vue.findViewById<LinearLayout>(R.id.tariff_steps)
        val touchees = mutableListOf<Int>()
        AppDialog.segments(rangee, listOf("1", "2", "3"), choisi = 1) { touchees += it }

        rangee.getChildAt(1).performClick()
        rangee.getChildAt(2).performClick()
        assertEquals(listOf(2), touchees)
    }

    @Test
    fun `modifier un raccourci - deux listes qui defilent`() {
        val vue = gonfler(R.layout.dialog_edit_shortcut)
        assertNotNull(vue.findViewById<ImageView>(R.id.edit_shortcut_icon))
        assertNotNull(vue.findViewById<TextView>(R.id.edit_shortcut_key))
        assertNotNull(vue.findViewById<TextView>(R.id.edit_caption_action))
        assertNotNull(vue.findViewById<TextView>(R.id.edit_caption_profile))
        assertNotNull(vue.findViewById<ScrollView>(R.id.edit_scroll_action))
        assertNotNull(vue.findViewById<ScrollView>(R.id.edit_scroll_profile))
        assertNotNull(vue.findViewById<LinearLayout>(R.id.edit_list_action))
        assertNotNull(vue.findViewById<LinearLayout>(R.id.edit_list_profile))
        assertNotNull(vue.findViewById<View>(R.id.edit_cancel))
        assertNotNull(vue.findViewById<View>(R.id.edit_save))
    }

    @Test
    fun `completer la session - le type et deux horaires, chacun avec ses boutons`() {
        val vue = gonfler(R.layout.dialog_stats_complete)
        assertNotNull(vue.findViewById<ImageView>(R.id.complete_icon))
        assertNotNull(vue.findViewById<LinearLayout>(R.id.complete_types))
        assertNotNull(vue.findViewById<View>(R.id.complete_close))
        assertNotNull(vue.findViewById<View>(R.id.complete_save))
        // Le même panneau est inclus deux fois : ses vues se cherchent DANS chaque panneau.
        listOf(R.id.complete_start, R.id.complete_end).forEach { id ->
            val panneau = vue.findViewById<View>(id)
            assertNotNull(panneau.findViewById<TextView>(R.id.time_caption))
            assertNotNull(panneau.findViewById<ImageView>(R.id.time_clear))
            assertNotNull(panneau.findViewById<TextView>(R.id.time_hours))
            assertNotNull(panneau.findViewById<TextView>(R.id.time_minutes))
            listOf(
                R.id.time_hours_minus, R.id.time_hours_plus, R.id.time_minutes_minus, R.id.time_minutes_plus,
            ).forEach { assertNotNull(panneau.findViewById<ImageView>(it)) }
        }
    }

    @Test
    fun `corriger le tarif - le prix entre ses deux boutons`() {
        val vue = gonfler(R.layout.dialog_stats_tariff)
        assertNotNull(vue.findViewById<ImageView>(R.id.tariff_icon))
        assertNotNull(vue.findViewById<ImageView>(R.id.tariff_minus))
        assertNotNull(vue.findViewById<ImageView>(R.id.tariff_plus))
        assertNotNull(vue.findViewById<TextView>(R.id.tariff_value))
        assertNotNull(vue.findViewById<TextView>(R.id.tariff_unit))
        assertNotNull(vue.findViewById<LinearLayout>(R.id.tariff_steps))
        assertNotNull(vue.findViewById<View>(R.id.tariff_close))
        assertNotNull(vue.findViewById<View>(R.id.tariff_default))
        assertNotNull(vue.findViewById<View>(R.id.tariff_save))
    }

    @Test
    fun `diagnostic - les actions et le journal`() {
        val vue = gonfler(R.layout.dialog_diagnostic)
        assertNotNull(vue.findViewById<View>(R.id.diag_badge))
        assertNotNull(vue.findViewById<ImageView>(R.id.diag_icon))
        assertNotNull(vue.findViewById<TextView>(R.id.diag_crash_flag))
        assertNotNull(vue.findViewById<View>(R.id.diag_clear_crash))
        assertNotNull(vue.findViewById<TextView>(R.id.diag_copy))
        assertNotNull(vue.findViewById<View>(R.id.diag_download))
        assertNotNull(vue.findViewById<ImageView>(R.id.diag_close))
        assertNotNull(vue.findViewById<TextView>(R.id.diag_crash))
        assertNotNull(vue.findViewById<TextView>(R.id.diag_report))
        assertNotNull(vue.findViewById<TextView>(R.id.diag_logs))
    }

    @Test
    fun `a propos - les versions et les deux QR codes`() {
        val vue = gonfler(R.layout.dialog_app_info)
        assertNotNull(vue.findViewById<ImageView>(R.id.iv_info_icon))
        assertNotNull(vue.findViewById<TextView>(R.id.tv_app_version))
        assertNotNull(vue.findViewById<TextView>(R.id.tv_firmware_info))
        assertNotNull(vue.findViewById<ImageView>(R.id.iv_qr_code_github))
        assertNotNull(vue.findViewById<TextView>(R.id.tv_github_link))
        assertNotNull(vue.findViewById<ImageView>(R.id.iv_qr_code_gitlab))
        assertNotNull(vue.findViewById<TextView>(R.id.tv_gitlab_link))
        assertNotNull(vue.findViewById<View>(R.id.btn_info_close))
    }

    @Test
    fun `mise a jour - ses trois etats`() {
        val vue = gonfler(R.layout.dialog_update)
        assertNotNull(vue.findViewById<ImageView>(R.id.iv_update_icon))
        assertNotNull(vue.findViewById<ImageView>(R.id.iv_update_arrow))
        assertNotNull(vue.findViewById<TextView>(R.id.tv_version_from))
        assertNotNull(vue.findViewById<TextView>(R.id.tv_version_to))

        // Un seul état visible à l'ouverture : les nouveautés et les actions.
        assertEquals(View.VISIBLE, vue.findViewById<View>(R.id.group_info).visibility)
        assertEquals(View.GONE, vue.findViewById<View>(R.id.group_progress).visibility)
        assertEquals(View.GONE, vue.findViewById<View>(R.id.group_manual).visibility)

        assertNotNull(vue.findViewById<TextView>(R.id.tv_release_notes))
        assertNotNull(vue.findViewById<View>(R.id.row_data_warning))
        assertNotNull(vue.findViewById<TextView>(R.id.btn_update_auto))
        assertNotNull(vue.findViewById<TextView>(R.id.btn_update_manual))
        assertNotNull(vue.findViewById<TextView>(R.id.btn_update_later))
        assertNotNull(vue.findViewById<TextView>(R.id.btn_update_skip))

        assertNotNull(vue.findViewById<TextView>(R.id.tv_progress_status))
        assertNotNull(vue.findViewById<ProgressBar>(R.id.progress_bar))
        assertNotNull(vue.findViewById<TextView>(R.id.btn_cancel_download))

        assertNotNull(vue.findViewById<TextView>(R.id.tv_manual_instructions))
        assertNotNull(vue.findViewById<ImageView>(R.id.iv_update_qr))
        assertNotNull(vue.findViewById<TextView>(R.id.tv_update_gh_link))
        assertNotNull(vue.findViewById<TextView>(R.id.btn_close_manual))
    }
}
