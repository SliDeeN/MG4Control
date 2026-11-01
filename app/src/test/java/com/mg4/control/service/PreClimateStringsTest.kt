package com.mg4.control.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Locale

/**
 * Textes de la pré-climatisation (issue #130) dans les sept langues — sans Android. Une langue à
 * qui il manque un texte, ou dont un texte n'a pas les mêmes paramètres que le français, ferait
 * planter la mise en forme en voiture (comme « Batterie (%) » le 2026-10-16 pour le pop-up de charge).
 */
class PreClimateStringsTest {

    private val langues = listOf("values", "values-en", "values-de", "values-es", "values-it", "values-pt", "values-tr")
    private val parametre = Regex("""%(\d+)\$([sd])""")

    private fun textes(dossier: String): Map<String, String> {
        val fichier = listOf(File("src/main/res/$dossier/strings.xml"), File("app/src/main/res/$dossier/strings.xml"))
            .first { it.exists() }
        val rx = Regex("""<string name="((?:shortcuts_[a-z]+_)?preclim[a-z_]*)"[^>]*>(.*?)</string>""")
        return rx.findAll(fichier.readText()).associate { m ->
            m.groupValues[1] to m.groupValues[2].replace("\\'", "'").replace("\\\"", "\"").replace("&amp;", "&")
        }
    }

    /** Les paramètres d'un texte, triés : (rang, type). */
    private fun parametres(texte: String) = parametre.findAll(texte).map { it.groupValues[1] to it.groupValues[2] }.toSortedSet(
        compareBy<Pair<String, String>> { it.first }.thenBy { it.second })

    @Test
    fun `chaque langue a tous les textes, avec les memes parametres que le francais`() {
        val reference = textes("values")
        assertTrue(reference.size >= 80)
        for (dossier in langues) {
            val t = textes(dossier)
            assertEquals(dossier, reference.keys, t.keys)
            reference.forEach { (nom, fr) ->
                assertEquals("$dossier/$nom", parametres(fr), parametres(t.getValue(nom)))
            }
        }
    }

    @Test
    fun `chaque texte se met en forme avec ses arguments`() {
        for (dossier in langues) {
            textes(dossier).forEach { (nom, texte) ->
                val args = parametres(texte).map { (_, type) -> if (type == "d") 7 else "18:30" }.toTypedArray()
                if (args.isEmpty()) {
                    // Jamais mis en forme : aucun « % » suivi d'un chiffre ne doit y traîner.
                    assertFalse("$dossier/$nom : $texte", Regex("%[0-9]").containsMatchIn(texte))
                } else {
                    val rendu = String.format(Locale.FRANCE, texte, *args)
                    assertFalse("$dossier/$nom : $rendu", parametre.containsMatchIn(rendu))
                }
            }
        }
    }
}
