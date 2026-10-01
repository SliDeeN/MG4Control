package com.mg4.control.automation

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import com.mg4.control.debug.AppLogger
import com.mg4.control.hardware.EnergyReader
import com.mg4.control.hardware.ReadyWatcher
import com.mg4.control.hardware.VehicleWriteGate
import com.mg4.control.profile.ProfileApplier
import com.mg4.control.profile.ProfileManager
import com.mg4.control.service.ProfileConfirmOverlay
import com.mg4.control.util.GarageMode

/**
 * Automatisation « profil selon la batterie » (issue #112) : surveille le niveau de batterie
 * pendant le trajet et applique un profil quand il passe sous un seuil. Les règles de décision
 * sont dans [BatteryTrigger] ; ici, seulement le rythme et les effets.
 *
 * **Indépendante de la chaîne de démarrage** (manuel → température → Bluetooth → défaut) : la
 * batterie baisse en roulant, elle n'a rien à faire dans un choix qui ne se fait qu'au contact.
 * Au démarrage, la première vérification attend [STARTUP_DELAY_MS] que cette chaîne ait fini —
 * popup de la température compris —, puis la remplace si la batterie est déjà sous le seuil.
 *
 * **Un choix manuel ne la bloque pas** : franchir le seuil est un nouvel événement, c'est
 * précisément pour lui que l'automatisation a été activée. En mode confirmation, le conducteur
 * peut toujours refuser.
 *
 * Coût : une propriété lue toutes les [TICK_MS], seulement sous contact.
 */
object BatteryAutomation {

    private const val TAG = "MG4_SOC_AUTO"

    /** Une batterie ne perd pas un pour cent en trente secondes, même sur autoroute. */
    private const val TICK_MS = 30_000L

    /** Laisse la chaîne de démarrage finir, popup de 8 s de la température compris. */
    private const val STARTUP_DELAY_MS = 12_000L

    private val thread = HandlerThread("mg4-soc-auto").apply { start() }
    private val worker = Handler(thread.looper)
    private val trigger = BatteryTrigger()

    @Volatile
    private var context: Context? = null

    @Volatile
    private var started = false

    private val readyListener = ReadyWatcher.Listener { ready, _ ->
        worker.post {
            worker.removeCallbacks(tick)
            if (ready) {
                // Chaque démarrage ouvre un nouvel épisode : batterie encore basse ce matin,
                // profil économie à nouveau.
                trigger.rearm()
                worker.postDelayed(tick, STARTUP_DELAY_MS)
            }
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            runCatching { check() }
                .onFailure { AppLogger.w(TAG, "vérification manquée : ${it.javaClass.simpleName} ${it.message}") }
            if (ReadyWatcher.ready == true) worker.postDelayed(this, TICK_MS)
        }
    }

    /** Appelé une fois par le service. La configuration est relue à chaque vérification. */
    fun start(ctx: Context) {
        if (started) return
        started = true
        context = ctx.applicationContext
        ReadyWatcher.add(readyListener)
    }

    /** Sur [worker]. */
    private fun check() {
        val ctx = context ?: return
        val cfg = BatteryAutomationSettings.read(ctx)
        // Désactivée ou Mode Garage : le déclencheur n'est pas consulté, donc reste armé —
        // l'activer en cours de route, batterie déjà basse, agit au relevé suivant.
        if (!cfg.enabled || GarageMode.isOn(ctx)) return

        val soc = EnergyReader.soc()
        val etaitEnAttente = trigger.isPending
        val agir = trigger.onSoc(soc, cfg.threshold, canAct = VehicleWriteGate.isAllowedNow())
        if (!agir) {
            if (!etaitEnAttente && trigger.isPending) {
                AppLogger.i(TAG, "batterie $soc % < seuil ${cfg.threshold} % mais réglages bloqués " +
                    "par la sécurité conduite — attente de la prochaine occasion")
            }
            return
        }

        val profile = cfg.profileId.takeIf { it.isNotEmpty() }?.let { ProfileManager(ctx).getById(it) }
        if (profile == null) {
            AppLogger.w(TAG, "batterie $soc % < seuil ${cfg.threshold} % — aucun profil configuré, rien n'est fait")
            return
        }
        // `soc` est forcément lu ici : le déclencheur n'agit jamais sur une lecture ratée.
        val niveau = soc ?: return
        if (cfg.autoExecute) {
            AppLogger.i(TAG, "batterie $niveau % < seuil ${cfg.threshold} % → application '${profile.name}'")
            ProfileApplier.apply(profile, autoStart = true) { ok ->
                AppLogger.i(TAG, "profil '${profile.name}' appliqué — ok=$ok")
            }
        } else {
            AppLogger.i(TAG, "batterie $niveau % < seuil ${cfg.threshold} % → popup '${profile.name}'")
            ProfileConfirmOverlay.showBattery(
                context     = ctx,
                profile     = profile,
                threshold   = cfg.threshold,
                currentSoc  = niveau,
                onConfirmed = {
                    ProfileApplier.apply(profile, autoStart = true) { ok ->
                        AppLogger.i(TAG, "OUI — profil '${profile.name}' appliqué — ok=$ok")
                    }
                },
                onDeclined  = { AppLogger.i(TAG, "NON ou délai écoulé — profil '${profile.name}' non appliqué") },
            )
        }
    }
}
