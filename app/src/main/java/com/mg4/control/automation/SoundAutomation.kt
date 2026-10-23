package com.mg4.control.automation

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import com.mg4.control.debug.AppLogger
import com.mg4.control.hardware.MG4Hardware
import com.mg4.control.hardware.ReadyWatcher
import com.mg4.control.model.SoundAutomationRules
import com.mg4.control.model.SoundEvent
import com.mg4.control.model.SoundGuard
import com.mg4.control.model.SoundSlot
import com.mg4.control.profile.ActiveProfile
import com.mg4.control.util.GarageMode
import java.io.File

/**
 * Sons d'automatisation (issue #161, 2026-10-23) : un son importé joué à un événement choisi —
 * démarrage de MG4Control, entrée ou sortie de READY, passage en P ou en D, ouverture d'une porte
 * avant (SWI132, SWI133), profil appliqué.
 *
 * Volume : celui de la VOITURE (volume média), réglé juste avant le son puis rendu à la fin —
 * seulement s'il n'a pas bougé entre-temps (décision du 2026-10-23). Le son passe donc par le canal
 * média, avec une demande de focus audio transitoire pour mettre la musique en pause. Garde-fou
 * ([SoundGuard]) : un événement qui arrive pendant un son est ignoré. Rien en Mode Garage.
 *
 * Tout se passe sur le fil `mg4-sound` ; le boîtier est tenu éveillé le temps du son (un son de
 * sortie de READY ne doit pas être coupé par la mise en veille). Journal : [TAG].
 */
object SoundAutomation {

    const val TAG = "MG4_SOUND"

    /** Le son du démarrage attend que la pile audio de la voiture soit prête. */
    private const val DEMARRAGE_MS = 4_000L
    private const val RAPPORT_MS = 700L
    /** Fin forcée si le lecteur ne signale rien : durée du son plus cette marge. */
    private const val SECURITE_MS = 3_000L

    private val fil: Handler by lazy { Handler(HandlerThread("mg4-sound").also { it.start() }.looper) }
    private val principal = Handler(Looper.getMainLooper())

    @Volatile private var app: Context? = null

    // ── Fil mg4-sound ──
    private var abonne = false
    private val garde = SoundGuard()
    private var lecteur: MediaPlayer? = null
    private var focus: AudioFocusRequest? = null
    private var veille: PowerManager.WakeLock? = null
    private var volumeAvant = -1
    private var volumeMis = -1
    private var finForcee: Runnable? = null
    private var dernierRapport: Int? = null

    /** Son en cours (0 ou 1), pour le bouton Écouter / Arrêter de la carte. */
    @Volatile var enLecture: Int? = null
        private set

    /** Prévenu sur le fil principal quand un son commence ou s'arrête (carte de l'onglet). */
    @Volatile var surChangement: (() -> Unit)? = null

    /** Démarrage du service : surveillance si l'option est active, puis le son du démarrage. */
    fun start(context: Context) {
        app = context.applicationContext
        fil.post {
            actualiser()
            if (abonne) fil.postDelayed({ declencherIci(SoundEvent.APP_START, "démarrage de MG4Control") }, DEMARRAGE_MS)
        }
    }

    /** L'interrupteur ou les événements ont changé (carte de l'onglet Automatisation). */
    fun refresh(context: Context) {
        app = context.applicationContext
        fil.post { actualiser() }
    }

    /** Un profil vient d'être appliqué ([com.mg4.control.profile.ProfileApplier]). */
    fun profilApplique(profileId: String) {
        fil.post { declencherIci(SoundEvent.PROFILE, "profil appliqué", profileId) }
    }

    /** Bouton Écouter : le son [index] tout de suite, au volume réglé, sans condition. */
    fun ecouter(context: Context, index: Int) {
        app = context.applicationContext
        fil.post {
            val ctx = app ?: return@post
            terminer("nouvelle écoute")
            jouer(ctx, index, SoundAutomationSettings.slot(ctx, index), "écoute", ecoute = true)
        }
    }

    /** Bouton Arrêter. */
    fun arreter() {
        fil.post { terminer("arrêt demandé") }
    }

    // ── Surveillance (fil mg4-sound) ─────────────────────────────────────────

    private val ready = ReadyWatcher.Listener { estReady, premiere ->
        if (premiere) return@Listener
        fil.post { declencherIci(if (estReady) SoundEvent.READY_ON else SoundEvent.READY_OFF, "READY") }
    }

    private val porte: () -> Unit = { fil.post { declencherIci(SoundEvent.FRONT_DOOR, "porte avant ouverte") } }

    private fun actualiser() {
        val ctx = app ?: return
        val voulu = SoundAutomationSettings.isEnabled(ctx)
        if (voulu == abonne) return
        abonne = voulu
        if (voulu) {
            ReadyWatcher.add(ready)
            MG4Hardware.addFrontDoorOpenListener(porte)
            dernierRapport = null
            fil.post(rapport)
            AppLogger.i(TAG, "surveillance active (${SoundAutomationSettings.slots(ctx).count { it.hasFile }} son(s) importé(s))")
        } else {
            ReadyWatcher.remove(ready)
            MG4Hardware.removeFrontDoorOpenListener(porte)
            fil.removeCallbacks(rapport)
            terminer("option désactivée")
            AppLogger.i(TAG, "surveillance arrêtée")
        }
    }

    /** Rapport relu tant qu'un son est réglé sur P ou D ; un changement vers P ou D déclenche. */
    private val rapport = object : Runnable {
        override fun run() {
            if (!abonne) return
            val ctx = app
            val utile = ctx != null && SoundAutomationSettings.slots(ctx).any {
                it.hasFile && (SoundEvent.PARK in it.events || SoundEvent.DRIVE in it.events)
            }
            if (utile) {
                val g = MG4Hardware.readGearRaw()
                if (g != null && g != dernierRapport) {
                    AppLogger.i(TAG, "rapport ${dernierRapport ?: "?"} → $g")
                    SoundAutomationRules.gearEvent(dernierRapport, g)?.let { declencherIci(it, "rapport") }
                    dernierRapport = g
                }
            } else {
                dernierRapport = null
            }
            fil.postDelayed(this, RAPPORT_MS)
        }
    }

    private fun declencherIci(event: SoundEvent, origine: String, profileId: String? = null) {
        val ctx = app ?: return
        if (!abonne || !SoundAutomationSettings.isEnabled(ctx)) return
        val sons = SoundAutomationSettings.slots(ctx)
        val index = SoundAutomationRules.slotFor(event, sons) ?: return
        if (GarageMode.isOn(ctx)) {
            AppLogger.i(TAG, "${event.key} : Mode Garage, aucun son")
            return
        }
        val profil = profileId ?: ActiveProfile.id(ctx)
        if (!SoundAutomationRules.profileAllowed(SoundAutomationSettings.profiles(ctx), profil)) {
            AppLogger.i(TAG, "${event.key} : profil ${profil ?: "inconnu"} exclu, aucun son")
            return
        }
        jouer(ctx, index, sons[index], "${event.key} ($origine)", ecoute = false)
    }

    // ── Lecture (fil mg4-sound) ──────────────────────────────────────────────

    private fun jouer(ctx: Context, index: Int, s: SoundSlot, raison: String, ecoute: Boolean) {
        val chemin = s.path?.takeIf { File(it).canRead() }
        if (chemin == null) {
            AppLogger.w(TAG, "son ${index + 1} ($raison) : aucun fichier lisible")
            return
        }
        val maintenant = SystemClock.elapsedRealtime()
        if (!garde.claim(maintenant, s.durationMs)) {
            AppLogger.i(TAG, "son ${index + 1} ($raison) ignoré : un son joue encore (${garde.remainingMs(maintenant)} ms)")
            return
        }
        prendreVeille(ctx, s.durationMs)
        demanderFocus(ctx)
        // Volume de la voiture pour ce son ; rendu à la fin s'il n'a pas bougé entre-temps.
        val max = MG4Hardware.getMediaVolumeMax()
        val avant = MG4Hardware.getMediaVolume()
        val cible = SoundAutomationRules.volumeFor(s, max)
        if (max > 0 && avant >= 0 && avant != cible && MG4Hardware.setMediaVolume(cible)) {
            volumeAvant = avant
            volumeMis = cible
        }
        try {
            lecteur = MediaPlayer().apply {
                setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build())
                setDataSource(chemin)
                setOnCompletionListener { fil.post { terminer("fin du son") } }
                setOnErrorListener { _, quoi, extra ->
                    fil.post { terminer("erreur du lecteur $quoi/$extra") }
                    true
                }
                prepare()
                start()
            }
        } catch (e: Exception) {
            AppLogger.w(TAG, "son ${index + 1} ($raison) : lecture impossible — ${e.javaClass.simpleName}: ${e.message}")
            terminer("lecture impossible")
            return
        }
        enLecture = index
        finForcee = Runnable { terminer("délai dépassé") }.also { fil.postDelayed(it, s.durationMs + SECURITE_MS) }
        AppLogger.i(TAG, "son ${index + 1} « ${s.title} » (${s.durationMs} ms) — $raison — volume " +
            (if (volumeMis >= 0) "$avant → $volumeMis" else "inchangé ($avant)") + if (ecoute) " — écoute" else "")
        prevenir()
    }

    /** Fin du son, quelle qu'en soit la raison : volume rendu, focus et veille relâchés. Idempotent. */
    private fun terminer(raison: String) {
        finForcee?.let { fil.removeCallbacks(it) }
        finForcee = null
        val joue = lecteur != null
        lecteur?.let { p ->
            runCatching { if (p.isPlaying) p.stop() }
            runCatching { p.release() }
        }
        lecteur = null
        if (volumeMis >= 0) {
            val actuel = MG4Hardware.getMediaVolume()
            if (SoundAutomationRules.shouldRestore(actuel, volumeMis)) {
                MG4Hardware.setMediaVolume(volumeAvant)
                AppLogger.i(TAG, "volume rendu : $volumeMis → $volumeAvant")
            } else {
                AppLogger.i(TAG, "volume changé pendant le son ($actuel), laissé tel quel")
            }
        }
        volumeAvant = -1
        volumeMis = -1
        rendreFocus()
        garde.release()
        relacherVeille()
        if (joue) AppLogger.i(TAG, "fin : $raison")
        if (enLecture != null) {
            enLecture = null
            prevenir()
        }
    }

    private fun prevenir() {
        principal.post { surChangement?.invoke() }
    }

    private fun demanderFocus(ctx: Context) {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        val f = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build())
            .setOnAudioFocusChangeListener { }
            .build()
        val r = runCatching { am.requestAudioFocus(f) }.getOrNull()
        focus = f
        AppLogger.i(TAG, "focus audio demandé → ${r ?: "erreur"}")
    }

    private fun rendreFocus() {
        val f = focus ?: return
        focus = null
        val am = app?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        runCatching { am.abandonAudioFocusRequest(f) }
    }

    private fun prendreVeille(ctx: Context, dureeMs: Long) {
        relacherVeille()
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
        veille = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "mg4control:sound").apply {
            setReferenceCounted(false)
            acquire(dureeMs + SECURITE_MS * 2)
        }
    }

    private fun relacherVeille() {
        runCatching { veille?.takeIf { it.isHeld }?.release() }
        veille = null
    }
}
