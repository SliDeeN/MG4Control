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
 */
class StatsTracker(private val capacityKwh: Float = StatsSettings.DEFAULT_CAPACITY_KWH) {

    sealed class Event {
        data class TripEnded(val trip: Trip) : Event()
        data class ChargeEnded(val session: ChargeSession) : Event()
    }

    private data class TripState(
        val startMs: Long,
        val odometerStart: Int?,
        val energyStart: Float?,
        val socStart: Float?,
        val tempC: Float?,
    )

    private data class ChargeState(
        val startMs: Long,
        val socStart: Float?,
        val type: ChargeType?,
        val tempC: Float?,
        var powerSum: Float = 0f,
        var powerCount: Int = 0,
    )

    private var trip: TripState? = null
    private var charge: ChargeState? = null
    private var last: EnergySnapshot? = null

    val tripInProgress: Boolean get() = trip != null
    val chargeInProgress: Boolean get() = charge != null

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
        if (ready == true && trip == null) {
            trip = TripState(
                startMs = snapshot.timestampMs,
                odometerStart = snapshot.odometerKm,
                energyStart = snapshot.energySinceStartKwh,
                socStart = snapshot.socPercent,
                tempC = snapshot.outsideTempC,
            )
        } else if (ready == false && trip != null) {
            finishTrip(snapshot)?.let { events += Event.TripEnded(it) }
        }

        // ── Charge ──────────────────────────────────────────────────────────
        val charging = snapshot.charging
        val enCours = charge
        if (charging == true) {
            if (enCours == null) {
                charge = ChargeState(
                    startMs = snapshot.timestampMs,
                    socStart = snapshot.socPercent,
                    type = snapshot.chargeType,
                    tempC = snapshot.outsideTempC,
                )
            } else {
                // La puissance n'est relevée que si l'écran est réveillé : on garde ce qu'on a vu.
                snapshot.powerKw?.takeIf { it > 0f }?.let {
                    enCours.powerSum += it
                    enCours.powerCount++
                }
            }
        } else if (charging == false && enCours != null) {
            events += Event.ChargeEnded(finishCharge(enCours, snapshot))
            charge = null
        }

        last = snapshot
        return events
    }

    /**
     * Fin de trajet. Rend null pour un trajet sans distance ni énergie : mettre le contact pour
     * régler la climatisation n'est pas un trajet, et remplirait la liste de lignes vides.
     */
    private fun finishTrip(end: EnergySnapshot): Trip? {
        val state = trip ?: return null
        trip = null
        val reference = last ?: end     // le dernier instantané du trajet, pas celui d'après
        val distance = diffKm(state.odometerStart, reference.odometerKm)
        val energy = counterDelta(state.energyStart, reference.energySinceStartKwh) ?: 0f
        if (distance <= 0 && energy <= 0f) return null
        return Trip(
            startMs = state.startMs,
            endMs = reference.timestampMs,
            distanceKm = distance,
            energyKwh = energy.roundTenth(),
            climateKwh = reference.climateSinceStartKwh,
            accessoriesKwh = reference.accessoriesSinceStartKwh,
            regenKwh = reference.regenSinceStartKwh,
            socStart = state.socStart,
            socEnd = reference.socPercent,
            outsideTempC = state.tempC ?: reference.outsideTempC,
        )
    }

    private fun finishCharge(state: ChargeState, end: EnergySnapshot): ChargeSession {
        val socEnd = end.socPercent ?: last?.socPercent
        val delta = if (state.socStart != null && socEnd != null) socEnd - state.socStart else null
        return ChargeSession(
            startMs = state.startMs,
            endMs = end.timestampMs,
            type = state.type ?: end.chargeType,
            socStart = state.socStart,
            socEnd = socEnd,
            energyKwh = delta?.takeIf { it > 0f }?.let { (it / 100f * capacityKwh).roundTenth() },
            measuredPowerKw = if (state.powerCount > 0)
                (state.powerSum / state.powerCount).roundTenth() else null,
            outsideTempC = state.tempC ?: end.outsideTempC,
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
}
