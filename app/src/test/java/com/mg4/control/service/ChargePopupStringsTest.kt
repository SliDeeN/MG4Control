package com.mg4.control.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Locale

/**
 * Textes du pop-up de charge, dans les sept langues, mis en forme comme le fait le pop-up — sans
 * Android, donc aussi sur un poste où les tests Robolectric ne tournent pas.
 *
 * Né d'une panne : « Batterie (%) » passée à la mise en forme levait une exception et le pop-up ne
 * s'affichait jamais (diag SWI133 du 2026-10-16). Les textes sans argument ne doivent JAMAIS être
 * mis en forme ([ChargePopupOverlay.Vues.s]) ; ceux qui en ont doivent accepter les leurs.
 */
class ChargePopupStringsTest {

    private val langues = listOf("values", "values-en", "values-de", "values-es", "values-it", "values-pt", "values-tr")

    /** Arguments passés par le pop-up à chaque texte mis en forme. */
    private val arguments: Map<String, Array<Any>> = mapOf(
        "charge_popup_hint_release" to arrayOf(90),
        "charge_popup_hint_below" to arrayOf(60),
        "charge_popup_hint_sending" to arrayOf(90),
        "charge_popup_hint_refused" to arrayOf(90),
        "charge_popup_until_unknown" to arrayOf(80),
        "charge_popup_end_left" to arrayOf("26 min", 100),
        "charge_popup_since" to arrayOf("15:31"),
        "charge_popup_target" to arrayOf(80),
        "charge_popup_end_estimate" to arrayOf("15:55"),
        "charge_popup_power_max" to arrayOf("134"),
        "charge_popup_tariff_default" to arrayOf("0,490", "€", "DC"),
        "charge_popup_tariff_fixed" to arrayOf("0,490", "€"),
        "charge_popup_since_time" to arrayOf("15:31"),
    )

    private fun textes(dossier: String): Map<String, String> {
        val fichier = listOf(File("src/main/res/$dossier/strings.xml"), File("app/src/main/res/$dossier/strings.xml"))
            .first { it.exists() }
        val rx = Regex("""<string name="(charge_popup_[a-z_]+)"[^>]*>(.*?)</string>""")
        return rx.findAll(fichier.readText()).associate { m ->
            m.groupValues[1] to m.groupValues[2].replace("\\'", "'").replace("&amp;", "&")
        }
    }

    @Test
    fun `chaque langue a tous les textes, et chacun se met en forme avec ses arguments`() {
        val reference = textes("values").keys
        assertTrue(reference.size >= 35)
        for (dossier in langues) {
            val t = textes(dossier)
            assertEquals(dossier, reference, t.keys)
            for ((nom, args) in arguments) {
                val rendu = String.format(Locale.FRANCE, t.getValue(nom), *args)
                // Un paramètre resté tel quel (« %1$s ») : pas « %100 », le turc met le signe devant.
                assertFalse("$dossier/$nom : $rendu", Regex("""%\d+\$""").containsMatchIn(rendu))
            }
        }
    }

    @Test
    fun `les textes sans argument ne contiennent pas de mise en forme`() {
        for (dossier in langues) {
            textes(dossier).filterKeys { it !in arguments }.forEach { (nom, texte) ->
                assertFalse("$dossier/$nom : $texte", Regex("%[0-9]").containsMatchIn(texte))
            }
        }
    }
}
