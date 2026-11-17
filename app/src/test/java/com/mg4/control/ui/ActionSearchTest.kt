package com.mg4.control.ui

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import com.mg4.control.R
import com.mg4.control.model.RuleAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

/**
 * « Ajouter une action » (2026-11-12, maquette validée) : plus de groupes, une tuile par sujet
 * rangée dans sa catégorie ; toucher la tuile fait choisir le mode (Régler sur…, Pas à pas…).
 * La recherche trouve un sujet par son nom et par les mots de ses modes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "fr")
class ActionSearchTest {

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun racine(): View = ShadowDialog.getLatestDialog().findViewById<View>(R.id.choice_rows).rootView

    private fun libelles(racine: View): List<String> {
        val out = mutableListOf<String>()
        fun parcourir(v: View) {
            if (v.visibility != View.VISIBLE) return
            if (v.id == R.id.choice_item_label) out += (v as TextView).text.toString()
            if (v is ViewGroup) for (i in 0 until v.childCount) parcourir(v.getChildAt(i))
        }
        parcourir(racine.findViewById<LinearLayout>(R.id.choice_rows))
        return out
    }

    private fun champ(v: View): EditText? {
        if (v is EditText) return v
        if (v is ViewGroup) for (i in 0 until v.childCount) champ(v.getChildAt(i))?.let { return it }
        return null
    }

    private fun toucher(racine: View, libelle: String) {
        fun chercher(v: View): View? {
            if (v.id == R.id.choice_item_label && (v as TextView).text.toString() == libelle) return v
            if (v is ViewGroup) for (i in 0 until v.childCount) chercher(v.getChildAt(i))?.let { return it }
            return null
        }
        var v: View? = chercher(racine.findViewById(R.id.choice_rows))
        while (v != null && !v.hasOnClickListeners()) v = v.parent as? View
        v!!.performClick()
        idle()
    }

    private fun editeur(): CustomRuleEditFragment {
        val controle = Robolectric.buildActivity(FragmentActivity::class.java)
        val activite = controle.get()
        activite.setTheme(R.style.Theme_MG4Control)
        controle.setup()
        val f = CustomRuleEditFragment()
        activite.supportFragmentManager.beginTransaction().add(android.R.id.content, f).commitNow()
        idle()
        return f
    }

    private fun chercher(racine: View, texte: String): List<String> {
        champ(racine)!!.setText(texte)
        idle()
        return libelles(racine)
    }

    @Test
    fun `one tile per subject, its modes chosen after the tap, found by its modes' words`() {
        val f = editeur()
        val action = CustomRuleEditFragment::class.java.getDeclaredMethod("action",
            RuleAction::class.java, Int::class.javaPrimitiveType, kotlin.jvm.functions.Function1::class.java)
        action.isAccessible = true
        action.invoke(f, null, 0, { _: RuleAction -> })
        idle()
        val r = racine()

        // Plus de groupe « Régler la voiture » ; le volume n'a qu'une tuile, ses 4 modes annoncés.
        assertFalse(chercher(r, "voiture").contains("Régler la voiture"))
        assertEquals(listOf("Volume\n4 modes"), chercher(r, "volume").filter { it.startsWith("Volume") })
        // Les mots d'un mode trouvent le sujet : « fermer toutes » → Vitres.
        assertTrue(chercher(r, "fermer toutes").toString(), "Vitres\n2 modes" in libelles(r))
        assertTrue(chercher(r, "accueil").toString(), "Retour à l'accueil" in libelles(r))
        // Le raccourci « Paramètres Android » n'est plus proposé : la page précise le couvre.
        assertFalse(chercher(r, "parametres android").contains("Paramètres Android"))

        chercher(r, "volume")
        toucher(r, "Volume\n4 modes")
        assertEquals(listOf("Régler sur…", "Volume + / −", "Pas à pas +", "Pas à pas −"), libelles(racine()))

        // Modifier une action « Volume + » : l'étape du mode de son sujet revient.
        action.invoke(f, RuleAction(RuleAction.Type.SHORTCUT, shortcut = "VOLUME_UP"), 0, { _: RuleAction -> })
        idle()
        assertEquals(listOf("Régler sur…", "Volume + / −", "Pas à pas +", "Pas à pas −"), libelles(racine()))
    }

    @Test
    fun `triggers and conditions work like actions`() {
        val f = editeur()
        val declencheur = CustomRuleEditFragment::class.java.getDeclaredMethod("declencheur",
            com.mg4.control.model.RuleTrigger::class.java, kotlin.jvm.functions.Function1::class.java)
        declencheur.isAccessible = true
        declencheur.invoke(f, null, { _: com.mg4.control.model.RuleTrigger -> })
        idle()
        val r = racine()
        // Un raccourci devient un mode de son capteur : « Début de charge » trouve « En charge ».
        assertEquals(listOf("En charge\n3 modes"), chercher(r, "debut de charge"))
        assertFalse(chercher(r, "ready").contains("La voiture passe en READY"))
        toucher(r, "READY\n3 modes")
        assertEquals(listOf("La voiture passe en READY", "Sortie de READY", "Bascule (les deux sens)"), libelles(racine()))
        // « Pendant » demandé pour chaque mode qui suit un état, ancien raccourci compris.
        for (mode in listOf("La voiture passe en READY", "Bascule (les deux sens)", "Sortie de READY")) {
            declencheur.invoke(f, null, { _: com.mg4.control.model.RuleTrigger -> })
            idle()
            chercher(racine(), "ready")
            toucher(racine(), "READY\n3 modes")
            toucher(racine(), mode)
            assertEquals(mode, "Pendant combien de temps ?",
                ShadowDialog.getLatestDialog().findViewById<TextView>(R.id.rule_value_title).text.toString())
            ShadowDialog.getLatestDialog().dismiss()
            idle()
        }

        val condition = CustomRuleEditFragment::class.java.getDeclaredMethod("nouvelleCondition",
            Int::class.javaPrimitiveType, kotlin.jvm.functions.Function1::class.java)
        condition.isAccessible = true
        condition.invoke(f, 0, { _: com.mg4.control.model.RuleCondition -> })
        idle()
        val c = racine()
        // Une seule tuile par capteur en recherche, ses comparaisons au toucher.
        assertEquals(listOf("Batterie\n2 modes"), chercher(c, "batterie").filter { it.startsWith("Batterie") })
        toucher(c, "Batterie\n2 modes")
        assertEquals(2, libelles(racine()).size)
    }
}
