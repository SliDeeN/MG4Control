package com.mg4.control.hardware

import android.annotation.SuppressLint
import android.app.UiModeManager
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.provider.Settings
import com.mg4.control.debug.AppLogger
import com.mg4.control.model.StatsTracker
import com.mg4.control.util.FirmwareInfo
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Sonde lumière et position, en **lecture seule** : de quoi décider comment régler la luminosité
 * de l'écran au passage en READY (étude du 2026-10-01). Elle n'écrit rien sur le véhicule.
 *
 * Trois questions :
 *  1. **La voiture mesure-t-elle la lumière ?** `OUTSIDE_AMBIENT_LIGHT` (0x2140934f) est un entier
 *     qu'aucune application d'origine ne lit : s'il suit la lumière, il suffit à lui seul. À côté,
 *     les signaux jour/nuit connus (feux de position, NIGHT_MODE…) pour les comparer.
 *  2. **La position est-elle là au READY, et en combien de temps ?** Fournisseurs Android, dont
 *     `fused`, alimenté par le GPS de la T-Box sur SWI68 et SWI69 (firmware décompilé).
 *  3. **Le réseau est-il monté au READY ?** Il décide si une API peut répondre à ce moment-là.
 *
 * Relevés automatiques à chaque passage en READY (+5 s et +90 s) et à chaque ouverture du
 * Diagnostic ; ils sont gardés dans [reports] pour le rapport. **Aucune coordonnée n'est
 * journalisée** : le rapport est souvent publié tel quel.
 */
object LightProbe {

    const val TAG = "MG4_LIGHT"

    /** Assez pour quelques démarrages (deux relevés chacun) et des ouvertures du Diagnostic. */
    private const val MAX_RELEVES = 12

    private const val AREA_GLOBAL = 0x1000000
    private const val PREMIER_RELEVE_MS = 5_000L
    private const val SECOND_RELEVE_MS = 90_000L
    /** Délai laissé à une position fraîche : sous le second relevé, pour que celui-ci la voie. */
    private const val ATTENTE_POSITION_MS = 80_000L

    private val FOURNISSEURS = listOf("gps", "fused", "network", "passive")

    private data class Signal(val name: String, val id: Int)

    private val SIGNALS = listOf(
        Signal("OUTSIDE_AMBIENT_LIGHT (lumière extérieure ?)", 0x2140934f),
        Signal("VEH_SIDE_LGHT (feux de position, 3 = nuit d'origine)", 0x21409323),
        Signal("FICM_DAY_NIGHT_MD", 0x21407b08),
        Signal("PMS_SYSTEM_DAY_NIGHT_MODE", 0x11408a0d),
        Signal("NIGHT_MODE (AOSP)", 0x11200407),
        Signal("HEADLIGHTS_STATE (AOSP)", 0x11400e00),
        Signal("DISPLAY_BRIGHTNESS (AOSP)", 0x11400a03),
    )

    /** Lus aussi par le CarLampManager SAIC, la voie des écrans d'origine. */
    private val PAR_LAMP = setOf(0x2140934f, 0x21409323)

    /** Ancien SDK (SWI133) : identifiants VPM de la table `VehiclePropertyID`. */
    private val SIGNAUX_VPM = listOf(
        Signal("VPM ID_LIGHT_SENSOR", 0x101002a),
        Signal("VPM ID_FICM_DAY_NIGHT_MD", 0x2040014),
    )

    /** Clés Settings.System : Android standard, puis celles des A9 (absentes ailleurs). */
    private val CLES_SYSTEME = listOf(
        "screen_brightness", "screen_brightness_mode",
        "auto_brightness_mode", "brightness_percent", "screen_skin_mode",
    )

    private val historique = ArrayDeque<String>()

    /** Tous les relevés gardés, du plus ancien au plus récent. */
    val reports: List<String>
        @Synchronized get() = historique.toList()

    @Volatile private var appContext: Context? = null

    /** Fil dédié : les relevés font une vingtaine de lectures binder, les positions y arrivent aussi. */
    private val handler: Handler by lazy {
        Handler(HandlerThread("mg4-light-probe").also { it.start() }.looper)
    }

    /** Jeton des relevés programmés : un nouveau READY les annule sans toucher aux attentes de position. */
    private val RELEVES = Any()

    private val readyListener = ReadyWatcher.Listener { ready, firstRead ->
        // Seules les vraies transitions vers READY : c'est le moment où la luminosité serait réglée.
        if (!ready || firstRead) return@Listener
        handler.removeCallbacksAndMessages(RELEVES)
        handler.postDelayed({ run("READY+5s"); demanderPositionsFraiches() }, RELEVES, PREMIER_RELEVE_MS)
        handler.postDelayed({ run("READY+90s") }, RELEVES, SECOND_RELEVE_MS)
    }

    fun start(context: Context) {
        appContext = context.applicationContext
        ReadyWatcher.add(readyListener)
    }

    /** Relève tout et renvoie le texte du relevé. Hors du fil principal : une vingtaine de lectures binder. */
    fun run(origin: String): String {
        val ctx = appContext ?: MG4Hardware.appContext()
        val maintenant = System.currentTimeMillis()
        val horloge = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(Date(maintenant))
        val lignes = mutableListOf<String>()
        lignes += "══ Sonde lumière [$origin] · ${FirmwareInfo.getGeneration()} · horloge $horloge" +
            (if (StatsTracker.clockPlausible(maintenant)) "" else " (NON SYNCHRONISÉE)") + " ══"

        lignes += "Lumière / jour-nuit :"
        val cpm = MG4Hardware.carPropertyManager()
        val declarees = declarees(cpm)
        val lamp = carManager("lamp")
        SIGNALS.forEach { s ->
            val voies = mutableListOf<String>()
            if (declarees != null) voies += "déclarée=" + if (s.id in declarees) "oui" else "non"
            if (s.id in PAR_LAMP) lamp?.let { voies += "lamp=" + lireLamp(it, s.id) }
            voies += "cpm@global=" + lireCpm(cpm, s.id, AREA_GLOBAL)
            voies += "@0=" + lireCpm(cpm, s.id, 0)
            lignes += "  ${s.name} 0x${hex(s.id)} : ${voies.joinToString(" · ")}"
        }
        if (FirmwareInfo.getGeneration() == FirmwareInfo.Gen.SWI133) {
            SIGNAUX_VPM.forEach { s ->
                lignes += "  ${s.name} 0x${hex(s.id)} : ${MG4Hardware.vpmIntForProbe(s.id)}"
            }
        }
        lignes += "  inventaire lamp : " + inventaire(lamp)
        lignes += "  UiModeManager.nightMode=" + runCatching {
            (ctx?.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager)?.nightMode
        }.getOrNull() + " (1=jour, 2=nuit)"
        MG4Hardware.generalManager()?.let { g ->
            lignes += "  GeneralManager : " + listOf(
                "getIsNightMode", "getDayNightAutoMode", "getBrightnessAutoState", "getBrightness"
            ).joinToString(" · ") { "$it=" + appel(g, it) }
        }

        lignes += "Écran : luminosité=${MG4Hardware.getScreenBrightnessPercent()} % · " +
            CLES_SYSTEME.joinToString(" · ") { "$it=" + systeme(ctx, it) }

        lignes += "Position :"
        lignes += position(ctx)

        lignes += "Réseau : " + reseau(ctx)

        val texte = lignes.joinToString("\n")
        lignes.forEach { AppLogger.i(TAG, it) }
        garde(texte)
        return texte
    }

    // ── Position ─────────────────────────────────────────────────────────────

    /**
     * Fournisseurs et dernières positions connues — sans les coordonnées. L'application tourne en
     * `android.uid.system` : le contrôle de permission passe sans déclaration au manifeste, et une
     * SecurityException éventuelle est journalisée telle quelle (c'est une réponse en soi).
     */
    @SuppressLint("MissingPermission")
    private fun position(ctx: Context?): List<String> {
        ctx ?: return listOf("  contexte absent")
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return listOf("  LocationManager absent")
        val lignes = mutableListOf<String>()
        @Suppress("DEPRECATION")
        val mode = runCatching {
            Settings.Secure.getInt(ctx.contentResolver, Settings.Secure.LOCATION_MODE, -1)
        }.getOrDefault(-1)
        val autorises = runCatching {
            Settings.Secure.getString(ctx.contentResolver, "location_providers_allowed")
        }.getOrNull()
        lignes += "  réglage : location_mode=$mode (0=désactivée, 3=précise) · autorisés=${autorises ?: "?"}"
        lignes += "  fournisseurs : tous=" + runCatching { lm.allProviders.toString() }.getOrElse { court(it) } +
            " · actifs=" + runCatching { lm.getProviders(true).toString() }.getOrElse { court(it) }
        FOURNISSEURS.forEach { p ->
            val actif = runCatching { lm.isProviderEnabled(p).toString() }.getOrElse { court(it) }
            val derniere = try {
                lm.getLastKnownLocation(p)?.let { decrire(it) } ?: "aucune"
            } catch (e: Exception) {
                court(e)
            }
            lignes += "  $p : actif=$actif · dernière=$derniere"
        }
        return lignes
    }

    /** Précision, âge et écart entre l'heure de la position et l'horloge — jamais les coordonnées. */
    private fun decrire(l: Location): String {
        val ageS = (SystemClock.elapsedRealtimeNanos() - l.elapsedRealtimeNanos) / 1_000_000_000L
        val ecartS = (l.time - System.currentTimeMillis()) / 1000L
        val precision = if (l.hasAccuracy()) "${l.accuracy.toInt()} m" else "?"
        return "oui (précision $precision, âge $ageS s, heure position − horloge = $ecartS s)"
    }

    /**
     * Demande UNE position fraîche à `gps` et `fused` et journalise le délai d'arrivée : c'est ce
     * qui dira s'il faut l'attendre au READY ou se contenter de la dernière position connue.
     * Écoute et délai d'abandon tournent sur le même fil, d'où le simple booléen [recue].
     */
    @SuppressLint("MissingPermission")
    private fun demanderPositionsFraiches() {
        val lm = appContext?.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return
        listOf("gps", "fused").forEach { p ->
            val debut = SystemClock.elapsedRealtime()
            var recue = false
            // Les quatre méthodes, même celles qui ont un corps par défaut à la compilation : sur le
            // framework de la voiture (API 28) elles sont abstraites, un oubli serait fatal.
            val ecoute = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    if (recue) return
                    recue = true
                    val s = (SystemClock.elapsedRealtime() - debut) / 1000.0
                    note("position fraîche $p reçue en %.1f s → %s".format(Locale.ROOT, s, decrire(location)))
                }
                @Deprecated("Deprecated in Java")
                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
                override fun onProviderEnabled(provider: String) {}
                override fun onProviderDisabled(provider: String) {}
            }
            try {
                @Suppress("DEPRECATION")
                lm.requestSingleUpdate(p, ecoute, handler.looper)
                handler.postDelayed({
                    runCatching { lm.removeUpdates(ecoute) }
                    if (!recue) note("position fraîche $p : rien reçu en ${ATTENTE_POSITION_MS / 1000} s")
                }, ATTENTE_POSITION_MS)
            } catch (e: Exception) {
                note("position fraîche $p : demande refusée — ${court(e)}")
            }
        }
    }

    private fun note(texte: String) {
        AppLogger.i(TAG, texte)
        garde("  ⤷ $texte")
    }

    // ── Réseau ───────────────────────────────────────────────────────────────

    private fun reseau(ctx: Context?): String {
        val cm = ctx?.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return "ConnectivityManager absent"
        return try {
            val n = cm.activeNetwork ?: return "aucun réseau actif"
            val c = cm.getNetworkCapabilities(n) ?: return "réseau actif, capacités illisibles"
            val type = when {
                c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
                c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellulaire"
                c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)     -> "wifi"
                else                                                   -> "autre"
            }
            "actif ($type) · internet=${c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)}" +
                " · validé=${c.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)}"
        } catch (e: Exception) {
            court(e)
        }
    }

    // ── Lectures ─────────────────────────────────────────────────────────────

    @Synchronized
    private fun garde(texte: String) {
        historique.addLast(texte)
        while (historique.size > MAX_RELEVES) historique.removeFirst()
    }

    /** Identifiants que le véhicule déclare porter, ou null si la liste est illisible. */
    private fun declarees(cpm: Any?): Set<Int>? = cpm?.let { ids(it)?.toSet() }

    private fun inventaire(manager: Any?): String {
        manager ?: return "gestionnaire absent"
        val ids = ids(manager) ?: return "liste illisible"
        return "${ids.size} propriétés → " + ids.sorted().joinToString(" ") { "0x" + hex(it) }
    }

    private fun ids(manager: Any): List<Int>? = runCatching {
        (manager.javaClass.getMethod("getPropertyList").invoke(manager) as? List<*>)
            ?.filterNotNull()
            ?.mapNotNull { runCatching { it.javaClass.getMethod("getPropertyId").invoke(it) as? Int }.getOrNull() }
    }.getOrNull()

    private fun lireCpm(cpm: Any?, id: Int, area: Int): String {
        cpm ?: return "cpm absent"
        val getter = when (id and 0x00ff0000) {
            0x00600000 -> "getFloatProperty"
            0x00200000 -> "getBooleanProperty"
            else       -> "getIntProperty"
        }
        return try {
            cpm.javaClass.getMethod(getter, Int::class.java, Int::class.java)
                .invoke(cpm, id, area)?.toString() ?: "null"
        } catch (e: Exception) {
            court(e)
        }
    }

    /** Lecture par le CarLampManager SAIC, comme l'écran Réglages d'origine (`getProperty(Class, id, zone)`). */
    private fun lireLamp(lamp: Any, id: Int): String = try {
        lamp.javaClass
            .getMethod("getProperty", Class::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            .invoke(lamp, java.lang.Integer::class.java, id, AREA_GLOBAL)?.toString() ?: "null"
    } catch (e: Exception) {
        court(e)
    }

    private fun appel(o: Any, methode: String): String = try {
        o.javaClass.getMethod(methode).invoke(o)?.toString() ?: "null"
    } catch (e: Exception) {
        court(e)
    }

    private fun systeme(ctx: Context?, cle: String): String {
        ctx ?: return "?"
        return runCatching { Settings.System.getString(ctx.contentResolver, cle) ?: "absent" }
            .getOrElse { court(it) }
    }

    private fun carManager(nom: String): Any? {
        val car = MG4Hardware.car() ?: return null
        return runCatching {
            car.javaClass.getMethod("getCarManager", String::class.java).invoke(car, nom)
        }.getOrNull()
    }

    private fun hex(id: Int) = Integer.toHexString(id)

    /** Message d'échec ramassé : le nom de l'exception suffit à distinguer absente de refusée. */
    private fun court(e: Throwable): String {
        val cause = (e.cause ?: e)
        return cause.javaClass.simpleName + (cause.message?.let { " ($it)" } ?: "")
    }
}
