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
import com.mg4.control.model.WindowCommand
import com.mg4.control.model.WindowCommand.Direction
import com.mg4.control.util.FirmwareInfo
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
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
 * Sondes (tag [TAG]) : liste des propriétés déclarées, lectures par zone, chaque commande avec
 * son résultat, et chaque changement de valeur reçu du véhicule avec le délai depuis la dernière
 * commande de l'app — un mouvement sans commande récente vient donc de l'interrupteur physique.
 */
object PowerWindows {

    const val TAG = "MG4_WIN"

    /** Store partagé avec les Réglages. */
    private const val PREFS_NAME = "mg4_settings"
    private const val KEY_CHILD_LOCK = "windows_child_lock"

    private const val AREA_GLOBAL = 0x1000000
    /** AOSP WINDOW_LOCK (booléen, zone VehicleAreaWindow) — utilisé par aucune appli d'origine. */
    private const val PROP_WINDOW_LOCK = 0x13200bc4
    /** AOSP WINDOW_POS / WINDOW_MOVE : déclarées dans les firmwares, sondées pour information. */
    private const val PROP_WINDOW_POS  = 0x13400bc0
    private const val PROP_WINDOW_MOVE = 0x13400bc1
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

    /** Maintien en cours, par vitre. Lu et écrit uniquement sur [worker]. */
    private class Hold(val value: Int, val startMs: Long, val repeat: Runnable) {
        var sent = 0
        var failed = 0
    }
    private val holds = HashMap<PowerWindow, Hold>()

    data class Snapshot(
        val cpmReady: Boolean,
        val subscribed: Boolean,
        val values: Map<PowerWindow, Float?>,
        val lastCommand: String,
    )

    // ── Sécurité enfant ─────────────────────────────────────────────────────

    fun isChildLockOn(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean(KEY_CHILD_LOCK, false)

    /**
     * Enregistre le choix, arrête un maintien en cours sur une vitre arrière, puis tente le verrou
     * natif `WINDOW_LOCK` sur les deux zones arrière avec relecture. Le blocage des commandes de
     * l'app ne dépend PAS de ce verrou : il vaut même si le véhicule l'ignore.
     */
    fun setChildLock(context: Context, on: Boolean, onResult: (String) -> Unit) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit { putBoolean(KEY_CHILD_LOCK, on) }
        AppLogger.i(TAG, "sécurité enfant ${if (on) "ON" else "OFF"} (choix utilisateur)")
        if (on) PowerWindow.entries.filter { it.isRear }.forEach { stopHold(it) }
        worker.post {
            val result = applyNativeLock(on)
            main.post { onResult(result) }
        }
    }

    private fun applyNativeLock(on: Boolean): String {
        val cpm = MG4Hardware.carPropertyManager() ?: return "CarPropertyManager indisponible".also {
            AppLogger.w(TAG, "WINDOW_LOCK ← $on : $it")
        }
        return PowerWindow.entries.filter { it.isRear }.joinToString("\n") { w ->
            val area = "0x${w.lockArea.toString(16)}"
            val write = runCatching {
                cpm.javaClass.getMethod("setBooleanProperty", Int::class.java, Int::class.java, Boolean::class.java)
                    .invoke(cpm, PROP_WINDOW_LOCK, w.lockArea, on)
            }
            val readBack = readBoolean(cpm, PROP_WINDOW_LOCK, w.lockArea)
            val line = "WINDOW_LOCK ${w.shortName} ($area) ← $on : " +
                (if (write.isSuccess) "écrit" else "refusé (${describe(write.exceptionOrNull())})") +
                ", relu = ${readBack ?: "illisible"}"
            if (write.isSuccess && readBack == on) AppLogger.i(TAG, line) else AppLogger.w(TAG, line)
            line
        }
    }

    // ── Commandes ───────────────────────────────────────────────────────────

    /** Appui court : course automatique, ou stop si une course vient d'être lancée. */
    fun shortPress(window: PowerWindow, direction: Direction) {
        val now = SystemClock.elapsedRealtime()
        val value = WindowCommand.forShortPress(direction, lastAutoMs[window], now)
        if (value == WindowCommand.STOP) lastAutoMs.remove(window) else lastAutoMs[window] = now
        worker.post { send(window, value, "appui court") }
    }

    /** Tout ouvrir / tout fermer : toujours la course automatique, jamais le stop. */
    fun autoAll(windows: List<PowerWindow>, direction: Direction) {
        val now = SystemClock.elapsedRealtime()
        val value = WindowCommand.auto(direction)
        windows.forEach { lastAutoMs[it] = now }
        worker.post { windows.forEach { send(it, value, "tout ${if (direction == Direction.UP) "fermer" else "ouvrir"}") } }
    }

    /** Appui long : commande manuelle répétée jusqu'à [stopHold], comme un doigt sur l'interrupteur. */
    fun startHold(window: PowerWindow, direction: Direction) {
        lastAutoMs.remove(window)
        val value = WindowCommand.manual(direction)
        worker.post {
            holds.remove(window)?.let { worker.removeCallbacks(it.repeat) }
            lateinit var hold: Hold
            val repeat = object : Runnable {
                override fun run() {
                    if (holds[window] !== hold) return
                    if (SystemClock.elapsedRealtime() - hold.startMs > WindowCommand.HOLD_MAX_MS) {
                        AppLogger.w(TAG, "maintien ${window.shortName} : ${WindowCommand.HOLD_MAX_MS} ms dépassés, arrêt de sécurité")
                        stopHold(window)
                        return
                    }
                    if (send(window, value, "maintien", quiet = true)) hold.sent++ else hold.failed++
                    worker.postDelayed(this, WindowCommand.HOLD_REPEAT_MS)
                }
            }
            hold = Hold(value, SystemClock.elapsedRealtime(), repeat)
            holds[window] = hold
            AppLogger.i(TAG, "maintien ${window.shortName} début ← $value toutes les ${WindowCommand.HOLD_REPEAT_MS} ms")
            repeat.run()
        }
    }

    /** Fin de maintien : stop envoyé seulement si un maintien était en cours (sans effet sinon). */
    fun stopHold(window: PowerWindow) {
        worker.post {
            val hold = holds.remove(window) ?: return@post
            worker.removeCallbacks(hold.repeat)
            val ms = SystemClock.elapsedRealtime() - hold.startMs
            AppLogger.i(TAG, "maintien ${window.shortName} fin : ← ${hold.value} ×${hold.sent} " +
                "(échecs ${hold.failed}) en $ms ms")
            send(window, WindowCommand.STOP, "fin de maintien")
        }
    }

    fun stopAllHolds() = PowerWindow.entries.forEach { stopHold(it) }

    /** Test brut de l'onglet Diagnostic : n'importe quelle valeur de 0 à 7, pour décoder l'échelle. */
    fun sendRaw(window: PowerWindow, value: Int) {
        if (!WindowCommand.isValid(value)) return
        lastAutoMs.remove(window)
        worker.post { send(window, value, "test brut") }
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
            val snap = Snapshot(cpm != null, subscribed, values, lastCommand)
            main.post { onResult(snap) }
        }
    }

    /**
     * Abonnement aux changements des 4 vitres et de WINDOW_LOCK, une fois pour tout le processus.
     * Sur plusieurs firmwares une propriété n'est PAS lisible à la demande mais poussée en
     * changement (constaté pour les portes) : c'est l'abonnement qui voit l'interrupteur physique.
     */
    fun ensureSubscribed() {
        worker.post {
            if (subscribed) return@post
            val cpm = MG4Hardware.carPropertyManager() ?: run {
                AppLogger.w(TAG, "abonnement reporté : CarPropertyManager indisponible")
                return@post
            }
            val props = PowerWindow.entries.map { it.propId } + PROP_WINDOW_LOCK
            listenerProxy = register(cpm, props)
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
        if (pid == PROP_WINDOW_LOCK) {
            AppLogger.i(TAG, "événement WINDOW_LOCK zone 0x${area.toString(16)} = $raw")
            return
        }
        val window = PowerWindow.entries.firstOrNull { it.propId == pid } ?: return
        val v = (raw as? Number)?.toFloat() ?: return
        val prev = lastValue.put(window, v)
        if (prev == v) return
        val sinceCmd = lastCommandMs[window]?.let { SystemClock.elapsedRealtime() - it }
        val origin = if (sinceCmd != null && sinceCmd < OWN_COMMAND_WINDOW_MS) "après commande app (+$sinceCmd ms)"
                     else "sans commande app récente → interrupteur physique ?"
        val context = MG4Hardware.appContext()
        val lockNote = if (window.isRear && context != null && isChildLockOn(context))
            " ⚠ sécurité enfant ON" else ""
        val msg = "événement ${window.shortName} zone 0x${area.toString(16)} : ${prev ?: "?"} → $v ($origin)$lockNote"
        if (lockNote.isEmpty()) AppLogger.i(TAG, msg) else AppLogger.w(TAG, msg)
    }

    // ── Sonde complète ──────────────────────────────────────────────────────

    /**
     * Relevé complet, journalisé et rendu en texte pour la carte Diagnostic : propriétés déclarées
     * (zones, accès, mode de changement), lecture de chaque zone, verrou natif, abonnement.
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
                listOf(PROP_WINDOW_LOCK to "WINDOW_LOCK", PROP_WINDOW_POS to "WINDOW_POS", PROP_WINDOW_MOVE to "WINDOW_MOVE")
                    .forEach { (p, name) ->
                        val cfg = byId[p]
                        lines += "$name 0x${p.toString(16)} ${describeConfig(cfg, cfg?.let { cfgAreas(it) }.orEmpty())}"
                    }
                PowerWindow.entries.filter { it.isRear }.forEach { w ->
                    lines += "WINDOW_LOCK ${w.shortName} (0x${w.lockArea.toString(16)}) = ${readBoolean(cpm, PROP_WINDOW_LOCK, w.lockArea) ?: "illisible"}"
                }
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

    private fun readBoolean(cpm: Any, propId: Int, area: Int): Boolean? = runCatching {
        cpm.javaClass.getMethod("getBooleanProperty", Int::class.java, Int::class.java)
            .invoke(cpm, propId, area) as? Boolean
    }.getOrNull()

    /** Cause réelle d'un échec par réflexion (InvocationTargetException n'a pas de message). */
    private fun describe(t: Throwable?): String {
        val c = (t as? java.lang.reflect.InvocationTargetException)?.cause ?: t
        return "${c?.javaClass?.simpleName}: ${c?.message}"
    }
}
