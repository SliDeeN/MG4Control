package com.mg4.control.model

/**
 * Machine à états qui transforme une suite d'instantanés en trajets et en sessions de charge.
 *
 * Pure et sans Android : c'est elle qui porte les règles délicates, donc c'est elle qu'on teste.
 *
 * **Bornes d'un trajet** : le contact. Les compteurs d'énergie du véhicule se remettent à zéro au
 * contact (vérifié le 2026-09-19), les bornes du trajet et celles du compteur coïncident donc.
 *
 * **Énergie d'un trajet** : différence du compteur entre début et fin, SAUF si la valeur finale est
 * inférieure à l'initiale — signe qu'il a été remis à zéro en cours de route, auquel cas la valeur
 * finale est le total. Cette règle marche que l'application ait démarré avant ou pendant le trajet.
 *
 * **Énergie d'une charge** : différence de pourcentage × capacité utile. C'est la seule méthode qui
 * survit à un boîtier qui s'endort pendant la nuit ; la puissance mesurée, elle, n'existe que si
 * l'application a pu relever quelque chose.
 *
 * **Distance d'un trajet** : l'odomètre donne le kilomètre entier, et c'est tout ce que le
 * véhicule publie (les trois autres odomètres du firmware ont été sondés le 2026-09-20, tous à
 * zéro). La distance fine vient donc de l'intégration de la vitesse par la méthode des trapèzes ;
 * l'odomètre reste le garde-fou qui détecte une intégration partielle.
 *
 * **Survie aux coupures** : l'état courant est exposé par [pendingState] pour être enregistré après
 * chaque relevé, et [recover] rouvre ce qui restait en cours au démarrage suivant. Sans ça, tout
 * trajet dont la fin coïncide avec l'extinction du boîtier — c'est-à-dire la plupart — serait perdu.
 */
class StatsTracker(private val capacityKwh: Float = StatsSettings.DEFAULT_CAPACITY_KWH) {

    sealed class Event {
        data class TripEnded(val trip: Trip) : Event()
        data class ChargeEnded(val session: ChargeSession) : Event()
    }

    private var trip: PendingTrip? = null
    private var charge: PendingCharge? = null

    val tripInProgress: Boolean get() = trip != null
    val chargeInProgress: Boolean get() = charge != null

    /** État à enregistrer après chaque relevé. */
    fun pendingState(): PendingState = PendingState(trip, charge)

    /**
     * Reprend ce qui restait ouvert au démarrage précédent et le clôt avec son dernier relevé
     * connu. Appelé une fois, au lancement du collecteur, avant tout nouveau relevé.
     *
     * Le trajet est daté de son dernier relevé, pas de maintenant : la voiture a pu rester éteinte
     * toute la nuit, et lui attribuer le temps écoulé fausserait sa durée et sa vitesse moyenne.
     */
    fun recover(state: PendingState?): List<Event> {
        state ?: return emptyList()
        val events = mutableListOf<Event>()
        state.trip?.let { p ->
            trip = p
            finishTrip()?.let { events += Event.TripEnded(it) }
        }
        state.charge?.let { p ->
            charge = p
            events += Event.ChargeEnded(finishCharge(p, p.lastMs, p.socLast))
            charge = null
        }
        return events
    }

    /**
     * Avale un instantané et rend ce qui vient de se terminer.
     *
     * [ready] : contact mis. Null quand l'état n'a pas pu être lu — dans ce cas on ne conclut rien,
     * ni début ni fin : une lecture manquée ne doit pas couper un trajet en deux.
     */
    fun onSnapshot(snapshot: EnergySnapshot, ready: Boolean?): List<Event> {
        if (!snapshot.usable) return emptyList()
        val events = mutableListOf<Event>()

        // ── Trajet ──────────────────────────────────────────────────────────
        val enCours = trip
        if (ready == true) {
            // Calculé avant la recopie : il dit à la fois combien ajouter et si l'intervalle
            // comptait, ce qui n'est pas la même chose qu'ajouter zéro.
            val pas = enCours?.let { integrationStep(it, snapshot) }
            trip = if (enCours == null) PendingTrip(
                startMs = snapshot.timestampMs,
                odometerStart = snapshot.odometerKm,
                energyStart = snapshot.energySinceStartKwh,
                socStart = snapshot.socPercent,
                tempC = snapshot.outsideTempC,
                lastMs = snapshot.timestampMs,
                odometerLast = snapshot.odometerKm,
                energyLast = snapshot.energySinceStartKwh,
                socLast = snapshot.socPercent,
                climateLast = snapshot.climateSinceStartKwh,
                accessoriesLast = snapshot.accessoriesSinceStartKwh,
                regenLast = snapshot.regenSinceStartKwh,
                lastSpeedKmh = snapshot.speedKmh,
            ) else enCours.copy(
                lastMs = snapshot.timestampMs,
                odometerLast = snapshot.odometerKm ?: enCours.odometerLast,
                energyLast = snapshot.energySinceStartKwh ?: enCours.energyLast,
                socLast = snapshot.socPercent ?: enCours.socLast,
                climateLast = snapshot.climateSinceStartKwh ?: enCours.climateLast,
                accessoriesLast = snapshot.accessoriesSinceStartKwh ?: enCours.accessoriesLast,
                regenLast = snapshot.regenSinceStartKwh ?: enCours.regenLast,
                integratedKm = enCours.integratedKm + (pas ?: 0f),
                integrated = enCours.integrated || pas != null,
                // Une vitesse manquée ne réinitialise pas le trapèze : le prochain intervalle
                // repart de la dernière vitesse connue, ce qui sous-estime sans jamais inventer.
                lastSpeedKmh = snapshot.speedKmh ?: enCours.lastSpeedKmh,
            )
        } else if (ready == false && enCours != null) {
            // Entre le dernier relevé sous contact et celui qui le coupe, la voiture roulait
            // encore : sans ce dernier trapèze, chaque trajet perdrait sa fin. Seules la distance
            // et l'heure de fin sont reprises — les compteurs d'énergie restent ceux du dernier
            // relevé sous contact, dont on sait qu'ils étaient valides.
            val fin = integrationStep(enCours, snapshot)
            if (fin != null) trip = enCours.copy(
                lastMs = snapshot.timestampMs,
                integratedKm = enCours.integratedKm + fin,
                integrated = true,
            )
            finishTrip()?.let { events += Event.TripEnded(it) }
        }

        // ── Charge ──────────────────────────────────────────────────────────
        val chargeEnCours = charge
        if (snapshot.charging == true) {
            charge = if (chargeEnCours == null) PendingCharge(
                startMs = snapshot.timestampMs,
                socStart = snapshot.socPercent,
                type = snapshot.chargeType,
                tempC = snapshot.outsideTempC,
                powerSum = 0f,
                powerCount = 0,
                lastMs = snapshot.timestampMs,
                socLast = snapshot.socPercent,
            ) else chargeEnCours.copy(
                // La puissance n'est relevée que si l'écran est réveillé : on garde ce qu'on voit.
                powerSum = chargeEnCours.powerSum + (snapshot.powerKw?.takeIf { it > 0f } ?: 0f),
                powerCount = chargeEnCours.powerCount + if ((snapshot.powerKw ?: 0f) > 0f) 1 else 0,
                lastMs = snapshot.timestampMs,
                socLast = snapshot.socPercent ?: chargeEnCours.socLast,
                type = chargeEnCours.type ?: snapshot.chargeType,
            )
        } else if (snapshot.charging == false && chargeEnCours != null) {
            events += Event.ChargeEnded(
                finishCharge(chargeEnCours, snapshot.timestampMs, snapshot.socPercent ?: chargeEnCours.socLast)
            )
            charge = null
        }

        return events
    }

    /**
     * Distance parcourue depuis le relevé précédent, par la méthode des trapèzes.
     *
     * Rend null quand l'intervalle n'est pas mesurable : vitesse inconnue d'un côté ou de l'autre,
     * ou trou de plus de [MAX_SAMPLE_GAP_MS] — passé ce délai, prolonger la dernière vitesse connue
     * ne serait plus une mesure mais une supposition. L'intervalle est alors simplement perdu, et
     * la comparaison avec l'odomètre se chargera de dire que le total n'est plus fiable.
     */
    private fun integrationStep(p: PendingTrip, s: EnergySnapshot): Float? {
        val avant = p.lastSpeedKmh ?: return null
        val apres = s.speedKmh ?: return null
        val dt = s.timestampMs - p.lastMs
        if (dt <= 0L || dt > MAX_SAMPLE_GAP_MS) return null
        return (avant + apres) / 2f * (dt / 3_600_000f)
    }

    /**
     * Fin de trajet, à partir du dernier relevé observé. Rend null pour un trajet sans distance ni
     * énergie : mettre le contact pour régler la climatisation n'est pas un trajet, et remplirait
     * la liste de lignes vides.
     */
    private fun finishTrip(): Trip? {
        val p = trip ?: return null
        trip = null
        val distance = diffKm(p.odometerStart, p.odometerLast)
        val energy = counterDelta(p.energyStart, p.energyLast) ?: 0f
        // La distance intégrée compte dans ce test : un trajet de six cents mètres est un vrai
        // trajet, même si l'odomètre n'a pas changé de kilomètre.
        val integre = if (p.integrated) p.integratedKm.roundTenth() else null
        if (distance <= 0 && energy <= 0f && (integre ?: 0f) <= 0f) return null
        return Trip(
            startMs = p.startMs,
            endMs = p.lastMs,
            distanceKm = distance,
            energyKwh = energy.roundTenth(),
            climateKwh = p.climateLast,
            accessoriesKwh = p.accessoriesLast,
            regenKwh = p.regenLast,
            socStart = p.socStart,
            socEnd = p.socLast,
            outsideTempC = p.tempC,
            integratedKm = integre,
        )
    }

    private fun finishCharge(p: PendingCharge, endMs: Long, socEnd: Float?): ChargeSession {
        val delta = if (p.socStart != null && socEnd != null) socEnd - p.socStart else null
        return ChargeSession(
            startMs = p.startMs,
            endMs = endMs,
            type = p.type,
            socStart = p.socStart,
            socEnd = socEnd,
            energyKwh = delta?.takeIf { it > 0f }?.let { (it / 100f * capacityKwh).roundTenth() },
            measuredPowerKw = if (p.powerCount > 0) (p.powerSum / p.powerCount).roundTenth() else null,
            outsideTempC = p.tempC,
        )
    }

    /** Différence d'odomètre, jamais négative : un compteur qui recule est une lecture ratée. */
    private fun diffKm(start: Int?, end: Int?): Int {
        if (start == null || end == null) return 0
        return (end - start).coerceAtLeast(0)
    }

    /**
     * Différence d'un compteur qui se remet à zéro. Une valeur finale plus petite que l'initiale
     * signale une remise à zéro : le total est alors la valeur finale elle-même.
     */
    private fun counterDelta(start: Float?, end: Float?): Float? {
        end ?: return null
        if (start == null || end < start) return end
        return end - start
    }

    companion object {
        /**
         * Trou maximal accepté entre deux relevés pour intégrer la vitesse. Le collecteur relève
         * toutes les dix secondes en roulant : une minute laisse passer cinq relevés manqués, bien
         * au-delà d'un simple retard de l'ordonnanceur, mais sans couvrir une mise en veille.
         */
        const val MAX_SAMPLE_GAP_MS = 60_000L
    }
}
