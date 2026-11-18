package com.mg4.control.service

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.os.IBinder
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import com.mg4.control.debug.AppLogger
import com.mg4.control.util.FirmwareInfo
import com.mg4.control.util.GarageMode
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Relais du panneau SAIC lorsqu'il apparaît. La commande de clim reste au véhicule. */
internal class SaicHvacPanel(context: Context) {
    companion object {
        private const val TAG = "MG4_HVAC_SAIC"
        private const val PREFS = "mg4_settings"
        private const val KEY = "replace_saic_hvac_panel"
        const val KEYCODE = 0x120
        private const val TITRE = "hvac page"
        private const val DELAI_MAX_MS = 1_500L
        private const val LECTURE_MAX_MS = 400L
        private const val SONDE_MS = 400L
        private const val ATTENTE_MS = 1_000L
        private const val TX_FERMER = 2
        private const val SYSTEM_UI = "com.android.systemui"
        private val SERVICE by lazy { ComponentName(SYSTEM_UI, "$SYSTEM_UI.StartActivityService") }
        private val INTERFACES = setOf(
            "$SYSTEM_UI.IStartActivityService", // SWI133, SWI165 et certaines versions de SWI68
            "com.saicmotor.sdk.external.IPageService", // SWI68 : closeHvac
        )
        private val ENTETE = Regex("""(?m)^\h*Window #\d+\h+""")
        private val FENETRE = Regex("""Window\{\S+\s+u\d+ ${Regex.escape(TITRE)}\}:""")
        private val CADRE = Regex("""(?m)^\h*mFrame=\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]""")
        private val PAQUET = Regex("""\bpackage=com\.android\.systemui(?:\s|$)""")

        fun supported(): Boolean = when (FirmwareInfo.getGeneration()) {
            FirmwareInfo.Gen.SWI133, FirmwareInfo.Gen.SWI165, FirmwareInfo.Gen.SWI68 -> true
            else -> false
        }

        fun isOn(ctx: Context): Boolean = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, false)

        fun setOn(ctx: Context, on: Boolean) {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, on).apply()
            AppLogger.i(TAG, "Remplacement du panneau SAIC : ${if (on) "activé" else "désactivé"}")
        }

        /** SystemUI garde la vue attachée et la fait glisser au-dessus de l'écran pour la cacher. */
        internal fun estVisible(dump: String): Boolean = ENTETE.split(dump).any { bloc ->
            if (!FENETRE.matches(bloc.lineSequence().firstOrNull()?.trim().orEmpty())) return@any false
            if (!PAQUET.containsMatchIn(bloc) || "mDisplayId=0 " !in bloc ||
                "mHasSurface=true " !in bloc || "isReadyForDisplay()=true " !in bloc ||
                "mViewVisibility=0x0 " !in bloc || "mObscured=true" in bloc ||
                "mPolicyVisibility=false" in bloc) return@any false
            val cadre = CADRE.find(bloc) ?: return@any false
            val (gauche, haut, droite, bas) = cadre.groupValues.drop(1).map { it.toIntOrNull() ?: return@any false }
            droite > maxOf(gauche, 0) && bas > maxOf(haut, 0)
        }
    }

    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val ecran = app.getSystemService(PowerManager::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val appui = Channel<Unit>(Channel.CONFLATED)
    private var surveillance: Job? = null
    private var appuiLong = false
    private val changement = SharedPreferences.OnSharedPreferenceChangeListener { _, cle ->
        if (cle == KEY) actualiser()
    }
    @Volatile private var windowManager: IBinder? = null

    fun demarrer() {
        prefs.registerOnSharedPreferenceChangeListener(changement)
        actualiser()
    }

    fun stop() {
        prefs.unregisterOnSharedPreferenceChangeListener(changement)
        scope.cancel()
    }

    fun actualiser() {
        if (!autorise()) {
            surveillance?.cancel()
            surveillance = null
            appui.tryReceive()
            appuiLong = false
            return
        }
        if (surveillance?.isActive == true) return
        AppLogger.i(TAG, "Surveillance du panneau SAIC active (${FirmwareInfo.getGeneration()})")
        surveillance = scope.launch { observer() }
    }

    /** La touche accélère la prochaine lecture. Le bandeau et les firmwares sans signal passent par la sonde. */
    fun touche(down: Boolean, long: Boolean) {
        if (down) {
            appuiLong = long
            return
        }
        val ignorer = appuiLong || long
        appuiLong = false
        if (!ignorer && surveillance?.isActive == true) {
            AppLogger.i(TAG, "Touche clim relâchée")
            appui.trySend(Unit)
        }
    }

    private fun autorise() = isOn(app) && !GarageMode.isOn(app) && supported()

    /** Une tentative par ouverture confirmée, même si plusieurs sondes voient la même fenêtre. */
    internal class Ouvertures {
        private var visible = false

        fun nouvelle(valeur: Boolean?): Boolean {
            if (valeur == null) return false
            val ouverture = valeur && !visible
            visible = valeur
            return ouverture
        }
    }

    private suspend fun observer() {
        val ouvertures = Ouvertures()
        var derniereErreur: String? = null
        var premiereLecture = true
        while (currentCoroutineContext().isActive && autorise()) {
            if (ecran?.isInteractive != true) {
                ouvertures.nouvelle(false)
                appui.tryReceive()
                delay(ATTENTE_MS)
                continue
            }
            val lecture = withContext(Dispatchers.IO) { runCatching { panneauVisible() } }
            val erreur = lecture.exceptionOrNull()?.let { it.cause?.message ?: it.message ?: it.javaClass.simpleName }
            if (erreur != null && erreur != derniereErreur) AppLogger.w(TAG, "Lecture du panneau SAIC : $erreur")
            val visible = lecture.getOrNull()
            if (visible != null && (premiereLecture || derniereErreur != null)) {
                AppLogger.i(TAG, "Lecture du panneau SAIC disponible")
                premiereLecture = false
            }
            derniereErreur = erreur
            if (ouvertures.nouvelle(visible) && autorise()) {
                AppLogger.i(TAG, "Panneau SAIC visible, tentative de remplacement")
                when (withTimeoutOrNull(DELAI_MAX_MS) { remplacer() }) {
                    null -> AppLogger.w(TAG, "Panneau SAIC : remplacement expiré")
                    false -> AppLogger.w(TAG, "Panneau SAIC conservé, remplacement indisponible")
                    true -> AppLogger.i(TAG, "Panneau SAIC relayé par MG4Control")
                }
            }
            // La demande physique et la mise en page de SystemUI sont asynchrones.
            val raccourci = withTimeoutOrNull(if (visible == null) ATTENTE_MS else SONDE_MS) { appui.receive() }
            if (raccourci != null) delay(65L)
        }
    }

    private suspend fun remplacer(): Boolean {
        val connexion = CompletableDeferred<IBinder?>()
        val service = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) { connexion.complete(binder) }
            override fun onServiceDisconnected(name: ComponentName) { connexion.complete(null) }
            override fun onBindingDied(name: ComponentName) { connexion.complete(null) }
            override fun onNullBinding(name: ComponentName) { connexion.complete(null) }
        }
        val lie = runCatching { app.bindService(Intent().setComponent(SERVICE), service, Context.BIND_AUTO_CREATE) }
            .getOrDefault(false)
        if (!lie) {
            AppLogger.w(TAG, "Service de fermeture SAIC injoignable")
            return false
        }
        try {
            val binder = connexion.await() ?: return false
            val interfaceSaic = withContext(Dispatchers.IO) { runCatching { binder.interfaceDescriptor }.getOrNull() }
            if (interfaceSaic == null || interfaceSaic !in INTERFACES) {
                AppLogger.w(TAG, "Interface de fermeture SAIC inconnue : $interfaceSaic")
                return false
            }
            return HvacPopupOverlay.remplacerPanneauSaic(app, ::autorise) {
                withContext(Dispatchers.IO) {
                    autorise() && fermer(binder, interfaceSaic)
                }
            }
        } finally {
            runCatching { app.unbindService(service) }
        }
    }

    /** Les deux interfaces connues ferment le panneau avec la transaction synchrone 2. */
    private fun fermer(binder: IBinder, interfaceSaic: String): Boolean {
        val message = Parcel.obtain()
        val reponse = Parcel.obtain()
        return try {
            message.writeInterfaceToken(interfaceSaic)
            check(binder.transact(TX_FERMER, message, reponse, 0))
            reponse.readException()
            AppLogger.i(TAG, "Fermeture du panneau SAIC demandée ($interfaceSaic)")
            true
        } catch (e: Exception) {
            AppLogger.w(TAG, "Fermeture du panneau SAIC refusée : ${e.javaClass.simpleName} ${e.message}")
            false
        } finally {
            message.recycle()
            reponse.recycle()
        }
    }

    /** Diagnostic ciblé de WindowManager, borné et lu hors du fil principal. */
    private fun panneauVisible(): Boolean {
        val wm = windowManager?.takeIf { it.isBinderAlive } ?: checkNotNull(
            Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java)
                .invoke(null, "window") as? IBinder
        ) { "WindowManager injoignable" }.also { windowManager = it }
        val (lecture, ecriture) = ParcelFileDescriptor.createPipe()
        val dump = lecture.use { entree ->
            ecriture.use { wm.dumpAsync(it.fileDescriptor, arrayOf("-a", TITRE)) }
            checkNotNull(lireDiagnostic(entree)) { "délai ou taille limite de la lecture WindowManager" }
        }
        check("Permission Denial" !in dump) { "lecture de WindowManager refusée" }
        check("WINDOW MANAGER WINDOWS" in dump || "Bad window command, or no windows match: $TITRE" in dump) {
            "format de diagnostic WindowManager inconnu"
        }
        return estVisible(dump)
    }

    private fun lireDiagnostic(entree: ParcelFileDescriptor): String? {
        val fin = SystemClock.elapsedRealtime() + LECTURE_MAX_MS
        val fd = entree.fileDescriptor
        val evenement = StructPollfd().apply {
            this.fd = fd
            events = OsConstants.POLLIN.toShort()
        }
        val sondes = arrayOf(evenement)
        val octets = ByteArrayOutputStream()
        val bloc = ByteArray(4096)
        while (octets.size() < 64 * 1024) {
            val reste = fin - SystemClock.elapsedRealtime()
            if (reste <= 0 || Os.poll(sondes, reste.toInt()) == 0) return null
            val taille = Os.read(fd, bloc, 0, bloc.size)
            if (taille == 0) return octets.toString(Charsets.UTF_8.name())
            octets.write(bloc, 0, taille)
        }
        return null
    }
}
