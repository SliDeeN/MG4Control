package com.mg4.control.service

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Parcel
import android.os.ParcelFileDescriptor
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Relais du panneau SAIC après la touche HVAC. La commande de clim reste au véhicule. */
internal class SaicHvacPanel(context: Context) {
    companion object {
        private const val TAG = "MG4_HVAC_SAIC"
        private const val PREFS = "mg4_settings"
        private const val KEY = "replace_saic_hvac_panel"
        const val KEYCODE = 0x120
        private const val TITRE = "hvac page"
        private const val DELAI_MAX_MS = 1_500L
        private const val LECTURE_MAX_MS = 200L
        private const val TX_FERMER = 2
        private const val SYSTEM_UI = "com.android.systemui"
        private val SERVICE by lazy { ComponentName(SYSTEM_UI, "$SYSTEM_UI.StartActivityService") }
        private val INTERFACES = setOf(
            "$SYSTEM_UI.IStartActivityService", // SWI133, SWI165 et certaines versions de SWI68
            "com.saicmotor.sdk.external.IPageService", // SWI68 : closeHvac
        )
        private val ENTETE = Regex("""(?m)^\h*Window #\d+\h+""")
        private val FENETRE = Regex("""Window\{\S+\s+u\d+ hvac page\}:""")
        private val CADRE = Regex("""(?m)^\h*mFrame=\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]""")
        private val PAQUET = Regex("""\bpackage=com\.android\.systemui(?:\s|$)""")

        fun supported(): Boolean = when (FirmwareInfo.getGeneration()) {
            FirmwareInfo.Gen.SWI133, FirmwareInfo.Gen.SWI165, FirmwareInfo.Gen.SWI68 -> true
            else -> false
        }

        fun isOn(ctx: Context): Boolean = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, false)

        fun setOn(ctx: Context, on: Boolean) {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, on).apply()
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
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var remplacement: Job? = null
    private var appuiLong = false

    fun stop() = scope.cancel()

    /** Reçoit uniquement la touche native, via le receiver protégé du service. Fil principal. */
    fun touche(down: Boolean, long: Boolean) {
        if (down) {
            appuiLong = long
            return
        }
        val ignorer = appuiLong || long
        appuiLong = false
        if (ignorer || remplacement?.isActive == true || !autorise()) return
        remplacement = scope.launch {
            val abouti = withTimeoutOrNull(DELAI_MAX_MS) { relayer() }
            if (abouti == null) AppLogger.w(TAG, "Panneau SAIC : remplacement expiré")
        }
    }

    private fun autorise() = isOn(app) && !GarageMode.isOn(app) && supported()

    private suspend fun relayer(): Boolean {
        // Le receiver de SystemUI traite le même événement. On attend que sa vue apparaisse.
        for (essai in 0 until 4) {
            if (!autorise()) return false
            delay(if (essai == 0) 65 else 95)
            val visible = withContext(Dispatchers.IO) { panneauVisible() } ?: return false
            if (visible) return remplacer()
        }
        return false
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
    private fun panneauVisible(): Boolean? = try {
        val wm = Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java)
            .invoke(null, "window") as? IBinder
        if (wm == null) null else {
            val (lecture, ecriture) = ParcelFileDescriptor.createPipe()
            lecture.use { entree ->
                ecriture.use { wm.dumpAsync(it.fileDescriptor, arrayOf("-a", TITRE)) }
                lireDiagnostic(entree)?.let { dump ->
                    if ("Permission Denial" in dump) {
                        AppLogger.w(TAG, "Lecture des fenêtres de SystemUI refusée")
                        null
                    } else estVisible(dump)
                }
            }
        }
    } catch (e: Exception) {
        AppLogger.w(TAG, "Lecture du panneau SAIC impossible : ${e.javaClass.simpleName} ${e.message}")
        null
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
