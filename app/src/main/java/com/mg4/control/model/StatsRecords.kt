package com.mg4.control.model

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Ce que le véhicule publie, et avec quelle finesse. Toute l'incertitude des ratios vient de là.
 *
 * Les compteurs d'énergie sortent au dixième de kWh : sur un trajet de deux kilomètres, ce seul pas
 * vaut déjà plusieurs kWh/100 km. Annoncer « 12,5 » quand la vraie valeur peut être 9,4 serait une
 * précision inventée — d'où [APPROXIMATE_ABOVE], au-delà duquel l'écran passe au signe « ≈ » et
 * laisse tomber la décimale.
 */
object Resolution {
    /** Demi-pas des compteurs d'énergie, publiés au dixième de kWh. */
    const val ENERGY_KWH = 0.05f

    /** Demi-pas de la distance intégrée, arrondie au dixième de kilomètre. */
    const val DISTANCE_PRECISE_KM = 0.05f

    /** Demi-pas de l'odomètre du véhicule, entier — dix fois plus grossier. */
    const val DISTANCE_ODOMETER_KM = 0.5f

    /** Incertitude relative au-delà de laquelle une consommation est annoncée comme approchée. */
    const val APPROXIMATE_ABOVE = 0.10f

    /**
     * Incertitude relative retenue pour une énergie intégrée (tension × courant, un relevé par
     * seconde). Elle n'a pas le pas d'un dixième de kWh des compteurs ; son erreur est
     * proportionnelle. Valeur prudente, tirée du premier trajet de validation (2026-10-06 : 1,27
     * kWh intégrés pour 1,20 aux compteurs et 1,24 d'après la batterie) — à resserrer ou relâcher
     * quand d'autres trajets auront parlé.
     */
    const val INTEGRATED_ENERGY_RELATIVE = 0.07f
}

/**
 * Un trajet enregistré : ce qui reste d'une période de conduite une fois la voiture éteinte.
 *
 * Les durées sont en millisecondes d'horloge murale (elles servent à dater, pas à mesurer une
 * précision fine), l'énergie en kWh et la distance en kilomètres.
 */
data class Trip(
    val startMs: Long,
    val endMs: Long,
    /**
     * Distance lue sur l'odomètre du véhicule, **au kilomètre entier** — c'est tout ce qu'il
     * publie. Elle sert de contrôle à [integratedKm], plus fine mais susceptible de dériver.
     */
    val distanceKm: Int,
    /**
     * Énergie **brute** relevée sur le compteur du véhicule : la régénération n'en est PAS déduite.
     *
     * Vérifié le 2026-09-20 en comparant avec l'écran d'origine — la voiture affichait 14,1
     * kWh/100 km là où ce compteur donnait 17,1 sur la même distance, l'écart valant exactement la
     * régénération. C'est donc [netEnergyKwh] qu'il faut montrer et facturer.
     */
    val energyKwh: Float,
    val climateKwh: Float?,
    val accessoriesKwh: Float?,
    val regenKwh: Float?,
    val socStart: Float?,
    val socEnd: Float?,
    val outsideTempC: Float?,
    /**
     * Distance obtenue en intégrant la vitesse, au dixième de kilomètre.
     *
     * L'odomètre du véhicule est entier et aucune autre source n'a répondu (sondés le 2026-09-20 :
     * odomètre AOSP, odomètre du combiné, carte de trajet — tous à zéro). Null sur les trajets
     * enregistrés avant cette mesure, et null si la vitesse n'a jamais pu être relevée.
     */
    val integratedKm: Float? = null,
    /**
     * Vrai quand [energyKwh] et [regenKwh] viennent de l'intégration de la puissance batterie et
     * non des compteurs du véhicule — le cas des firmwares qui ne les alimentent pas (issue #117).
     * Les postes [climateKwh] et [accessoriesKwh] sont alors inconnus.
     */
    val energyIntegrated: Boolean = false,
    /**
     * « Climatisation et autres » d'après le compteur de l'écran d'origine, au **kWh entier** :
     * à présenter comme un ordre de grandeur. Renseigné seulement quand [energyIntegrated].
     */
    val auxiliaryKwh: Float? = null,
) {
    val durationMs: Long get() = max(0L, endMs - startMs)

    /**
     * Faux quand l'énergie du trajet n'a pas été mesurée : un brut resté à zéro sur un kilomètre
     * et plus. C'est la signature des compteurs muets (issue #117), dont les trajets enregistrés
     * avant que l'intégration prenne le relais restent dans l'historique. Sous le kilomètre, zéro
     * est au contraire la bonne lecture d'un compteur au dixième de kWh.
     */
    val energyKnown: Boolean
        get() = energyKwh > 0f || distance < MIN_DISTANCE_PRECISE_KM

    /**
     * Distance retenue pour l'affichage et les ratios.
     *
     * L'intégration l'emporte quand elle existe ET qu'elle concorde avec l'odomètre : elle est dix
     * fois plus fine. En cas de désaccord franc — plus de 2 km ou 15 % —, on retombe sur
     * l'odomètre : lui ne dérive jamais, et un tel écart trahirait des relevés manqués plutôt
     * qu'une meilleure mesure.
     */
    val distance: Float
        get() {
            val integre = integratedKm ?: return distanceKm.toFloat()
            if (distanceKm <= 0) return integre
            val ecart = abs(integre - distanceKm)
            return if (ecart <= max(2f, distanceKm * 0.15f)) integre else distanceKm.toFloat()
        }

    /** Vrai quand la distance affichée vient de l'intégration, donc connue au dixième. */
    val distancePrecise: Boolean get() = integratedKm != null && distance == integratedKm

    /**
     * Énergie réellement sortie de la batterie : brute moins ce que la régénération a rendu.
     *
     * C'est la convention de tout le monde — la voiture elle-même, Tesla, les planificateurs — et
     * c'est la seule qui ait un sens pour le coût : on ne recharge que ce qu'on a vraiment dépensé.
     */
    val netEnergyKwh: Float
        get() = (energyKwh - (regenKwh ?: 0f)).coerceAtLeast(0f).roundTenth()

    /**
     * Consommation moyenne en kWh/100 km, **null sous la distance plancher** — et null quand
     * l'énergie est inconnue ([energyKnown]) : « 0 kWh/100 km » n'a jamais été une mesure.
     *
     * Le plancher dépend de la source. Au kilomètre entier, 2 km sont connus à ±50 % près et le
     * ratio n'aurait aucun sens ; au dixième, un seul kilomètre donne déjà un chiffre honnête.
     * Mieux vaut ne rien afficher qu'un chiffre faux — l'énergie et la distance, elles, restent
     * justes et restent affichées.
     */
    val consumptionPer100: Float?
        get() = if (energyKnown && distance >= ratioFloor) netEnergyKwh * 100f / distance else null

    /**
     * Incertitude relative de [consumptionPer100], due à la seule résolution des compteurs.
     *
     * Les deux termes s'additionnent au lieu de se compenser en quadrature : sur deux grandeurs
     * seulement, une estimation prudente vaut mieux qu'une élégante. La part énergie double quand
     * la régénération est connue, puisque le net est la différence de deux valeurs arrondies.
     *
     * Null quand elle n'a pas de sens : pas de ratio, ou une énergie nette nulle — ce dernier cas
     * étant justement celui où l'on ne garantit rien du tout.
     */
    val consumptionUncertainty: Float?
        get() {
            consumptionPer100 ?: return null
            if (netEnergyKwh <= 0f || distance <= 0f) return null
            val energie = Resolution.ENERGY_KWH * (if (regenKwh != null) 2f else 1f)
            val kilometres =
                if (distancePrecise) Resolution.DISTANCE_PRECISE_KM
                else Resolution.DISTANCE_ODOMETER_KM
            val partEnergie =
                if (energyIntegrated) Resolution.INTEGRATED_ENERGY_RELATIVE else energie / netEnergyKwh
            return partEnergie + kilometres / distance
        }

    /** Vrai quand la consommation doit être annoncée comme approchée. */
    val consumptionApproximate: Boolean
        get() {
            consumptionPer100 ?: return false
            // Incertitude indéterminée (énergie nette nulle) : c'est le pire des cas, pas le
            // meilleur — on ne va pas l'afficher comme une mesure.
            val u = consumptionUncertainty ?: return true
            return u > Resolution.APPROXIMATE_ABOVE
        }

    /**
     * Énergie du moteur : ce qui reste du total une fois la climatisation et les accessoires
     * retirés. Le véhicule ne la publie pas — il ne donne que le total et ses postes annexes.
     *
     * Nommée « moteur » et non « traction » : la MG4 est une propulsion, et la XPower une quatre
     * roues motrices. Null tant qu'aucun poste n'est connu, sinon on présenterait le total comme
     * une mesure séparée qui n'existe pas.
     *
     * Sur un trajet à l'énergie intégrée, le seul poste connu est [auxiliaryKwh] et le calcul est
     * celui du camembert de l'écran d'origine : « Driving power » = total − « AC and others ». Ce
     * compteur tronque au kWh, le vrai poste vaut donc jusqu'à 1 kWh de plus : le reste est rendu
     * au kWh entier **par défaut**, seule valeur ronde qui tombe toujours dans l'intervalle
     * possible. À présenter comme un ordre de grandeur, au même titre que [auxiliaryKwh].
     */
    val motorKwh: Float?
        get() {
            if (energyIntegrated) {
                val autres = auxiliaryKwh ?: return null
                return floor(energyKwh - autres).coerceAtLeast(0f)
            }
            if (climateKwh == null && accessoriesKwh == null) return null
            // Sur le BRUT : moteur + climatisation + accessoires − régénération = énergie nette.
            val reste = energyKwh - (climateKwh ?: 0f) - (accessoriesKwh ?: 0f)
            return reste.coerceAtLeast(0f).roundTenth()
        }

    /** Vitesse moyenne en km/h, même réserve de précision que la consommation. */
    val averageSpeedKmh: Float?
        get() {
            if (distance < ratioFloor || durationMs <= 0L) return null
            return distance * 3_600_000f / durationMs
        }

    private val ratioFloor: Float
        get() = if (distancePrecise) MIN_DISTANCE_PRECISE_KM else MIN_DISTANCE_ODOMETER_KM

    companion object {
        /** Plancher des ratios quand la distance vient de l'odomètre, au kilomètre entier. */
        const val MIN_DISTANCE_ODOMETER_KM = 5f

        /** Idem quand elle vient de l'intégration de la vitesse, au dixième. */
        const val MIN_DISTANCE_PRECISE_KM = 1f
    }
}

/**
 * Une session de charge.
 *
 * [energyKwh] vient de la différence de pourcentage multipliée par la capacité utile réglée : c'est
 * la seule méthode qui fonctionne même si le boîtier s'est endormi pendant la nuit. [measuredPowerKw]
 * n'existe que si l'application a pu relever la puissance en direct, donc si elle était éveillée ;
 * sa présence est justement ce qui dit à l'écran s'il peut annoncer une puissance mesurée.
 */
data class ChargeSession(
    val startMs: Long,
    val endMs: Long,
    val type: ChargeType?,
    val socStart: Float?,
    val socEnd: Float?,
    val energyKwh: Float?,
    /** Moyenne des puissances relevées pendant la charge, null si rien n'a pu être relevé. */
    val measuredPowerKw: Float?,
    val outsideTempC: Float?,
    /** Prix du kWh corrigé à la main pour cette session ; null = tarif par défaut du type. */
    val tariffOverride: Float? = null,
    /**
     * Vrai quand l'application n'a pas vu toute la charge — typiquement une charge de nuit, boîtier
     * coupé, déduite après coup de la remontée du pourcentage.
     *
     * Ce qui subsiste alors est l'**énergie** (différence de pourcentage × capacité, méthode
     * recoupée avec la puissance mesurée le 2026-09-20). Ce qui disparaît est la **durée** — les
     * deux dates encadrent la charge sans la mesurer — et donc toute puissance.
     */
    val reconstructed: Boolean = false,
    /**
     * Horaires réels de la charge, saisis à la main quand l'utilisateur les connaît.
     *
     * [startMs] et [endMs] restent la **fenêtre relevée** : ils identifient la session et ne
     * bougent jamais. Ces deux-là sont ce que l'utilisateur affirme, et leur seule présence suffit
     * à rendre la durée — donc la puissance moyenne — de nouveau calculable.
     */
    val userStartMs: Long? = null,
    val userEndMs: Long? = null,
) {
    /** Bornes à afficher : celles de l'utilisateur s'il en a donné, sinon la fenêtre relevée. */
    val displayStartMs: Long get() = userStartMs ?: startMs
    val displayEndMs: Long get() = userEndMs ?: endMs

    /** Vrai quand les deux horaires sont connus : la durée est alors une vraie durée. */
    val timesKnown: Boolean get() = userStartMs != null && userEndMs != null

    val durationMs: Long get() = max(0L, displayEndMs - displayStartMs)

    /**
     * Puissance à afficher : celle qui a été mesurée si elle existe, sinon énergie ÷ durée.
     * Le second cas est une déduction, pas une mesure — l'écran doit le dire.
     *
     * Rien du tout pour une charge reconstituée tant que ses horaires sont inconnus : diviser
     * l'énergie par l'intervalle entre deux réveils donnerait une puissance ridiculement basse, et
     * surtout fausse. Dès que l'utilisateur donne les deux heures, le calcul redevient légitime —
     * et il reste une déduction, que l'écran annonce comme telle.
     */
    val powerKw: Float?
        get() {
            if (reconstructed) {
                if (!timesKnown) return null
                // La moyenne relevée ne couvrirait qu'une poignée de minutes de la fin : la
                // présenter comme la moyenne de la session serait trompeur.
                val kwh = energyKwh ?: return null
                val heures = durationMs / 3_600_000f
                return if (heures <= 0.05f) null else kwh / heures
            }
            return measuredPowerKw ?: run {
                val heures = durationMs / 3_600_000f
                val kwh = energyKwh ?: return null
                if (heures <= 0.05f) null else kwh / heures
            }
        }

    /**
     * La même session, son énergie recalculée pour une autre batterie. Sert quand l'utilisateur
     * corrige sa batterie : sans cela l'historique garderait l'erreur — 20 % de trop pour une
     * 51 kWh laissée sur le réglage de la 64. Le calcul est exact, les deux pourcentages étant
     * gardés ; sans eux, l'énergie enregistrée reste la seule information et n'est pas touchée.
     */
    fun withCapacity(capacityKwh: Float): ChargeSession {
        val debut = socStart ?: return this
        val fin = socEnd ?: return this
        val monte = fin - debut
        return if (monte <= 0f) this else copy(energyKwh = (monte / 100f * capacityKwh).roundTenth())
    }

    /** Prix payé pour cette session, selon le tarif corrigé ou celui de son type de prise. */
    fun cost(settings: StatsSettings): Float? {
        val kwh = energyKwh ?: return null
        return kwh * (tariffOverride ?: settings.priceFor(type))
    }
}

/** Arrondi d'affichage : un dixième, comme la voiture publie ses compteurs. */
fun Float.roundTenth(): Float = (this * 10f).roundToInt() / 10f
