package com.mg4.control.hardware

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.edit
import com.mg4.control.debug.AppLogger
import com.mg4.control.model.PowerWindow
import com.mg4.control.model.WindowCalibration
import com.mg4.control.model.WindowCommand
import com.mg4.control.model.WindowCommand.Direction
import com.mg4.control.model.WindowEstimator
import com.mg4.control.util.FirmwareInfo
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Vitres électriques — V0 de test (onglet Vitres du tableau de bord).
 *
 * Écriture directe de la propriété de chaque vitre via CarPropertyManager, voie commune aux
 * 6 firmwares (voir [PowerWindow]). Tout passe par un fil dédié : la répétition du mode manuel
 * (toutes les 120 ms) ne doit ni bloquer l'écran ni se mélanger aux autres commandes.
 *
 * Pas de verrou de vitesse ([VehicleWriteGate]) : c'est du confort, et les interrupteurs
 * physiques restent utilisables en roulant.
 *
 * Vitres sans capteur : position estimée ([WindowEstimator]) à partir de leur calibration,
 * remise à « inconnue » à chaque démarrage de la voiture.
 *
 * Sondes (tag [TAG]) : liste des propriétés déclarées, lectures par zone, chaque commande avec
 * son résultat, chaque changement de valeur reçu du véhicule avec le délai depuis la dernière
 * commande de l'app, et chaque estimation de position en fin de mouvement.
 */
object PowerWindows {

    const val TAG = "MG4_WIN"

    /** Store partagé avec les Réglages. */
    private const val PREFS_NAME = "mg4_settings"

    private const val AREA_GLOBAL = 0x1000000
    /** AOSP WINDOW_POS / WINDOW_MOVE : déclarées dans les firmwares, sondées pour information. */
    private const val PROP_WINDOW_POS  = 0x13400bc0
    private const val PROP_WINDOW_MOVE = 0x13400bc1
    /** Codes de configuration (octets) lus par VehicleConditionBinder SWI68 : getWindowControl /
     *  getRearWindowAutoConfigCode (« rearWindowAutomaticStatus »). Sondés pour relier finition et auto. */
    private const val PROP_CFG_DRIVER_WINDOW    = 0x21704208
    private const val PROP_CFG_REAR_WINDOW_AUTO = 0x21704267
    /** Changement reçu plus tard que ça après notre dernière commande = interrupteur physique. */
    private const val OWN_COMMAND_WINDOW_MS = 3_000L

    private val worker: Handler by lazy {
        Handler(HandlerThread("MG4Windows").apply { start() }.looper)
    }
    private val main = Handler(Looper.getMainLooper())

    private val lastValue     = ConcurrentHashMap<PowerWindow, Float>()
    private val lastAutoMs    = ConcurrentHashMap<PowerWindow, Long>()
    private val lastCommandMs = ConcurrentHashMap<PowerWindow, Long>()
    /** Vitres dont la lecture directe échoue : plus relues chaque seconde (erreurs côté service),
     *  seuls les événements les mettent à jour. Vidé par chaque sonde complète. */
    private val unreadable = ConcurrentHashMap.newKeySet<PowerWindow>()
    @Volatile private var lastCommand = "—"
    /** Écouteur abonné, gardé en référence forte ; null tant que l'abonnement n'a pas réussi. */
    @Volatile private var listenerProxy: Any? = null
    private val subscribed get() = listenerProxy != null

    /** Estimation des vitres calibrées sans capteur. Lu et écrit uniquement sur [worker]. */
    private val estimators = HashMap<PowerWindow, WindowEstimator>()
    private var estimatorsLoaded = false

    /**
     * Commande répétée en cours, par vitre : doigt posé ([finger]) ou course auto émulée.
     * Lu et écrit uniquement sur [worker].
     */
    private class Hold(val value: Int, val startMs: Long, val finger: Boolean, val label: String, val repeat: Runnable) {
        var sent = 0
        var failed = 0
        /** Arrêtée si l'utilisateur quitte l'onglet : doigt posé, ou fermeture émulée. */
        val stopOnLeave: Boolean get() = finger || WindowCommand.stopsWhenUnattended(value)
    }
    private val holds = HashMap<PowerWindow, Hold>()

    data class Snapshot(
        val cpmReady: Boolean,
        val subscribed: Boolean,
        val values: Map<PowerWindow, Float?>,
        /** Vitres calibrées → position estimée (null = inconnue jusqu'à la prochaine course complète). */
        val estimates: Map<PowerWindow, Float?>,
        val lastCommand: String,
    )

    init {
        // Un interrupteur physique a pu servir voiture éteinte : toute estimation redevient inconnue.
        MG4Hardware.registerVehicleConditionListener { state ->
            if (state == MG4Hardware.CarIgnitionItem.RUN) worker.post {
                if (estimators.isNotEmpty()) AppLogger.i(TAG, "démarrage : positions estimées remises à inconnues")
                estimators.values.forEach { it.reset() }
            }
        }
    }

    // ── Calibration ─────────────────────────────────────────────────────────

    private fun calKey(window: PowerWindow, part: String) = "win_cal_${window.name.lowercase(Locale.ROOT)}_$part"

    fun calibration(context: Context, window: PowerWindow): WindowCalibration? {
        if (window.hasPositionSensor) return null
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val down = prefs.getLong(calKey(window, "down_ms"), 0L)
        val up = prefs.getLong(calKey(window, "up_ms"), 0L)
        return WindowCalibration(down, up).takeIf { WindowCalibration.isValidMeasure(down) && WindowCalibration.isValidMeasure(up) }
    }

    /** Enregistre la calibration ; l'assistant finit sur une montée complète, la vitre est donc fermée. */
    fun saveCalibration(context: Context, window: PowerWindow, cal: WindowCalibration) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
            putLong(calKey(window, "down_ms"), cal.downMs)
            putLong(calKey(window, "up_ms"), cal.upMs)
        }
        AppLogger.i(TAG, "calibration ${window.shortName} enregistrée : descente ${cal.downMs} ms, montée ${cal.upMs} ms")
        worker.post { estimators[window] = WindowEstimator(cal).apply { setClosed() } }
    }

    /** Sur [worker] : charge une fois les calibrations enregistrées. */
    private fun loadEstimators() {
        if (estimatorsLoaded) return
        val context = MG4Hardware.appContext() ?: return
        PowerWindow.entries.forEach { w -> calibration(context, w)?.let { estimators[w] = WindowEstimator(it) } }
        estimatorsLoaded = true
    }

    /** Sur [worker] : suit la commande envoyée pour estimer la position ; journalise en fin de mouvement. */
    private fun trackEstimate(window: PowerWindow, value: Int) {
        loadEstimators()
        val estimator = estimators[window] ?: return
        val now = SystemClock.elapsedRealtime()
        val direction = WindowCommand.directionOf(value)
        when {
            direction != null -> estimator.start(direction, now)
            value == WindowCommand.STOP -> {
                val before = estimator.current(now)
                estimator.stop(now)
                AppLogger.i(TAG, "estimation ${window.shortName} : ${pct(before)} (fin de mouvement)")
            }
            else -> {
                estimator.reset()
                AppLogger.i(TAG, "estimation ${window.shortName} : valeur $value inconnue → position inconnue")
            }
        }
    }

    private fun pct(position: Float?) = position?.let { "≈ ${it.toInt()} %" } ?: "inconnue"

    // ── Commandes ───────────────────────────────────────────────────────────

    /**
     * Appui court. Vitre à courses natives (conducteur) : course auto, ou stop si elle vient d'être
     * lancée. Autres vitres : course émulée, ou stop si une course émulée est en cours — c'est la
     * répétition en cours qui fait foi, pas le temps écoulé depuis l'appui précédent.
     */
    fun shortPress(window: PowerWindow, direction: Direction) {
        val now = SystemClock.elapsedRealtime()
        worker.post {
            if (!window.hasNativeAuto) {
                if (!endRepeat(window, "appui court")) startEmulatedCourse(window, direction, "appui court")
                return@post
            }
            val value = WindowCommand.forShortPress(direction, lastAutoMs[window], now)
            if (value == WindowCommand.STOP) {
                lastAutoMs.remove(window)
                if (!endRepeat(window, "appui court")) send(window, WindowCommand.STOP, "appui court")
            } else {
                startAuto(window, direction, "appui court")
            }
        }
    }

    /** Tout ouvrir / tout fermer : toujours la course automatique, jamais le stop. */
    fun autoAll(direction: Direction) {
        val origin = "tout ${if (direction == Direction.UP) "fermer" else "ouvrir"}"
        worker.post { PowerWindow.entries.forEach { startAuto(it, direction, origin) } }
    }

    /**
     * Étape 1 de la calibration : fermeture complète. Émulée, elle dure au moins la course par défaut,
     * pour atteindre la butée même si une ancienne calibration était trop courte.
     */
    fun closeForCalibration(window: PowerWindow) {
        lastAutoMs.remove(window)
        worker.post { startAuto(window, Direction.UP, "calibration", minEmulatedMs = WindowCommand.EMULATED_COURSE_MS) }
    }

    /** Sur [worker]. Course automatique : native pour le conducteur, émulée pour les autres vitres. */
    private fun startAuto(window: PowerWindow, direction: Direction, origin: String, minEmulatedMs: Long = 0L) {
        if (!window.hasNativeAuto) {
            startEmulatedCourse(window, direction, origin, minEmulatedMs)
            return
        }
        lastAutoMs[window] = SystemClock.elapsedRealtime()
        endRepeat(window, "$origin demandé")
        send(window, WindowCommand.auto(direction), origin)
    }

    /**
     * Sur [worker]. Course émulée : commande manuelle répétée le temps d'une course (la course
     * calibrée si elle existe), ce que ferait un doigt posé. L'estimation suit donc le vrai mouvement.
     */
    private fun startEmulatedCourse(window: PowerWindow, direction: Direction, origin: String, minMs: Long = 0L) {
        loadEstimators()
        val ms = maxOf(minMs, WindowCommand.emulatedCourseMs(direction, estimators[window]?.calibration))
        val what = if (direction == Direction.UP) "fermeture" else "ouverture"
        startRepeat(window, WindowCommand.manual(direction), ms, finger = false, label = "$origin, $what auto émulée")
    }

    /** Appui long : commande manuelle répétée jusqu'à [stopHold], comme un doigt sur l'interrupteur. */
    fun startHold(window: PowerWindow, direction: Direction) {
        lastAutoMs.remove(window)
        worker.post {
            startRepeat(window, WindowCommand.manual(direction), WindowCommand.HOLD_MAX_MS,
                finger = true, label = "maintien")
        }
    }

    /** Fin de maintien ou d'ouverture émulée : stop envoyé seulement si une répétition était en cours. */
    fun stopHold(window: PowerWindow) {
        worker.post { endRepeat(window, "relâché") }
    }

    /**
     * Onglet quitté : s'arrêtent les doigts posés (le relâché ne viendra peut-être jamais) et les
     * fermetures émulées (pas d'anti-pincement hors de la vue de l'utilisateur). Une ouverture
     * émulée, bornée dans le temps, va au bout comme une course native.
     */
    fun stopUnattendedMoves() {
        worker.post { holds.filterValues { it.stopOnLeave }.keys.toList().forEach { endRepeat(it, "onglet quitté") } }
    }

    /** Test brut de l'onglet Diagnostic : n'importe quelle valeur de 0 à 7, pour décoder l'échelle. */
    fun sendRaw(window: PowerWindow, value: Int) {
        if (!WindowCommand.isValid(value)) return
        lastAutoMs.remove(window)
        worker.post {
            endRepeat(window, "test brut")
            send(window, value, "test brut")
        }
    }

    /** Sur [worker]. Remplace la répétition en cours sur cette vitre, s'il y en a une. */
    private fun startRepeat(window: PowerWindow, value: Int, maxMs: Long, finger: Boolean, label: String) {
        holds.remove(window)?.let { worker.removeCallbacks(it.repeat) }
        lateinit var hold: Hold
        val repeat = object : Runnable {
            override fun run() {
                if (holds[window] !== hold) return
                if (SystemClock.elapsedRealtime() - hold.startMs >= maxMs) {
                    if (finger) AppLogger.w(TAG, "${window.shortName} $label : $maxMs ms dépassés, arrêt de sécurité")
                    endRepeat(window, if (finger) "arrêt de sécurité" else "course terminée")
                    return
                }
                if (send(window, value, label, quiet = true)) hold.sent++ else hold.failed++
                worker.postDelayed(this, WindowCommand.HOLD_REPEAT_MS)
            }
        }
        hold = Hold(value, SystemClock.elapsedRealtime(), finger, label, repeat)
        holds[window] = hold
        AppLogger.i(TAG, "${window.shortName} $label : début ← $value toutes les " +
            "${WindowCommand.HOLD_REPEAT_MS} ms (max $maxMs ms)")
        repeat.run()
    }

    /** Sur [worker]. Arrête la répétition en cours et envoie le stop ; faux s'il n'y en avait pas. */
    private fun endRepeat(window: PowerWindow, reason: String): Boolean {
        val hold = holds.remove(window) ?: return false
        worker.removeCallbacks(hold.repeat)
        val ms = SystemClock.elapsedRealtime() - hold.startMs
        AppLogger.i(TAG, "${window.shortName} ${hold.label} : fin ($reason) ← ${hold.value} ×${hold.sent} " +
            "(échecs ${hold.failed}) en $ms ms")
        send(window, WindowCommand.STOP, reason)
        return true
    }

    /** À appeler sur [worker]. [quiet] : pas de log par envoi (répétition du maintien, résumée à la fin). */
    private fun send(window: PowerWindow, value: Int, origin: String, quiet: Boolean = false): Boolean {
        val cpm = MG4Hardware.carPropertyManager()
        val label = "${window.shortName} 0x${window.propId.toString(16)} ← $value"
        if (cpm == null) {
            AppLogger.w(TAG, "$label ($origin) : CarPropertyManager indisponible")
            lastCommand = "$label : CPM indisponible"
            return false
        }
        val setter = runCatching {
            cpm.javaClass.getMethod("setFloatProperty", Int::class.java, Int::class.java, Float::class.java)
        }.getOrElse {
            AppLogger.w(TAG, "$label ($origin) : setFloatProperty introuvable (${describe(it)})")
            lastCommand = "$label : setFloatProperty introuvable"
            return false
        }
        // Zone des services SAIC (0x1000000) ; 0 en repli, au cas où un firmware la refuserait.
        var used = AREA_GLOBAL
        var result = runCatching { setter.invoke(cpm, window.propId, AREA_GLOBAL, value.toFloat()) }
        if (result.isFailure) {
            val first = describe(result.exceptionOrNull())
            used = 0
            result = runCatching { setter.invoke(cpm, window.propId, 0, value.toFloat()) }
            if (!quiet) AppLogger.w(TAG, "$label zone 0x1000000 refusée ($first) → essai zone 0")
        }
        lastCommandMs[window] = SystemClock.elapsedRealtime()
        val ok = result.isSuccess
        lastCommand = "$label (${origin}) : " + if (ok) "envoyé" else "refusé"
        if (!quiet || !ok) {
            val msg = "$label zone 0x${used.toString(16)} ($origin) : " +
                if (ok) "envoyé" else "refusé (${describe(result.exceptionOrNull())})"
            if (ok) AppLogger.i(TAG, msg) else AppLogger.w(TAG, msg)
        }
        if (ok) trackEstimate(window, value)
        return ok
    }

    // ── Lecture et abonnement ───────────────────────────────────────────────

    /** Relit les 4 vitres (lecture directe, sinon dernière valeur reçue) et rend l'état à l'écran. */
    fun refresh(onResult: (Snapshot) -> Unit) {
        worker.post {
            val cpm = MG4Hardware.carPropertyManager()
            val values = PowerWindow.entries.associateWith { w ->
                val read = if (cpm == null || w in unreadable) null else readFloat(cpm, w.propId)
                if (read != null) lastValue[w] = read
                else if (cpm != null && unreadable.add(w)) AppLogger.i(TAG, "${w.shortName} : lecture directe impossible, suivi par événements seulement")
                read ?: lastValue[w]
            }
            loadEstimators()
            val now = SystemClock.elapsedRealtime()
            val estimates = estimators.mapValues { it.value.current(now) }
            val snap = Snapshot(cpm != null, subscribed, values, estimates, lastCommand)
            main.post { onResult(snap) }
        }
    }

    /**
     * Abonnement aux changements des 4 vitres, une fois pour tout le processus.
     * Sur plusieurs firmwares une propriété n'est PAS lisible à la demande mais poussée en
     * changement (constaté pour les portes).
     */
    fun ensureSubscribed() {
        worker.post {
            if (subscribed) return@post
            val cpm = MG4Hardware.carPropertyManager() ?: run {
                AppLogger.w(TAG, "abonnement reporté : CarPropertyManager indisponible")
                return@post
            }
            listenerProxy = register(cpm, PowerWindow.entries.map { it.propId })
        }
    }

    @SuppressLint("PrivateApi")   // API Car cachée, lue par réflexion : l'app tourne en uid système (voulu).
    private fun register(cpm: Any, props: List<Int>): Any? {
        // Le Proxy doit venir du classloader de l'APP : android.car est dans le BootClassLoader,
        // où Proxy.newProxyInstance échoue (constaté sur la détection des portes).
        val cl = MG4Hardware.appContext()?.classLoader ?: return null
        val variants = listOf(
            "android.car.hardware.property.CarPropertyManager\$CarPropertyEventListener" to "registerListener",
            "android.car.hardware.property.CarPropertyManager\$CarPropertyEventCallback" to "registerCallback",
        )
        for ((ifaceName, methodName) in variants) {
            val iface = runCatching { cl.loadClass(ifaceName) }.getOrNull() ?: continue
            val method = runCatching {
                cpm.javaClass.getMethod(methodName, iface, Int::class.javaPrimitiveType, Float::class.javaPrimitiveType)
            }.getOrNull() ?: continue
            val proxy = Proxy.newProxyInstance(cl, arrayOf(iface), eventHandler())
            var anyOk = false
            val results = props.map { p ->
                // rate 5 et non 0 : une propriété déclarée CONTINUOUS ne remonte rien à 0.
                val r = runCatching { method.invoke(cpm, proxy, p, 5f) }
                // registerListener rend un booléen (false = refusé) ; registerCallback ne rend rien.
                if (r.isSuccess && r.getOrNull() != false) anyOk = true
                "0x${p.toString(16)}=" + if (r.isSuccess) "${r.getOrNull() ?: "ok"}" else describe(r.exceptionOrNull())
            }
            AppLogger.i(TAG, "abonnement via $methodName : ${results.joinToString(" ")}")
            // Tout refusé : on ne garde rien, le prochain affichage de l'onglet retentera.
            if (anyOk) return proxy
        }
        AppLogger.w(TAG, "abonnement impossible (détail dans les lignes précédentes, ou méthodes absentes)")
        return null
    }

    private fun eventHandler() = InvocationHandler { proxy, method, args ->
        when (method.name) {
            "onChangeEvent" -> { runCatching { onEvent(args?.getOrNull(0)) }
                .onFailure { AppLogger.w(TAG, "événement illisible : ${describe(it)}") }; null }
            "onErrorEvent"  -> { AppLogger.w(TAG, "événement d'erreur : ${args?.joinToString()}"); null }
            "hashCode"      -> System.identityHashCode(proxy)
            "equals"        -> proxy === args?.getOrNull(0)
            "toString"      -> "MG4WindowListener@" + Integer.toHexString(System.identityHashCode(proxy))
            else            -> null
        }
    }

    private fun onEvent(cpv: Any?) {
        cpv ?: return
        val pid  = cpv.javaClass.getMethod("getPropertyId").invoke(cpv) as? Int ?: return
        val area = cpv.javaClass.getMethod("getAreaId").invoke(cpv) as? Int ?: 0
        val raw  = cpv.javaClass.getMethod("getValue").invoke(cpv)
        val window = PowerWindow.entries.firstOrNull { it.propId == pid } ?: return
        val v = (raw as? Number)?.toFloat() ?: return
        val prev = lastValue.put(window, v)
        if (prev == v) return
        val sinceCmd = lastCommandMs[window]?.let { SystemClock.elapsedRealtime() - it }
        val origin = if (sinceCmd != null && sinceCmd < OWN_COMMAND_WINDOW_MS) "après commande app (+$sinceCmd ms)"
                     else "sans commande app récente → interrupteur physique ?"
        val sensor = if (WindowCommand.position(v) == null) " [hors 0..100 : pas de capteur]" else ""
        AppLogger.i(TAG, "événement ${window.shortName} zone 0x${area.toString(16)} : ${prev ?: "?"} → $v ($origin)$sensor")
    }

    // ── Sonde complète ──────────────────────────────────────────────────────

    /**
     * Relevé complet, journalisé et rendu en texte pour la carte Diagnostic : propriétés déclarées
     * (zones, accès, mode de changement), lecture de chaque zone, configuration, calibrations.
     */
    fun probe(origin: String, onResult: (String) -> Unit) {
        worker.post {
            val lines = mutableListOf<String>()
            val cpm = MG4Hardware.carPropertyManager()
            unreadable.clear()
            lines += "firmware ${FirmwareInfo.getGeneration()} · CPM ${if (cpm != null) "prêt" else "indisponible"} · abonnement ${if (subscribed) "actif" else "inactif"}"
            if (cpm != null) {
                val configs = runCatching {
                    (cpm.javaClass.getMethod("getPropertyList").invoke(cpm) as? List<*>).orEmpty()
                }.getOrElse { lines += "liste des propriétés illisible : ${describe(it)}"; emptyList() }
                val byId = configs.filterNotNull().associateBy { cfgInt(it, "getPropertyId") }
                lines += "${configs.size} propriétés déclarées"

                PowerWindow.entries.forEach { w ->
                    val cfg = byId[w.propId]
                    val areas = cfg?.let { cfgAreas(it) }.orEmpty()
                    val reads = (areas + AREA_GLOBAL + 0).distinct().joinToString(" ") { a ->
                        "0x${a.toString(16)}=" + readFloatArea(cpm, w.propId, a).fold({ "$it" }, { describe(it) })
                    }
                    lines += "${w.shortName} 0x${w.propId.toString(16)} ${describeConfig(cfg, areas)} · $reads"
                }
                listOf(PROP_WINDOW_POS to "WINDOW_POS", PROP_WINDOW_MOVE to "WINDOW_MOVE").forEach { (p, name) ->
                    val cfg = byId[p]
                    lines += "$name 0x${p.toString(16)} ${describeConfig(cfg, cfg?.let { cfgAreas(it) }.orEmpty())}"
                }
                // Codes de configuration du véhicule (finition) : ce que le service SWI68 lit pour
                // savoir si la vitre conducteur / les vitres arrière ont la fonction automatique.
                listOf(PROP_CFG_DRIVER_WINDOW to "config vitre conducteur", PROP_CFG_REAR_WINDOW_AUTO to "config vitres AR auto")
                    .forEach { (p, name) -> lines += "$name 0x${p.toString(16)} = ${readBytes(cpm, p)}" }
            }
            loadEstimators()
            PowerWindow.entries.filterNot { it.hasPositionSensor }.forEach { w ->
                lines += "calibration ${w.shortName} : " + (estimators[w]?.calibration
                    ?.let { "descente ${it.downMs} ms, montée ${it.upMs} ms" } ?: "aucune")
            }
            lines.forEach { AppLogger.i(TAG, "SONDE [$origin] $it") }
            val text = lines.joinToString("\n")
            main.post { onResult(text) }
        }
    }

    private fun describeConfig(cfg: Any?, areas: List<Int>): String {
        cfg ?: return "absente de la liste"
        val access = runCatching { cfg.javaClass.getMethod("getAccess").invoke(cfg) }.getOrNull()
        val change = runCatching { cfg.javaClass.getMethod("getChangeMode").invoke(cfg) }.getOrNull()
        return "déclarée zones=${areas.joinToString(",") { "0x${it.toString(16)}" }} accès=${access ?: "?"} mode=${change ?: "?"}"
    }

    private fun cfgInt(cfg: Any, getter: String): Int? =
        runCatching { cfg.javaClass.getMethod(getter).invoke(cfg) as? Int }.getOrNull()

    private fun cfgAreas(cfg: Any): List<Int> =
        runCatching { (cfg.javaClass.getMethod("getAreaIds").invoke(cfg) as? IntArray)?.toList() }.getOrNull().orEmpty()

    private fun readFloatArea(cpm: Any, propId: Int, area: Int): Result<Float> = runCatching {
        cpm.javaClass.getMethod("getFloatProperty", Int::class.java, Int::class.java)
            .invoke(cpm, propId, area) as Float
    }

    private fun readFloat(cpm: Any, propId: Int): Float? =
        readFloatArea(cpm, propId, AREA_GLOBAL).getOrNull() ?: readFloatArea(cpm, propId, 0).getOrNull()

    /** Propriété octets (codes de configuration) en hexadécimal, ou la cause de l'échec. */
    private fun readBytes(cpm: Any, propId: Int): String {
        val getter = runCatching {
            cpm.javaClass.getMethod("getProperty", Class::class.java, Int::class.java, Int::class.java)
        }.getOrElse { return "getProperty introuvable" }
        var last: Throwable? = null
        for (area in intArrayOf(0, AREA_GLOBAL)) {
            val r = runCatching {
                val cpv = getter.invoke(cpm, ByteArray::class.java, propId, area)
                cpv?.javaClass?.getMethod("getValue")?.invoke(cpv) as? ByteArray
            }
            r.getOrNull()?.let { bytes -> return bytes.joinToString(" ", "[", "]") { String.format(Locale.ROOT, "%02x", it) } }
            last = r.exceptionOrNull() ?: last
        }
        return "illisible (${describe(last)})"
    }

    /** Cause réelle d'un échec par réflexion (InvocationTargetException n'a pas de message). */
    private fun describe(t: Throwable?): String {
        val c = (t as? java.lang.reflect.InvocationTargetException)?.cause ?: t
        return "${c?.javaClass?.simpleName}: ${c?.message}"
    }
}
