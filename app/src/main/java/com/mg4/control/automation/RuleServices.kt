package com.mg4.control.automation

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.AudioManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ResultReceiver
import android.speech.tts.TextToSpeech
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.mg4.control.debug.AppLogger
import com.mg4.control.hardware.EnergyReader
import com.mg4.control.hardware.MG4Hardware
import com.mg4.control.model.RuleKeys
import com.mg4.control.model.RuleLogic
import com.mg4.control.model.RulePlace
import java.util.Locale

/**
 * Mes automatisations, étape 3 (2026-11-02) — ce dont le moteur [CustomRules] a besoin en plus :
 * lieux, variables, écritures propres aux automatisations ([RuleKeys]) et commandes d'Android.
 */

/** Les lieux enregistrés (« Maison », « Travail »…), pour les conditions de lieu et la navigation. */
object RulePlaces {
    private const val PREFS = "mg4_custom_rules"
    private const val KEY = "places_json"
    const val MAX = 20
    private val gson = Gson()
    private val type = object : TypeToken<List<RulePlace>>() {}.type

    fun all(ctx: Context): List<RulePlace> =
        runCatching { gson.fromJson<List<RulePlace>>(ctx.getSharedPreferences(PREFS, 0).getString(KEY, null), type) }
            .getOrNull().orEmpty().filterNotNull()

    fun get(ctx: Context, id: String?): RulePlace? = id?.let { i -> all(ctx).firstOrNull { it.id == i } }

    fun save(ctx: Context, places: List<RulePlace>) =
        ctx.getSharedPreferences(PREFS, 0).edit().putString(KEY, gson.toJson(places.take(MAX), type)).apply()
}

/** Les variables de l'utilisateur, gardées d'un démarrage à l'autre. Noms normalisés ([RuleLogic.variableName]). */
object RuleVariables {
    private const val PREFS = "mg4_rule_variables"

    fun all(ctx: Context): Map<String, String> =
        ctx.getSharedPreferences(PREFS, 0).all.mapNotNull { (k, v) -> (v as? String)?.let { k to it } }.toMap()

    fun set(ctx: Context, name: String, value: String) =
        ctx.getSharedPreferences(PREFS, 0).edit().putString(RuleLogic.variableName(name), value.trim().take(64)).apply()

    fun delete(ctx: Context, name: String) = ctx.getSharedPreferences(PREFS, 0).edit().remove(name).apply()

    /** Renomme [old] en [new] (valeur gardée) et dans toutes les automatisations ; faux si [new] existe déjà. */
    fun rename(ctx: Context, old: String, new: String): Boolean {
        val n = RuleLogic.variableName(new)
        if (n.isEmpty() || n == old) return false
        val p = ctx.getSharedPreferences(PREFS, 0)
        if (p.contains(n)) return false
        val v = p.getString(old, "") ?: ""
        p.edit().remove(old).putString(n, v).apply()
        CustomRuleStore.renameVariable(ctx, old, n)
        return true
    }
}

/**
 * Commandes et lectures d'Android (lot 2). L'appli tourne sous l'identité système
 * (`android.uid.system`) : Wi-Fi, Bluetooth et partage de connexion sont permis sans demande.
 * Chaque appel est sous garde — un firmware qui refuse renvoie faux, jamais une exception.
 */
object AndroidControls {

    private const val TAG = CustomRules.TAG
    private val principal = Handler(Looper.getMainLooper())

    // ── Wi-Fi ──
    private fun wifi(ctx: Context) = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager

    fun wifiOn(ctx: Context): Boolean? = runCatching { wifi(ctx)?.isWifiEnabled }.getOrNull()

    /** Nom du réseau Wi-Fi connecté, sans guillemets ; `null` si aucun ou illisible. */
    @Suppress("DEPRECATION")
    fun wifiSsid(ctx: Context): String? = runCatching {
        wifi(ctx)?.connectionInfo?.ssid?.trim('"')?.takeIf { it.isNotBlank() && it != "<unknown ssid>" }
    }.getOrNull()

    @Suppress("DEPRECATION")
    fun setWifi(ctx: Context, on: Boolean): Boolean = runCatching { wifi(ctx)?.setWifiEnabled(on) == true }
        .onFailure { AppLogger.w(TAG, "Wi-Fi → $on refusé : ${it.message}") }.getOrDefault(false)

    // ── Bluetooth ──
    @SuppressLint("MissingPermission")
    fun bluetoothOn(): Boolean? = runCatching { BluetoothAdapter.getDefaultAdapter()?.isEnabled }.getOrNull()

    @SuppressLint("MissingPermission")
    fun setBluetooth(on: Boolean): Boolean = runCatching {
        val a = BluetoothAdapter.getDefaultAdapter() ?: return false
        if (a.isEnabled == on) true else if (on) a.enable() else a.disable()
    }.onFailure { AppLogger.w(TAG, "Bluetooth → $on refusé : ${it.message}") }.getOrDefault(false)

    // ── Point d'accès (partage de connexion Wi-Fi) ──
    // Calqué sur DiAuto-MG4 (HotspotManager / SoftApStateReader, lus le 2026-11-02) : l'état par
    // `getWifiApState()` (13 = actif) ; déjà actif, on n'y touche pas (le reconfigurer le fait
    // tomber sur MG4) ; et le démarrage n'est cru que si le point d'accès MONTE vraiment.

    private const val AP_ACTIF = 13
    private const val AP_ATTENTE_MS = 10_000L

    /** Point d'accès actif ? `null` si la question n'a pas pu être posée (non « éteint »). */
    fun hotspotOn(ctx: Context): Boolean? = runCatching {
        wifi(ctx)?.let { (it.javaClass.getMethod("getWifiApState").invoke(it) as? Int)?.let { s -> s == AP_ACTIF } }
    }.getOrNull()

    /**
     * Allume ou coupe le partage de connexion Wi-Fi. Démarrage : le service de connectivité
     * (`IConnectivityManager.startTethering`, Android 9 — un `ResultReceiver` public au lieu du rappel
     * caché que DiAuto doit fabriquer), puis en secours `setWifiApEnabled` avec la configuration
     * ENREGISTRÉE (avec `null`, le point d'accès MG4 refuse ou démarre sans mot de passe). Vrai
     * seulement si l'état passe à « actif » dans les [AP_ATTENTE_MS]. Bloquant : hors fil principal.
     */
    fun setHotspot(ctx: Context, on: Boolean): Boolean {
        if (on && hotspotOn(ctx) == true) return true.also { AppLogger.i(TAG, "point d'accès déjà actif : laissé tel quel") }
        if (!on) {
            val coupe = connectivite("stopTethering", ctx) { cm -> cm.javaClass.getMethod("stopTethering", Int::class.javaPrimitiveType).invoke(cm, 0) }
                || legacyAp(ctx, false)
            return coupe
        }
        val demande = connectivite("startTethering", ctx) { _ ->
            val sm = Class.forName("android.os.ServiceManager")
            val binder = sm.getMethod("getService", String::class.java).invoke(null, Context.CONNECTIVITY_SERVICE)
            val stub = Class.forName("android.net.IConnectivityManager\$Stub")
            val icm = stub.getMethod("asInterface", android.os.IBinder::class.java).invoke(null, binder)
            val recepteur = object : ResultReceiver(principal) {
                override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
                    AppLogger.i(TAG, "point d'accès : réponse $resultCode (0 = démarré)")
                }
            }
            icm.javaClass.getMethod("startTethering", Int::class.javaPrimitiveType, ResultReceiver::class.java,
                Boolean::class.javaPrimitiveType, String::class.java).invoke(icm, 0, recepteur, false, ctx.packageName)
        }
        if (demande && attendreAp(ctx)) return true
        AppLogger.w(TAG, "point d'accès : pas monté par le service de connectivité, essai setWifiApEnabled")
        return legacyAp(ctx, true) && attendreAp(ctx)
    }

    private fun connectivite(nom: String, ctx: Context, appel: (Any) -> Unit): Boolean = runCatching {
        appel(ctx.getSystemService(Context.CONNECTIVITY_SERVICE) ?: return false)
        true
    }.onFailure { AppLogger.w(TAG, "point d'accès ($nom) refusé : ${(it.cause ?: it).javaClass.simpleName} ${(it.cause ?: it).message}") }
        .getOrDefault(false)

    @Suppress("DEPRECATION")
    private fun legacyAp(ctx: Context, on: Boolean): Boolean = runCatching {
        val wm = wifi(ctx) ?: return false
        val config = if (on) runCatching { wm.javaClass.getMethod("getWifiApConfiguration").invoke(wm) }.getOrNull() else null
        wm.javaClass.getMethod("setWifiApEnabled", android.net.wifi.WifiConfiguration::class.java, Boolean::class.javaPrimitiveType)
            .invoke(wm, config, on) as? Boolean == true
    }.onFailure { AppLogger.w(TAG, "point d'accès (setWifiApEnabled) refusé : ${(it.cause ?: it).message}") }
        .getOrDefault(false)

    /** Attend que le point d'accès soit monté : l'appel ne dit que « demande postée ». */
    private fun attendreAp(ctx: Context): Boolean {
        val fin = android.os.SystemClock.elapsedRealtime() + AP_ATTENTE_MS
        while (android.os.SystemClock.elapsedRealtime() < fin) {
            if (hotspotOn(ctx) == true) return true.also { AppLogger.i(TAG, "point d'accès monté") }
            Thread.sleep(500)
        }
        AppLogger.w(TAG, "point d'accès : toujours éteint après ${AP_ATTENTE_MS / 1000} s")
        return false
    }

    // ── Audio ──
    private fun audio(ctx: Context) = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    fun mediaPlaying(ctx: Context): Boolean? = runCatching { audio(ctx)?.isMusicActive }.getOrNull()

    fun inCall(ctx: Context): Boolean? = runCatching {
        audio(ctx)?.mode?.let { it == AudioManager.MODE_IN_CALL || it == AudioManager.MODE_IN_COMMUNICATION || it == AudioManager.MODE_RINGTONE }
    }.getOrNull()

    // ── Synthèse vocale ──
    @Volatile private var tts: TextToSpeech? = null
    @Volatile private var ttsPret = false

    /** Lit [texte] ; faux si aucun moteur de synthèse n'est installé (ou pas encore prêt). */
    fun speak(ctx: Context, texte: String): Boolean {
        if (texte.isBlank()) return false
        val existant = tts
        if (existant != null && ttsPret) return dire(existant, texte)
        principal.post {
            tts = TextToSpeech(ctx.applicationContext) { statut ->
                ttsPret = statut == TextToSpeech.SUCCESS
                AppLogger.i(TAG, "synthèse vocale : ${if (ttsPret) "prête" else "aucun moteur ($statut)"}")
                tts?.let { if (ttsPret) dire(it, texte) }
            }
        }
        // Premier usage : la réponse viendra du moteur ; on la tient pour faite.
        return true
    }

    private fun dire(t: TextToSpeech, texte: String): Boolean {
        runCatching { t.language = Locale.getDefault() }
        return t.speak(texte, TextToSpeech.QUEUE_ADD, null, "mg4-rule-${System.nanoTime()}") == TextToSpeech.SUCCESS
    }

    // ── Applis ──
    fun openApp(ctx: Context, paquet: String): Boolean = runCatching {
        val i = ctx.packageManager.getLaunchIntentForPackage(paquet) ?: return false
        ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    }.getOrDefault(false)

    /** Navigation vers un point : Google Maps / Waze s'ils comprennent `google.navigation:`, sinon `geo:`. */
    fun navigate(ctx: Context, lat: Double, lng: Double, nom: String): Boolean {
        val point = String.format(Locale.US, "%.6f,%.6f", lat, lng)
        for (uri in listOf("google.navigation:q=$point", "geo:$point?q=$point(${Uri.encode(nom)})")) {
            val ok = runCatching {
                ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                true
            }.getOrDefault(false)
            if (ok) return true
        }
        return false
    }

    // ── Position ──
    @Volatile private var derniere: Location? = null
    @Volatile private var suivi = false

    private val ecoute = object : LocationListener {
        override fun onLocationChanged(l: Location) { derniere = l }
        @Deprecated("API 28") override fun onStatusChanged(p: String?, s: Int, b: Bundle?) {}
        override fun onProviderEnabled(p: String) {}
        override fun onProviderDisabled(p: String) {}
    }

    /**
     * Les fournisseurs de position, dans l'ordre : `fused` d'abord. Sur ces voitures il est alimenté
     * par le GPS de la T-Box (FusedLocation modifié par SAIC) et répond en 0,2 s au READY, mais il
     * n'apparaît PAS dans la liste des fournisseurs d'Android — il faut le demander par son nom
     * (sonde de la luminosité auto, 2026-10-01 ; diagnostic du 2026-11-02 : `gps` sans aucune
     * position, `fused` avec une position fraîche). `gps` ensuite, `network` et `passive` en repli.
     */
    private val FOURNISSEURS = listOf("fused", LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)

    /**
     * Suivre la position tant qu'une automatisation en a besoin, sur `fused` et `gps`. Comme DiAuto :
     * au TEMPS seulement — Android combine temps ET distance, et toute distance minimale arrête un
     * écran de voiture garée après sa première position. Pas de test « fournisseur actif » : `fused`
     * répondrait non alors qu'il marche ; une demande refusée est seulement journalisée.
     */
    @SuppressLint("MissingPermission")
    fun followLocation(ctx: Context, on: Boolean) {
        if (on == suivi) return
        suivi = on
        principal.post {
            val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return@post
            if (!on) {
                runCatching { lm.removeUpdates(ecoute) }
                return@post
            }
            for (p in listOf("fused", LocationManager.GPS_PROVIDER)) {
                runCatching { lm.requestLocationUpdates(p, 10_000L, 0f, ecoute) }
                    .onFailure { AppLogger.w(TAG, "position : suivi $p refusé — ${it.message}") }
            }
        }
    }

    /**
     * Une position tout de suite : la dernière connue, sinon une position fraîche demandée à `fused`
     * puis `gps` (au plus [attenteMs]). [rappel] reçoit la position, ou `null`, sur le fil principal.
     */
    @SuppressLint("MissingPermission")
    fun locate(ctx: Context, attenteMs: Long = 8_000L, rappel: (Location?) -> Unit) {
        location(ctx)?.let { l -> principal.post { rappel(l) }; return }
        principal.post {
            val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            if (lm == null) return@post rappel(null)
            var fini = false
            val ecoutes = mutableListOf<LocationListener>()
            fun terminer(l: Location?) {
                if (fini) return
                fini = true
                ecoutes.forEach { runCatching { lm.removeUpdates(it) } }
                if (l != null) derniere = l
                AppLogger.i(TAG, "position fraîche : ${if (l == null) "aucune après ${attenteMs / 1000} s" else "reçue (${l.provider})"}")
                rappel(l)
            }
            for (p in listOf("fused", LocationManager.GPS_PROVIDER)) {
                val e = object : LocationListener {
                    override fun onLocationChanged(l: Location) {
                        if (!(l.latitude == 0.0 && l.longitude == 0.0)) terminer(l)
                    }
                    @Deprecated("API 28") override fun onStatusChanged(p: String?, s: Int, b: Bundle?) {}
                    override fun onProviderEnabled(p: String) {}
                    override fun onProviderDisabled(p: String) {}
                }
                @Suppress("DEPRECATION")
                runCatching { lm.requestSingleUpdate(p, e, Looper.getMainLooper()); ecoutes += e }
                    .onFailure { AppLogger.w(TAG, "position : demande $p refusée — ${it.message}") }
            }
            principal.postDelayed({ terminer(null) }, attenteMs)
        }
    }

    /**
     * La position connue la plus récente, toutes sources confondues, QUEL QUE SOIT son âge (comme le
     * `geofenceFix` de DiAuto) : une voiture garée n'a pas de nouvelle position, mais la dernière dit
     * toujours où elle est. Une position 0,0 (pas de fixe) est écartée.
     */
    @SuppressLint("MissingPermission")
    fun location(ctx: Context): Location? {
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        val connues = listOfNotNull(derniere) + FOURNISSEURS.mapNotNull { p -> runCatching { lm?.getLastKnownLocation(p) }.getOrNull() }
        return connues.filterNot { it.latitude == 0.0 && it.longitude == 0.0 }.maxByOrNull { it.time }
    }
}

/** Écritures propres aux automatisations ([RuleKeys]) : les setters de MG4Control, avec leur verrou de conduite. */
object RuleWrites {
    fun set(ctx: Context, key: String, value: String): Boolean {
        val on = value == "1" || value.equals("true", true)
        val n = value.trim().toIntOrNull()
        return when (key) {
            RuleKeys.BRIGHTNESS -> n != null && MG4Hardware.setScreenBrightnessPercent(n.coerceIn(0, 100))
            RuleKeys.VOLUME -> n != null && MG4Hardware.setMediaVolume(n.coerceIn(0, MG4Hardware.getMediaVolumeMax().coerceAtLeast(0)))
            RuleKeys.VOLUME_STEP -> {
                val actuel = MG4Hardware.getMediaVolume()
                n != null && actuel >= 0 && MG4Hardware.setMediaVolume((actuel + n).coerceIn(0, MG4Hardware.getMediaVolumeMax().coerceAtLeast(0)))
            }
            RuleKeys.ONE_PEDAL -> MG4Hardware.setOnePedal(on)
            RuleKeys.ENERGY_SAVING -> MG4Hardware.setEnergySavingMode(on)
            RuleKeys.AEB -> MG4Hardware.setAebEnabled(on)
            RuleKeys.TSR -> MG4Hardware.setTsrMode(on)
            RuleKeys.ESC -> MG4Hardware.hasDrowsinessAndEsc() && MG4Hardware.setEsc(on)
            RuleKeys.DROWSINESS -> MG4Hardware.hasDrowsinessAndEsc() && MG4Hardware.setDrowsiness(on)
            RuleKeys.OVERSPEED_ALARM -> MG4Hardware.setOverspeedAlarm(on)
            RuleKeys.SPEED_LIMIT_TONE -> MG4Hardware.setSpeedLimitTone(on)
            RuleKeys.HVAC_ECO -> MG4Hardware.hasClimateControl() && MG4Hardware.setClimateEco(on)
            RuleKeys.AIR_FLOW -> n != null && MG4Hardware.hasClimateControl() && MG4Hardware.setClimateAirFlow(n.coerceIn(0, 6))
            RuleKeys.AUTO_VOLUME -> n != null && MG4Hardware.hasClimateControl() && MG4Hardware.setClimateAutoVolume(n.coerceIn(0, 2))
            RuleKeys.AUTO_HIGH_BEAM -> MG4Hardware.hasAutoHighBeam() && MG4Hardware.setAutoHighBeam(on)
            RuleKeys.BATTERY_HEAT -> MG4Hardware.hasBatteryHeating() && MG4Hardware.setBatteryHeating(on)
            RuleKeys.CHARGE_LIMIT -> n != null && EnergyReader.setChargeLimitPercent(n.coerceIn(40, 100))
            RuleKeys.WIFI -> AndroidControls.setWifi(ctx, on)
            RuleKeys.BLUETOOTH -> AndroidControls.setBluetooth(on)
            RuleKeys.HOTSPOT -> AndroidControls.setHotspot(ctx, on)
            else -> false
        }
    }
}
