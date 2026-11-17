package com.mg4.control.automation

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import com.mg4.control.debug.AppLogger
import com.mg4.control.hardware.EnergyReader
import com.mg4.control.hardware.MG4Hardware
import com.mg4.control.model.AbrpAccount
import com.mg4.control.model.AbrpLogPolicy
import com.mg4.control.model.AbrpSample
import com.mg4.control.model.ChargeType
import com.mg4.control.model.RuleSignal
import com.mg4.control.model.maskSecret
import com.mg4.control.service.ChargeMonitor
import com.mg4.control.stats.StatsStore
import com.mg4.control.util.GarageMode
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Envoi continu vers ABRP (issue #188, option B) : lancé par l'action « ABRP : envoi continu vers
 * un compte » de Mes automatisations, arrêté par « ABRP : arrêter l'envoi ». Un seul compte à la
 * fois : en démarrer un autre remplace le premier.
 *
 * Un fil à lui relève la voiture toutes les [TIC_MS] et décide d'envoyer selon [AbrpStore.cadence] :
 * en roulant, en charge, à l'arrêt ; un passage en P, un début ou une fin de charge partent tout de
 * suite. Pas de file d'attente : sans réseau, le relevé est perdu (ABRP ne veut que du direct).
 * Mode Garage : l'envoi s'arrête.
 */
object AbrpStream {

    const val TAG = "MG4_ABRP"
    private const val URL_SEND = "https://api.iternio.com/1/tlm/send"
    /** Lecture seule : le test des identifiants ne doit JAMAIS écrire un faux relevé dans ABRP. */
    private const val URL_CHECK = "https://api.iternio.com/1/tlm/get_next_charge"
    private const val TIC_MS = 5_000L
    private const val DELAI_MS = 8_000

    /** État montré dans la fenêtre des comptes. */
    data class Status(
        val accountId: String? = null,
        val running: Boolean = false,
        val lastAtMs: Long? = null,
        val lastCode: Int? = null,
        /** Ce qui est parti (`soc=62 power=41.2 …`) ou la raison de l'échec — jamais d'identifiant. */
        val lastText: String? = null,
        val sent: Int = 0,
        val failed: Int = 0,
    )

    @Volatile var status = Status()
        private set
    @Volatile var onChange: (() -> Unit)? = null

    private var fil: HandlerThread? = null
    private var h: Handler? = null
    private val principal = Handler(Looper.getMainLooper())
    @Volatile private var compte: AbrpAccount? = null
    private var dernierEnvoiMs = 0L
    private var dernierEtat: Pair<Boolean?, Boolean?>? = null
    @Volatile private var position: Location? = null
    private var appCtx: Context? = null
    // Journal espacé (AbrpLogPolicy) : dernière ligne écrite, issue du dernier envoi, envois tus depuis.
    private var journalMs: Long? = null
    private var dernierOk: Boolean? = null
    private var derniereErreur: String? = null
    private var tus = 0
    private var echecsSuite = 0

    /** Démarre (ou bascule) l'envoi vers le compte [id] ; faux si impossible (hors ligne, compte ou clé absents, Mode Garage). */
    @Synchronized
    fun start(ctx: Context, id: String): Boolean {
        val app = ctx.applicationContext
        if (!AbrpStore.available) return false
        if (GarageMode.isOn(app)) { AppLogger.i(TAG, "envoi refusé : Mode Garage"); return false }
        val a = AbrpStore.account(app, id) ?: run { AppLogger.w(TAG, "envoi refusé : compte introuvable"); return false }
        if (a.token.isBlank() || AbrpStore.keyFor(a).isBlank()) {
            AppLogger.w(TAG, "envoi refusé : « ${a.name} » sans jeton ou sans clé API")
            return false
        }
        appCtx = app
        val deja = compte?.id == a.id && fil != null
        compte = a
        if (deja) return true
        AppLogger.i(TAG, "envoi continu vers « ${a.name} » (${maskSecret(a.token)}), cadence ${AbrpStore.cadence(app)}")
        dernierEnvoiMs = 0L
        dernierEtat = null
        journalMs = null; dernierOk = null; derniereErreur = null; tus = 0; echecsSuite = 0
        status =Status(accountId = a.id, running = true)
        if (fil == null) {
            fil = HandlerThread(TAG).also { it.start() }
            h = Handler(fil!!.looper).also { it.post(tic) }
        } else {
            h?.removeCallbacks(tic); h?.post(tic)
        }
        suivrePosition(app, a.sends(com.mg4.control.model.AbrpField.POSITION))
        notifier()
        return true
    }

    @Synchronized
    fun stop(ctx: Context?, raison: String = "action") {
        if (fil == null) return
        AppLogger.i(TAG, "envoi arrêté ($raison) — ${status.sent} relevés envoyés, ${status.failed} en échec")
        h?.removeCallbacksAndMessages(null)
        fil?.quitSafely()
        fil = null; h = null; compte = null
        (ctx ?: appCtx)?.let { suivrePosition(it, false) }
        status = status.copy(running = false)
        notifier()
    }

    fun isRunning(): Boolean = fil != null

    private val tic = object : Runnable {
        override fun run() {
            val ctx = appCtx
            val a = compte
            if (ctx == null || a == null) return
            if (GarageMode.isOn(ctx)) { principal.post { stop(ctx, "Mode Garage") }; return }
            runCatching { tour(ctx, a) }.onFailure { AppLogger.w(TAG, "relevé : ${it.javaClass.simpleName} ${it.message ?: ""}") }
            h?.postDelayed(this, TIC_MS)
        }
    }

    private fun tour(ctx: Context, a: AbrpAccount) {
        val parked = RuleSignal.gearText(MG4Hardware.readGearRaw())?.let { it == RuleSignal.GEAR_PARK }
        val charging = EnergyReader.charging()
        val etat = parked to charging
        val change = dernierEtat != null && dernierEtat != etat
        dernierEtat = etat
        val maintenant = System.currentTimeMillis()
        if (!AbrpStore.cadence(ctx).due(maintenant, dernierEnvoiMs, parked, charging, change)) return
        dernierEnvoiMs = maintenant
        val e = releve(ctx, parked, charging)
        if (!e.canSend) {
            AppLogger.w(TAG, "relevé non envoyé : niveau de batterie illisible")
            return
        }
        envoyer(a, e, change)
    }

    private fun releve(ctx: Context, parked: Boolean?, charging: Boolean?): AbrpSample {
        val s = EnergyReader.read()
        val stats = runCatching { StatsStore(ctx).settings() }.getOrNull()
        val charge = ChargeMonitor.etat?.takeIf { it.enCharge }
        val clim = runCatching { if (MG4Hardware.hasClimateControl()) MG4Hardware.getClimateState() else null }.getOrNull()
        val l = position ?: AndroidControls.location(ctx)
        return AbrpSample(
            utcSeconds = System.currentTimeMillis() / 1000L,
            soc = s.socPercent,
            speedKmh = s.speedKmh,
            powerKw = EnergyReader.dischargeKw(),
            charging = charging ?: s.charging,
            dcfc = s.chargeType?.let { it == ChargeType.DC },
            parked = parked,
            outsideTempC = s.outsideTempC,
            rangeKm = s.rangeKm,
            odometerKm = s.odometerKm,
            capacityKwh = stats?.effectiveCapacityKwh,
            kwhCharged = charge?.energieKwh,
            hvacSetpointC = clim?.takeIf { it.powerOn == true }?.tempC?.toFloat(),
            lat = l?.latitude,
            lon = l?.longitude,
            elevationM = l?.takeIf { it.hasAltitude() }?.altitude,
            heading = l?.takeIf { it.hasBearing() }?.bearing,
        )
    }

    private fun envoyer(a: AbrpAccount, e: AbrpSample, change: Boolean) {
        val tlm = e.json(a)
        val corps = "api_key=" + enc(AbrpStore.keyFor(a)) + "&token=" + enc(a.token) + "&tlm=" + enc(tlm)
        val (code, texte) = runCatching { post(corps) }.getOrElse { -1 to (it.javaClass.simpleName + " " + (it.message ?: "")).trim() }
        val ok = code == 200
        // Ce qui est parti, sans les identifiants (ils ne sont pas dans tlm).
        val resume = tlm.removePrefix("{").removeSuffix("}").replace("\"", "").replace(",", " ").replace(":", "=")
        val st = status
        status = st.copy(lastAtMs = System.currentTimeMillis(), lastCode = code,
            lastText = if (ok) resume else erreur(texte),
            sent = st.sent + if (ok) 1 else 0, failed = st.failed + if (ok) 0 else 1)
        journaliser(a, ok, code, resume, if (ok) null else erreur(texte), change)
        notifier()
    }

    private fun journaliser(a: AbrpAccount, ok: Boolean, code: Int, resume: String, err: String?, change: Boolean) {
        val maintenant = SystemClock.elapsedRealtime()
        val echecsAvant = echecsSuite
        echecsSuite = if (ok) 0 else echecsSuite + 1
        val ecrire = AbrpLogPolicy.shouldLog(maintenant, journalMs, ok, dernierOk, err, derniereErreur, change)
        dernierOk = ok
        if (!ecrire) { tus++; return }
        val extra = buildList {
            if (tus > 0) add("+$tus non journalisés")
            if (ok && echecsAvant > 0) add("rétabli après $echecsAvant échecs")
            if (!ok && echecsSuite > 1) add("échec n° $echecsSuite d'affilée")
        }.joinToString(", ").let { if (it.isEmpty()) "" else " ($it)" }
        if (ok) AppLogger.i(TAG, "« ${a.name} » HTTP 200$extra · $resume")
        else AppLogger.w(TAG, "« ${a.name} » ${if (code < 0) "réseau" else "HTTP $code"}$extra · $err")
        journalMs = maintenant
        derniereErreur = err
        tus = 0
    }

    private fun post(corps: String): Pair<Int, String> {
        val c = URL(URL_SEND).openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.connectTimeout = DELAI_MS
            c.readTimeout = DELAI_MS
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            val b = corps.toByteArray(Charsets.UTF_8)
            c.setFixedLengthStreamingMode(b.size)
            c.outputStream.use { o: OutputStream -> o.write(b) }
            val code = c.responseCode
            return code to lireCorps(c, code)
        } finally {
            c.disconnect()
        }
    }

    /**
     * Teste un compte SANS rien écrire : la lecture « prochaine recharge » d'ABRP (comme
     * EVABRPUploader). Elle n'accepte le jeton que dans l'adresse ; la clé passe en en-tête.
     * [rappel] reçoit le code HTTP (-1 = réseau) et la raison, sur le fil principal.
     */
    fun check(a: AbrpAccount, rappel: (Int, String) -> Unit) {
        Thread {
            val r = runCatching {
                val c = URL("$URL_CHECK?token=${enc(a.token)}").openConnection() as HttpURLConnection
                try {
                    c.requestMethod = "GET"
                    c.connectTimeout = DELAI_MS
                    c.readTimeout = DELAI_MS
                    c.setRequestProperty("Authorization", "APIKEY " + AbrpStore.keyFor(a))
                    val code = c.responseCode
                    code to erreur(lireCorps(c, code))
                } finally {
                    c.disconnect()
                }
            }.getOrElse { -1 to (it.javaClass.simpleName + " " + (it.message ?: "")).trim() }
            AppLogger.i(TAG, "test de « ${a.name} » (${maskSecret(a.token)}) : ${if (r.first < 0) "réseau" else "HTTP ${r.first}"}")
            principal.post { rappel(r.first, r.second) }
        }.apply { name = "$TAG-test" }.start()
    }

    private fun lireCorps(c: HttpURLConnection, code: Int): String = runCatching {
        (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
    }.getOrDefault("")

    /** La raison donnée par ABRP (champs error / message), courte, sans rien d'autre du corps. */
    fun erreur(corps: String): String {
        val m = Regex("\"(error|message|status)\"\\s*:\\s*\"([^\"]{1,200})\"").find(corps)
        return (m?.groupValues?.get(2) ?: corps.take(80)).replace(Regex("\\p{Cntrl}"), " ").trim()
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun notifier() = principal.post { onChange?.invoke() }

    // ── Position : `fused` (GPS de la T-Box) et `gps`, au temps seulement ─────
    private val ecoute = object : LocationListener {
        override fun onLocationChanged(l: Location) { if (!(l.latitude == 0.0 && l.longitude == 0.0)) position = l }
        @Deprecated("API 28") override fun onStatusChanged(p: String?, s: Int, b: Bundle?) {}
        override fun onProviderEnabled(p: String) {}
        override fun onProviderDisabled(p: String) {}
    }
    private var suivi = false

    @SuppressLint("MissingPermission")
    private fun suivrePosition(ctx: Context, on: Boolean) {
        if (on == suivi) return
        suivi = on
        principal.post {
            val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return@post
            if (!on) { runCatching { lm.removeUpdates(ecoute) }; position = null; return@post }
            for (p in listOf("fused", LocationManager.GPS_PROVIDER)) {
                runCatching { lm.requestLocationUpdates(p, 10_000L, 0f, ecoute) }
                    .onFailure { AppLogger.w(TAG, "position : suivi $p refusé — ${it.message}") }
            }
        }
    }
}
