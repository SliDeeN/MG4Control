package com.mg4.control.hardware

import com.mg4.control.debug.AppLogger
import com.mg4.control.util.FirmwareInfo

/**
 * Sonde pneus, en **lecture seule** (2026-11-20) : la voiture envoie-t-elle la pression, la
 * température et l'état de chaque pneu à l'écran central, et dans quelle unité ?
 *
 * Codes relevés dans les 5 firmwares extraits (`CarSensorManager` / `YFVehicleProperty`,
 * identiques partout), tous dans la liste autorisée de CarService (SWI131/133/165/68, permission
 * `CAR_ENGINE_DETAILED`, comme le kilométrage 0x21401566 qu'on lit déjà). Aucune application de
 * l'écran central ne lit la pression : seul le combiné l'affiche. La voix SAIC de SWI133 lit l'état
 * par `CarSensorManager.getLatestSensorEvent(id).intValues[0]` : 0 ou 1 normal, au-dessus alerte
 * (6 à part). Les `getTirePressure` de carapi A9 renvoient toujours 0 : inutile de les interroger.
 *
 * Chaque valeur est lue par trois voies, pour voir laquelle répond : CarPropertyManager en zone
 * globale, en zone 0, et le gestionnaire de capteurs. Plus la pression Android standard
 * (`TIRE_PRESSURE`, flottant par roue). Rien n'est interprété : unités et échelle restent à
 * établir en comparant au combiné, pneus froids puis chauds.
 */
object TyreProbe {

    const val TAG = "MG4_TYRE"

    /** Les quatre roues. [zone] : la zone `VehicleAreaWheel` de la pression standard. */
    enum class Roue(val pression: Int, val etat: Int, val temperature: Int, val zone: Int) {
        AVANT_GAUCHE(0x21401553, 0x21401557, 0x2140155b, 0x1),
        AVANT_DROITE(0x21401554, 0x21401558, 0x2140155c, 0x2),
        ARRIERE_GAUCHE(0x21401555, 0x21401559, 0x2140155d, 0x4),
        ARRIERE_DROITE(0x21401556, 0x2140155a, 0x2140155e, 0x8),
    }

    /** Unité de pression choisie pour le combiné (`FICM_TYRE_PRESS_UNIT`). */
    const val PROP_UNITE = 0x21407b49

    /** Pression Android standard, flottant par zone de roue (`TIRE_PRESSURE`). */
    const val PROP_STANDARD = 0x17600309

    private const val AREA_GLOBAL = 0x1000000

    /** Une lecture : le nombre s'il y en a un, sinon le motif d'échec en clair. */
    data class Lu(val nombre: Number?, val texte: String) {
        companion object {
            fun de(n: Number?) = Lu(n, n?.toString() ?: "null")
            fun echec(motif: String) = Lu(null, motif)
        }
    }

    /** Une valeur lue par ses trois voies, dans l'ordre de préférence. */
    data class Voies(val global: Lu, val zero: Lu, val capteur: Lu) {
        /** Le premier nombre rendu, voie globale d'abord. */
        val valeur: Number? get() = premier(listOf(global, zero, capteur))

        fun detail() = "cpm=${global.texte} · @0=${zero.texte} · capteur=${capteur.texte}"
    }

    data class RoueLue(
        val roue: Roue,
        val pression: Voies,
        val temperature: Voies,
        val etat: Voies,
        val standard: Lu,
    )

    data class Releve(val roues: List<RoueLue>, val unite: Voies)

    /** Premier nombre trouvé : la voie qui répond, quelle qu'elle soit. */
    fun premier(voies: List<Lu>): Number? = voies.firstNotNullOfOrNull { it.nombre }

    /**
     * Les derniers relevés, pour que le rapport de diagnostic les porte tous (froid, chaud) — même
     * principe que la sonde énergie.
     */
    private val historique = ArrayDeque<String>()
    private const val MAX_RELEVES = 6

    val reports: List<String>
        @Synchronized get() = historique.toList()

    /** Relève tout. Hors du fil principal : une quarantaine d'allers-retours binder. */
    fun lire(): Releve {
        val capteurs = sensorManager()
        fun voies(id: Int) = Voies(
            global = lireCpm(id, AREA_GLOBAL),
            zero = lireCpm(id, 0),
            capteur = lireCapteur(capteurs, id),
        )
        val roues = Roue.values().map { r ->
            RoueLue(
                roue = r,
                pression = voies(r.pression),
                temperature = voies(r.temperature),
                etat = voies(r.etat),
                standard = lireStandard(r.zone),
            )
        }
        return Releve(roues, voies(PROP_UNITE))
    }

    /** Une roue pour la fenêtre Pression des pneus : pression (kPa), température (°C), code d'état. */
    data class Pneu(val pression: Int?, val temperature: Int?, val etat: Int?)

    /** Les quatre pneus et l'unité du combiné, lus par la seule voie confirmée. */
    data class Pneus(val roues: Map<Roue, Pneu>, val uniteCombine: Int?)

    /**
     * Lecture légère pour la fenêtre Pression des pneus : CarPropertyManager en zone globale, la
     * voie qui répond (relevé SWI133 du 2026-11-20 : les trois voies rendent la même chose).
     */
    fun lirePneus(): Pneus = Pneus(
        roues = Roue.values().associateWith { r ->
            Pneu(lireCpm(r.pression, AREA_GLOBAL).nombre?.toInt(),
                lireCpm(r.temperature, AREA_GLOBAL).nombre?.toInt(),
                lireCpm(r.etat, AREA_GLOBAL).nombre?.toInt())
        },
        uniteCombine = lireCpm(PROP_UNITE, AREA_GLOBAL).nombre?.toInt(),
    )

    /** Durée pendant laquelle un relevé sert à tous (bulles, écran conducteur, automatisations). */
    private const val CACHE_MS = 2_000L
    private var cache: Pair<Long, Pneus>? = null

    /** Relevé de moins de [CACHE_MS] partagé, sinon un nouveau. Fil de l'appelant (binder). */
    @Synchronized
    fun pneusRecents(): Pneus {
        val maintenant = android.os.SystemClock.elapsedRealtime()
        cache?.let { (quand, p) -> if (maintenant - quand < CACHE_MS) return p }
        return lirePneus().also { cache = maintenant to it }
    }

    /** Dernier relevé connu, sans lire la voiture (unité du combiné pour l'affichage). */
    val dernierConnu: Pneus? @Synchronized get() = cache?.second

    /** Journalise et garde pour le Diagnostic un relevé (pris ici par défaut). Renvoie son texte. */
    fun run(origin: String, r: Releve = lire()): String {
        val texte = texte(r, origin)
        texte.lines().forEach { AppLogger.i(TAG, it) }
        garde(texte)
        return texte
    }

    /** Le relevé en clair, une ligne par mesure. Noms techniques : pas de traduction. */
    fun texte(r: Releve, origin: String): String = buildString {
        append("══ Sonde pneus [$origin] · ${FirmwareInfo.getGeneration()} ══")
        append("\n  gestionnaires : cpm=${present(MG4Hardware.carPropertyManager())} · sensor=${present(sensorManager())}")
        append("\n  unité combiné 0x${hex(PROP_UNITE)} : ${r.unite.detail()}")
        r.roues.forEach { w ->
            val n = w.roue.name
            append("\n  $n pression 0x${hex(w.roue.pression)} : ${w.pression.detail()}")
            append("\n  $n température 0x${hex(w.roue.temperature)} : ${w.temperature.detail()}")
            append("\n  $n état 0x${hex(w.roue.etat)} : ${w.etat.detail()}")
            append("\n  $n standard 0x${hex(PROP_STANDARD)}@${w.roue.zone} : ${w.standard.texte}")
        }
    }

    /** Ce qui a bougé entre deux relevés, pour le journal ; vide si rien. */
    fun changements(avant: Releve, apres: Releve): String {
        val morceaux = mutableListOf<String>()
        if (avant.unite.valeur != apres.unite.valeur) morceaux += "unité ${avant.unite.valeur}→${apres.unite.valeur}"
        avant.roues.zip(apres.roues).forEach { (a, b) ->
            val n = b.roue.name
            if (a.pression.valeur != b.pression.valeur) morceaux += "$n pression ${a.pression.valeur}→${b.pression.valeur}"
            if (a.temperature.valeur != b.temperature.valeur) morceaux += "$n temp. ${a.temperature.valeur}→${b.temperature.valeur}"
            if (a.etat.valeur != b.etat.valeur) morceaux += "$n état ${a.etat.valeur}→${b.etat.valeur}"
            if (a.standard.nombre != b.standard.nombre) morceaux += "$n standard ${a.standard.nombre}→${b.standard.nombre}"
        }
        return morceaux.joinToString(" · ")
    }

    @Synchronized
    private fun garde(texte: String) {
        historique.addLast(texte)
        while (historique.size > MAX_RELEVES) historique.removeFirst()
    }

    private fun lireCpm(id: Int, area: Int): Lu {
        val cpm = MG4Hardware.carPropertyManager() ?: return Lu.echec("cpm absent")
        return try {
            Lu.de(cpm.javaClass.getMethod("getIntProperty", Int::class.java, Int::class.java)
                .invoke(cpm, id, area) as? Int)
        } catch (e: Exception) {
            Lu.echec(court(e))
        }
    }

    private fun lireStandard(zone: Int): Lu {
        val cpm = MG4Hardware.carPropertyManager() ?: return Lu.echec("cpm absent")
        return try {
            Lu.de(cpm.javaClass.getMethod("getFloatProperty", Int::class.java, Int::class.java)
                .invoke(cpm, PROP_STANDARD, zone) as? Float)
        } catch (e: Exception) {
            Lu.echec(court(e))
        }
    }

    /** Comme la voix SAIC : dernier événement du capteur, premier entier (sinon premier flottant). */
    private fun lireCapteur(capteurs: Any?, id: Int): Lu {
        capteurs ?: return Lu.echec("sensor absent")
        return try {
            val ev = capteurs.javaClass.getMethod("getLatestSensorEvent", Int::class.javaPrimitiveType)
                .invoke(capteurs, id) ?: return Lu.echec("aucun événement")
            val ints = ev.javaClass.getField("intValues").get(ev) as? IntArray
            if (ints != null && ints.isNotEmpty()) return Lu.de(ints[0])
            val floats = ev.javaClass.getField("floatValues").get(ev) as? FloatArray
            if (floats != null && floats.isNotEmpty()) return Lu.de(floats[0])
            Lu.echec("événement vide")
        } catch (e: Exception) {
            Lu.echec(court(e))
        }
    }

    private fun sensorManager(): Any? {
        val car = MG4Hardware.car() ?: return null
        return try {
            car.javaClass.getMethod("getCarManager", String::class.java).invoke(car, "sensor")
        } catch (e: Exception) {
            AppLogger.d(TAG, "getCarManager(sensor) : ${court(e)}")
            null
        }
    }

    private fun present(o: Any?) = if (o != null) "oui" else "non"

    private fun hex(id: Int) = Integer.toHexString(id)

    private fun court(e: Exception): String {
        val cause = e.cause ?: e
        return cause.javaClass.simpleName + (cause.message?.let { " ($it)" } ?: "")
    }
}
