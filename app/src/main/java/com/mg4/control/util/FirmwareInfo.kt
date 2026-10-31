package com.mg4.control.util

import android.annotation.SuppressLint
import android.content.Context

/**
 * Détecte la génération de firmware à partir de ro.build.mt2712.version.
 *
 * SWI133 : "SWI133-xxxxx" — ADAS via getMixProperty(0x32), 5 modes, 2 alertes, sièges+volant chauffants
 * SWI132 : "SWI132-xxxxx" — ADAS via CarVehicleSettingClient (acc/tja getAccTjaState), alertes via binder
 *                            direct IVehicleSettingService (TX 0x128/0x12a), même section UI que SWI68
 * SWI68  : "SWI68-xxxxx"  — ADAS via VehicleSettingManager (acc/tja), sièges+volant chauffants
 * SWI69  : "SWI69-xxxxx"  — ADAS via VehicleSettingManager "nouvelle gen", sans sièges/volant chauffants
 * SWI131 : "SWI131-xxxxx" — Identique SWI69 (même package, même API), sans sièges/volant chauffants
 * SWI165 : "SWI165-xxxxx" — ADAS via VehicleSettingManager (même SDK que SWI68), AEB via setFcwAlarmMode
 *                            sièges+volant chauffants disponibles
 * UNKNOWN : firmware non reconnu — l'utilisateur peut forcer un mode de compatibilité
 *
 * Firmwares de distributeur : même logiciel, autre préfixe. « DBI68-29558-1X00RXX-20250530 »
 * (Doğan Trend, Turquie — fork sensoyg, 2026-10-30) est un SWI68. Liste EXPLICITE de préfixes, pas
 * de motif générique « 2 à 4 lettres + numéro » : un numéro de build quelconque passerait pour
 * un firmware, et l'appli piloterait la voiture avec les commandes d'un autre.
 */
object FirmwareInfo {

    enum class Gen { SWI133, SWI132, SWI68, SWI69, SWI131, SWI165, UNKNOWN }

    private const val PREF_NAME       = "mg4_settings"
    private const val PREF_FORCED_GEN = "forced_firmware_gen"

    @Volatile private var cached:         Gen?    = null
    @Volatile private var detectedString: String? = null

    /**
     * Lit le choix forcé éventuel et l'applique au cache. À appeler AU PLUS TÔT dans chaque point
     * d'entrée du processus : MG4App, BootReceiver, MG4ControlService, MainActivity. Avant le
     * 2026-10-30, seule MainActivity le faisait : au démarrage de la voiture, le service tournait en
     * UNKNOWN chez qui avait forcé un mode, tant que l'écran de l'appli n'avait pas été ouvert
     * (MG4Hardware.init prenait le chemin SWI133 et réessayait en boucle).
     *
     * Le service et le récepteur de démarrage tournent aussi avant le déverrouillage (directBootAware) :
     * les réglages y sont illisibles, la lecture ne doit pas faire planter le démarrage. L'appel
     * suivant (service, écran) refera la lecture.
     */
    fun initWithContext(context: Context) {
        val forced = runCatching {
            context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .getString(PREF_FORCED_GEN, null)
        }.getOrNull() ?: return
        cached = runCatching { Gen.valueOf(forced) }.getOrDefault(Gen.UNKNOWN)
    }

    /** Retourne la génération active (forcée ou détectée automatiquement). */
    fun getGeneration(): Gen {
        // UNKNOWN aussi est gardé en cache : la génération est demandée à chaque lecture ou écriture
        // véhicule, relire les propriétés système à chaque fois coûterait pour rien.
        cached?.let { return it }
        val version = readProp("ro.build.mt2712.version")
            ?: readProp("ro.build.version.incremental")
        detectedString = version
        val gen = parseGeneration(version)
        cached = gen
        return gen
    }

    /** Préfixes reconnus : SAIC (SWI) et distributeurs connus (DBI, Turquie). */
    private val PREFIXES = listOf("SWI", "DBI")

    /**
     * Génération d'après la chaîne de version, « SWI133-xxxxx » comme « DBI68-xxxxx ». Le numéro doit
     * être suivi d'un caractère non numérique (ou de la fin) : « SWI1330 » n'est pas un SWI133.
     */
    fun parseGeneration(version: String?): Gen {
        val v = version?.trim()?.uppercase() ?: return Gen.UNKNOWN
        val prefix = PREFIXES.firstOrNull { v.startsWith(it) } ?: return Gen.UNKNOWN
        val numero = v.substring(prefix.length).takeWhile { it.isDigit() }
        return when (numero) {
            "165" -> Gen.SWI165
            "133" -> Gen.SWI133
            "132" -> Gen.SWI132
            "131" -> Gen.SWI131
            "68"  -> Gen.SWI68
            "69"  -> Gen.SWI69
            else  -> Gen.UNKNOWN
        }
    }

    /**
     * Chaîne brute lue depuis les propriétés système (ex: "SWI131-12345-xxx").
     * Utile pour afficher la version exacte à l'utilisateur dans le dialog de warning.
     */
    fun getDetectedString(): String {
        if (detectedString == null) getGeneration()
        return detectedString ?: "?"
    }

    /**
     * Force le mode de compatibilité manuellement.
     * Le choix est persisté dans les SharedPreferences et survit aux redémarrages.
     */
    fun forceGeneration(context: Context, gen: Gen) {
        cached = gen
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit().putString(PREF_FORCED_GEN, gen.name).apply()
    }

    /**
     * Retourne true si le mode de compatibilité a été forcé manuellement
     * (par opposition à une détection automatique réussie).
     */
    fun isForced(context: Context): Boolean =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .contains(PREF_FORCED_GEN)

    // ── Helpers de capacités ─────────────────────────────────────────────────

    /**
     * SWI69 et SWI131 utilisent la même API "nouvelle génération" VSM :
     *   package com.saicmotor.vehiclesetting.service
     *   constructeur IBinder-direct
     *   méthodes : setAccTjaState, setFcwState, setLasWarningSound…
     */
    fun isNewGenVsm(): Boolean {
        val gen = getGeneration()
        return gen == Gen.SWI69 || gen == Gen.SWI131
    }

    /** Firmwares « A9 » : SWI69, SWI131 et SWI132. */
    fun isA9(): Boolean {
        val gen = getGeneration()
        return gen == Gen.SWI69 || gen == Gen.SWI131 || gen == Gen.SWI132
    }

    /**
     * SWI68, SWI69, SWI131 et SWI165 utilisent tous le VehicleSettingManager SAIC
     * pour l'ADAS (ACC/TJA) et les alertes sonores.
     * SWI133 utilise VehiclePropertyManager (getMixProperty).
     */
    fun isVsmBased(): Boolean {
        val gen = getGeneration()
        return gen == Gen.SWI68 || gen == Gen.SWI69 || gen == Gen.SWI131 || gen == Gen.SWI132 || gen == Gen.SWI165
    }

    /**
     * SWI133, SWI68 et SWI165 ont les sièges chauffants et le volant chauffant.
     * SWI69 et SWI131 sont des finitions Standard/SE sans ces équipements.
     */
    fun hasHeatFeatures(): Boolean {
        val gen = getGeneration()
        return gen == Gen.SWI133 || gen == Gen.SWI68 || gen == Gen.SWI165
    }

    /**
     * SWI165 utilise le même SDK VehicleSettingManager que SWI68.
     * L'AEB est contrôlé par setFcwAlarmMode() (même API que SWI68).
     * setAutoEmergencyBraking() existe dans le SDK mais n'est pas utilisé par l'app officielle.
     */
    fun isSWI165(): Boolean = getGeneration() == Gen.SWI165

    private fun readProp(key: String): String? = try {
        @SuppressLint("PrivateApi")   // SystemProperties : API cachée d'Android, lue par réflexion : l'app tourne en uid système (voulu).
        val sp  = Class.forName("android.os.SystemProperties")
        val get = sp.getMethod("get", String::class.java, String::class.java)
        (get.invoke(null, key, "") as? String)?.takeIf { it.isNotBlank() && it != "0" }
    } catch (_: Exception) { null }
}
