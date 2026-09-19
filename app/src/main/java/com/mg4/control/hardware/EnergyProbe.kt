package com.mg4.control.hardware

import com.mg4.control.debug.AppLogger
import com.mg4.control.util.FirmwareInfo

/**
 * Sonde énergie, en **lecture seule** : de quoi décider si l'onglet Statistiques est réalisable,
 * et sur quels firmwares.
 *
 * Elle n'écrit rien et ne s'abonne à rien. Trois questions, dans cet ordre d'importance :
 *
 *  1. **Que publie la voiture ?** `CarBMSManager.getPropertyList()` fait énumérer au véhicule
 *     lui-même les propriétés qu'il porte : c'est la seule réponse qui ne soit pas une supposition.
 *  2. **Que valent les compteurs ?** Énergie depuis le départ et depuis la dernière charge, avec
 *     leur ventilation (climatisation, accessoires, régénération), SoC, état de charge, odomètre.
 *  3. **La valeur est-elle valide ?** SAIC publie des propriétés jumelles suffixées `V`
 *     (`BMS_PACK_SOC_DSPV` 0x2120f422) : une valeur lue sans son drapeau peut être figée.
 *
 * Chaque relevé est journalisé sous [TAG] et gardé dans [reports] pour le rapport de diagnostic.
 * Un relevé ne vaut qu'à l'instant où il est pris : il en faut plusieurs — avant un trajet,
 * après, pendant une charge — pour établir les unités et les remises à zéro.
 *
 * Identifiants relevés dans les firmwares décompilés (`YFVehicleProperty`), déclarés avec la
 * permission `CAR_VENDOR_EXTENSION` dans les services véhicule SWI133, SWI68, SWI165 et SWI131.
 */
object EnergyProbe {

    const val TAG = "MG4_ENERGY"

    /**
     * Les derniers relevés, pour que le rapport de diagnostic les porte TOUS.
     *
     * Le journal de l'application ne garde que ses dernières lignes et un relevé en fait une
     * trentaine : sans cette pile, celui d'avant le trajet aurait disparu au moment où le testeur
     * envoie son fichier, après la charge. Or c'est justement la comparaison entre relevés qui
     * donne les unités et les remises à zéro.
     */
    private val historique = ArrayDeque<String>()

    private const val MAX_RELEVES = 8

    /** Tous les relevés gardés, du plus ancien au plus récent. */
    val reports: List<String>
        @Synchronized get() = historique.toList()

    /** Zone des propriétés SAIC — la même que partout ailleurs dans l'app. */
    private const val AREA_GLOBAL = 0x1000000

    private const val BMS_SERVICE = "bms"
    private const val AAD_SERVICE = "advanced_assisted_driving"

    /**
     * Une propriété à relever.
     *
     * [validity] : la propriété jumelle qui dit si la valeur a du sens (null quand il n'y en a pas).
     * [service] : le gestionnaire qui la porte d'après le firmware — on tente quand même le
     * CarPropertyManager derrière, les deux voies aboutissant au même service de propriétés.
     */
    private data class Signal(
        val name: String,
        val id: Int,
        val service: String?,
        val validity: Int? = null,
    )

    private val SIGNALS = listOf(
        // ── Batterie ────────────────────────────────────────────────────────
        Signal("SoC (BMS_PACK_SOC_DSP)", 0x2160f404, BMS_SERVICE, validity = 0x2120f422),
        Signal("SoC (BMS_PACK_SOC)", 0x2160f405, BMS_SERVICE),
        Signal("SoC (BAT_SOC)", 0x2160f45b, BMS_SERVICE),
        Signal("Niveau batterie AOSP (Wh ?)", 0x11600309, null),

        // ── Charge ──────────────────────────────────────────────────────────
        Signal("État de charge (BMS_CHRG_STS)", 0x2140f409, BMS_SERVICE),
        Signal("Prise branchée (BMS_CHRG_PLUG)", 0x2140f408, BMS_SERVICE),
        Signal("Prise alternatif (CCU_ONBD)", 0x2140f43e, BMS_SERVICE),
        Signal("Prise continu (CCU_OFFBD)", 0x2140f43f, BMS_SERVICE),
        Signal("Temps de charge restant", 0x2140f417, BMS_SERVICE, validity = 0x2120f426),
        Signal("Courant de charge", 0x2160f40a, BMS_SERVICE, validity = 0x2120f424),
        Signal("SoC cible", 0x2140f40c, BMS_SERVICE),
        Signal("Puissance de charge AOSP (mW ?)", 0x1160030c, null),
        // Trouvés par le balayage le 2026-09-19, en charge alternative à 5,2 kW annoncés :
        // 408,25 V × 12,8 A = 5,23 kW. Le courant est NÉGATIF quand la batterie se remplit.
        Signal("Tension batterie (V)", 0x2160f406, BMS_SERVICE),
        Signal("Courant batterie (A, négatif = charge)", 0x2160f407, BMS_SERVICE),
        Signal("Température batterie (°C)", 0x2160f43c, BMS_SERVICE),
        Signal("Tension secteur (V)", 0x2160f43d, BMS_SERVICE),
        Signal("Prise connectée AOSP", 0x1120030b, null),

        // ── Énergie : les compteurs qui feraient les trajets ────────────────
        Signal("Énergie totale depuis la charge", 0x2160a1bb, AAD_SERVICE),
        Signal("Énergie totale depuis le départ", 0x2160a1bc, AAD_SERVICE),
        Signal("Accessoires depuis la charge", 0x2160a1bd, AAD_SERVICE),
        Signal("Accessoires depuis le départ", 0x2160a1be, AAD_SERVICE),
        Signal("Régénération depuis la charge", 0x2160a1bf, AAD_SERVICE),
        Signal("Régénération depuis le départ", 0x2160a1c0, AAD_SERVICE),
        Signal("Climatisation depuis la charge", 0x2160a1c1, AAD_SERVICE),
        Signal("Climatisation depuis le départ", 0x2160a1c2, AAD_SERVICE),
        Signal("Consommation moyenne", 0x2160f421, BMS_SERVICE),

        // ── Distance ────────────────────────────────────────────────────────
        Signal("Odomètre AOSP", 0x11600204, null),
        Signal("Kilométrage total SAIC", 0x21401566, null),
        Signal("Trajet combiné : distance", 0x21407b81, null),
        Signal("Trajet combiné : vitesse moyenne", 0x21407b80, null),
        Signal("Trajet combiné : conso moyenne", 0x21407b82, null),
        Signal("Autonomie restante (SENSOR)", 0x21401565, null),
        Signal("Autonomie restante (BMS)", 0x2140f41c, BMS_SERVICE),

        // ── Les six drapeaux « V » de la famille BMS ────────────────────────
        // Relevé du 2026-09-18 : le SoC était JUSTE avec son drapeau à false, et deux sentinelles
        // (1023, 511,5) avaient le leur à true. Le drapeau semble donc signaler l'INVALIDITÉ.
        // On relève les six pour le vérifier, une charge en cours devant les faire basculer.
        Signal("Drapeau 0x2120f422 (SoC affiché)", 0x2120f422, BMS_SERVICE),
        Signal("Drapeau 0x2120f423", 0x2120f423, BMS_SERVICE),
        Signal("Drapeau 0x2120f424 (courant de charge)", 0x2120f424, BMS_SERVICE),
        Signal("Drapeau 0x2120f425", 0x2120f425, BMS_SERVICE),
        Signal("Drapeau 0x2120f426 (temps restant)", 0x2120f426, BMS_SERVICE),
        Signal("Drapeau 0x2120f427", 0x2120f427, BMS_SERVICE),
    )

    /**
     * Relève tout et renvoie le texte du rapport. À appeler hors du fil principal : chaque lecture
     * est un aller-retour binder, et il y en a une trentaine.
     */
    fun run(origin: String): String {
        val lignes = mutableListOf<String>()
        lignes += "══ Sonde énergie [$origin] · ${FirmwareInfo.getGeneration()} ══"

        val bms = carManager(BMS_SERVICE)
        val aad = carManager(AAD_SERVICE)
        lignes += "Gestionnaires : bms=${present(bms)} · advanced_assisted_driving=${present(aad)} · " +
            "cpm=${present(MG4Hardware.carPropertyManager())}"

        // La question la plus utile du relevé : la voiture énumère ce qu'elle porte vraiment.
        lignes += inventaire("bms", bms)
        lignes += inventaire("aad", aad)

        SIGNALS.forEach { s ->
            val owner = when (s.service) {
                BMS_SERVICE -> bms
                AAD_SERVICE -> aad
                else        -> null
            }
            val voies = mutableListOf<String>()
            owner?.let { voies += "sdk=" + lire(it, s.id) }
            voies += "cpm@global=" + lireCpm(s.id, AREA_GLOBAL)
            voies += "@0=" + lireCpm(s.id, 0)
            s.validity?.let { v ->
                val source = owner ?: bms
                voies += "valide(0x${hex(v)})=" + (source?.let { lire(it, v) } ?: lireCpm(v, AREA_GLOBAL))
            }
            lignes += "  ${s.name} 0x${hex(s.id)} : ${voies.joinToString(" · ")}"
        }

        // La puissance de charge n'a pas de propriété dédiée : c'est le produit de la tension et du
        // courant de la batterie. Calculée ici pour que le relevé se lise sans sortir la calculette.
        lignes += puissance(bms)

        // Filet pour les autres firmwares : aucune des propriétés connues ne donne une puissance de
        // charge en kW. On balaie donc toutes les MESURES de la famille BMS (les identifiants en
        // 0x216… sont les flottants) pendant une charge : la bonne s'y trouve nécessairement.
        lignes += balayage(bms)

        val texte = lignes.joinToString("\n")
        lignes.forEach { AppLogger.i(TAG, it) }
        garde(texte)
        return texte
    }

    /**
     * Puissance déduite de la tension et du courant batterie.
     *
     * ⚠️ **82,3 est une sentinelle** de cette famille, pas une valeur : cinq propriétés la rendaient
     * en même temps sur SWI133 (dont la « consommation moyenne » 0x2160f421). Toute mesure qui vaut
     * exactement 82,3 doit être tenue pour non publiée.
     */
    private fun puissance(bms: Any?): String {
        bms ?: return "  puissance calculée : gestionnaire absent"
        val volts = mesure(bms, 0x2160f406)
        val amperes = mesure(bms, 0x2160f407)
        if (volts == null || amperes == null) return "  puissance calculée : tension ou courant illisible"
        val kw = volts * amperes / 1000f
        val sens = if (amperes < 0f) "charge" else "décharge"
        return "  puissance calculée : %.2f V × %.2f A = %.2f kW (%s)".format(volts, amperes, -kw, sens)
    }

    /** Mesure flottante utilisable, ou null : la sentinelle 82,3 ne compte pas pour une valeur. */
    private fun mesure(bms: Any, id: Int): Float? {
        val brut = runCatching {
            bms.javaClass.getMethod("getGlobalProperty", Class::class.java, Int::class.javaPrimitiveType)
                .invoke(bms, java.lang.Float::class.java, id) as? Float
        }.getOrNull() ?: return null
        return brut.takeIf { it != SENTINELLE }
    }

    /** Valeur rendue par les mesures BMS non publiées sur SWI133 (relevé du 2026-09-19). */
    private const val SENTINELLE = 82.3f

    /**
     * Relève toutes les mesures que le gestionnaire BMS déclare porter, en excluant celles déjà
     * nommées plus haut. Une ligne compacte : c'est un filet pour attraper ce qu'on n'a pas su nommer.
     */
    private fun balayage(bms: Any?): String {
        bms ?: return "  balayage BMS : gestionnaire absent"
        val connus = SIGNALS.map { it.id }.toSet()
        return try {
            val configs = bms.javaClass.getMethod("getPropertyList").invoke(bms) as? List<*>
                ?: return "  balayage BMS : liste nulle"
            val mesures = configs.filterNotNull().mapNotNull { cfg ->
                runCatching { cfg.javaClass.getMethod("getPropertyId").invoke(cfg) as? Int }.getOrNull()
            }.filter { it !in connus && typeOf(it) == java.lang.Float::class.java }.sorted()
            if (mesures.isEmpty()) "  balayage BMS : aucune mesure nouvelle"
            else "  balayage BMS : " + mesures.joinToString(" · ") { "0x${hex(it)}=${lire(bms, it)}" }
        } catch (e: Exception) {
            "  balayage BMS : ${court(e)}"
        }
    }

    @Synchronized
    private fun garde(texte: String) {
        historique.addLast(texte)
        while (historique.size > MAX_RELEVES) historique.removeFirst()
    }

    /** Liste des propriétés portées par un gestionnaire, résumée par famille pour rester lisible. */
    private fun inventaire(nom: String, manager: Any?): String {
        manager ?: return "  inventaire $nom : gestionnaire absent"
        return try {
            val configs = manager.javaClass.getMethod("getPropertyList").invoke(manager) as? List<*>
                ?: return "  inventaire $nom : liste nulle"
            val ids = configs.filterNotNull().mapNotNull { cfg ->
                runCatching { cfg.javaClass.getMethod("getPropertyId").invoke(cfg) as? Int }.getOrNull()
            }.sorted()
            if (ids.isEmpty()) return "  inventaire $nom : liste vide (${configs.size} entrées illisibles)"
            "  inventaire $nom : ${ids.size} propriétés → " + ids.joinToString(" ") { "0x" + hex(it) }
        } catch (e: Exception) {
            "  inventaire $nom : ${e.javaClass.simpleName} ${e.message}"
        }
    }

    /**
     * Lecture par un gestionnaire SAIC. Le type se déduit de l'identifiant : les bits 16-23 du
     * numéro de propriété VHAL portent le type, inutile de le deviner.
     */
    private fun lire(manager: Any, id: Int): String = try {
        val valeur = manager.javaClass
            .getMethod("getGlobalProperty", Class::class.java, Int::class.javaPrimitiveType)
            .invoke(manager, typeOf(id), id)
        valeur?.toString() ?: "null"
    } catch (e: Exception) {
        court(e)
    }

    private fun lireCpm(id: Int, area: Int): String {
        val cpm = MG4Hardware.carPropertyManager() ?: return "cpm absent"
        val getter = when (typeOf(id)) {
            java.lang.Float::class.java   -> "getFloatProperty"
            java.lang.Boolean::class.java -> "getBooleanProperty"
            else                          -> "getIntProperty"
        }
        return try {
            cpm.javaClass.getMethod(getter, Int::class.java, Int::class.java)
                .invoke(cpm, id, area)?.toString() ?: "null"
        } catch (e: Exception) {
            court(e)
        }
    }

    /** Type déclaré par le numéro de propriété VHAL (float, entier, booléen). */
    private fun typeOf(id: Int): Class<*> = when (id and 0x00ff0000) {
        0x00600000 -> java.lang.Float::class.java
        0x00200000 -> java.lang.Boolean::class.java
        else       -> java.lang.Integer::class.java
    }

    private fun carManager(nom: String): Any? {
        val car = MG4Hardware.car() ?: return null
        return try {
            car.javaClass.getMethod("getCarManager", String::class.java).invoke(car, nom)
        } catch (e: Exception) {
            AppLogger.d(TAG, "getCarManager($nom) : ${court(e)}")
            null
        }
    }

    private fun present(o: Any?) = if (o != null) "oui" else "non"

    private fun hex(id: Int) = Integer.toHexString(id)

    /** Message d'échec ramassé : le nom de l'exception suffit à distinguer absente de refusée. */
    private fun court(e: Exception): String {
        val cause = (e.cause ?: e)
        return cause.javaClass.simpleName + (cause.message?.let { " ($it)" } ?: "")
    }
}
