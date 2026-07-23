package com.mg4.control

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * [T-909] R8 est activé en release et le code est très réflexif. Ces règles ne peuvent pas
 * être vérifiées par un test unitaire classique (les tests tournent sur des classes non
 * minifiées), mais leur disparition casse la release de façon SILENCIEUSE — d'où ce
 * garde-fou sur le fichier lui-même.
 */
class ProguardRulesTest {

    private val rules: String by lazy {
        val file = File("proguard-rules.pro")
        assertTrue("proguard-rules.pro introuvable depuis ${File(".").absolutePath}",
            file.exists())
        file.readText()
    }

    @Test
    fun `l attribut Signature est conserve pour Gson`() {
        // Sans Signature, TypeToken<List<DrivingProfile>> perd son type générique et Gson
        // rend des LinkedTreeMap : les profils disparaissent au premier lancement.
        assertTrue("l'attribut Signature doit rester conservé",
            Regex("""-keepattributes[^\n]*\bSignature\b""").containsMatchIn(rules))
    }

    @Test
    fun `les cibles de reflexion restent conservees`() {
        listOf(
            "android.car.**",
            "android.os.ServiceManager",
            "android.os.SystemProperties",
            "com.saicmotor.**",
            "com.mg4.hardware.model.**",
            "com.mg4.hardware.MG4Hardware"
        ).forEach { target ->
            assertTrue("règle -keep manquante pour $target",
                rules.contains("-keep class $target"))
        }
    }

    @Test
    fun `les numeros de ligne sont conserves pour les rapports de crash`() {
        assertTrue("sans LineNumberTable, CrashLogger produit des traces inexploitables",
            Regex("""-keepattributes[^\n]*\bLineNumberTable\b""").containsMatchIn(rules))
    }
}
