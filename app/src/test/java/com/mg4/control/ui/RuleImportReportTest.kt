package com.mg4.control.ui

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import com.mg4.control.R
import com.mg4.control.automation.CustomRuleStore
import com.mg4.control.automation.RulePlaces
import com.mg4.control.model.CustomRule
import com.mg4.control.model.RefKind
import com.mg4.control.model.RuleCondition
import com.mg4.control.model.RulePlace
import com.mg4.control.model.RuleRef
import com.mg4.control.model.RuleRefs
import com.mg4.control.model.RuleTrigger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

/** Fin d'import (2026-11-14) : un lien manquant se réassocie dans la fenêtre, et l'automatisation est réécrite. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "fr-w1920dp-h720dp-mdpi")
class RuleImportReportTest {

    private fun textes(v: View): List<TextView> = when (v) {
        is TextView -> listOf(v)
        is ViewGroup -> (0 until v.childCount).flatMap { textes(v.getChildAt(it)) }
        else -> emptyList()
    }

    @Test
    fun `a missing place is chosen in the window and written into the rule`() {
        val act = Robolectric.buildActivity(FragmentActivity::class.java).get().apply { setTheme(R.style.Theme_MG4Control) }
        RulePlaces.save(act, listOf(RulePlace(id = "m9", name = "Maison"), RulePlace(id = "b4", name = "Bureau")))
        CustomRuleStore.add(act, CustomRule(id = "nA", name = "Arrivée", enabled = false,
            triggers = listOf(RuleTrigger(RuleTrigger.Type.BECOMES, condition = RuleCondition(RuleCondition.Type.PLACE, text = "p2")))))
        val liens = listOf(
            RuleRefs.Link("nA", "Arrivée", RuleRef(RefKind.PLACE, "p1"), "Maison", RuleRefs.Status.BY_NAME, "m9"),
            RuleRefs.Link("nA", "Arrivée", RuleRef(RefKind.PLACE, "p2"), "Travail", RuleRefs.Status.MISSING, null),
        )
        RuleImportReport.afficher(act, 1, liens) {}
        shadowOf(Looper.getMainLooper()).idle()
        val rapport = ShadowDialog.getLatestDialog()
        val racine = rapport.window!!.decorView
        val t = textes(racine).map { it.text.toString() }
        assertNotNull(t.firstOrNull { it == "« Maison » relié par son nom" })
        assertNotNull(t.firstOrNull { it == "« Travail » introuvable" })
        assertEquals("Plus tard", racine.findViewById<TextView>(R.id.rule_import_close).text.toString())

        textes(racine).first { it.text == "Choisir un lieu" }.performClick()
        shadowOf(Looper.getMainLooper()).idle()
        val choix = ShadowDialog.getLatestDialog()
        val bureau = textes(choix.window!!.decorView).first { it.text == "Bureau" }
        var cliquable: View = bureau
        while (!cliquable.isClickable) cliquable = cliquable.parent as View
        cliquable.performClick()
        shadowOf(Looper.getMainLooper()).idle()

        val regle = CustomRuleStore.all(act).first { it.id == "nA" }
        assertEquals("b4", regle.triggers[0].condition!!.text)
        val apres = textes(racine).map { it.text.toString() }
        assertNotNull(apres.firstOrNull { it == "relié à « Bureau »" })
        assertEquals("Terminé", racine.findViewById<TextView>(R.id.rule_import_close).text.toString())
    }
}
