package com.mg4.control.profile

import com.mg4.control.debug.AppLogger
import com.mg4.control.hardware.MG4Hardware
import com.mg4.control.hardware.MG4Hardware.Swi68Mode
import com.mg4.control.model.DrivingProfile
import com.mg4.control.util.FirmwareInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

object ProfileApplier {

    private const val TAG = "MG4_PROFILE"

    /**
     * Scope unique et supervisé pour toutes les applications de profil. Remplace
     * GlobalScope : une application qui échoue n'emporte pas les suivantes.
     */
    private val applierScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Sérialise les applications de profil. Bouton volant, connexion Bluetooth et passage
     * en READY peuvent se déclencher simultanément ; sans ce verrou les écritures
     * s'entrelacent et les séquences AEB multi-étapes (setFcwAutoBrakeMode puis
     * setFcwState) sont coupées par le profil concurrent, laissant l'ADAS dans un état
     * indéterminé.
     */
    private val applyMutex = Mutex()

    /** Application en cours : annulée dès qu'une nouvelle est demandée. */
    @Volatile
    private var currentApply: Job? = null

    /**
     * Id du dernier profil appliqué MANUELLEMENT (popup volant, bouton dans l'app, raccourci
     * APPLY_PROFILE) — c.-à-d. avec autoStart=false. Permet au passage en READY de respecter le
     * choix explicite de l'utilisateur au lieu de ré-appliquer le profil par défaut.
     * En mémoire : réinitialisé à l'extinction de la voiture (IGNITION_OFF) ou au redémarrage du process.
     */
    @Volatile
    var lastManualProfileId: String? = null

    /**
     * Applique tous les paramètres de [profile] au véhicule de façon asynchrone.
     * Les opérations HVAC (siège/volant) sont bloquantes (polling jusqu'à 7s),
     * exécutées sur le dispatcher IO.
     *
     * @param autoStart true si l'application est déclenchée automatiquement au démarrage
     *   (IGNITION/boot). Active la passe de vérification des alertes sonores (SWI132/SWI133) :
     *   au démarrage à froid, le firmware peut ré-asserter OVERSPEED/SPEED_TONE après nos
     *   écritures — on relit et on réécrit en cas d'écart. Inutile en application manuelle.
     */
    fun apply(profile: DrivingProfile, autoStart: Boolean = false, onComplete: ((Boolean) -> Unit)? = null) {
        AppLogger.i(TAG, "Application du profil : ${profile.name} (autoStart=$autoStart)")

        // Profil ACTIF : toutes sources confondues, celle-ci comprise. C'est ce que lisent les
        // raccourcis associés à un profil — voir [ActiveProfile], et surtout la distinction avec
        // [lastManualProfileId] juste en dessous, qui répond à une question différente.
        ActiveProfile.set(MG4Hardware.appContext(), profile.id)

        // Application manuelle (popup volant / app / raccourci) → on mémorise le choix de l'utilisateur
        // pour que le passage en READY le respecte au lieu de ré-appliquer le profil par défaut.
        if (!autoStart) {
            lastManualProfileId = profile.id
            AppLogger.i(TAG, "  Choix manuel mémorisé : ${profile.name} (id=${profile.id})")
        }

        val previous = currentApply
        currentApply = applierScope.launch {
            // Annule l'application précédente avant de prendre le verrou : la nouvelle
            // demande fait autorité, on ne fait pas la queue derrière une séquence obsolète.
            previous?.cancelAndJoin()
            applyMutex.withLock { applyLocked(profile, autoStart, onComplete) }
        }
    }

    /**
     * Corps de l'application, exécuté sous [applyMutex] : une seule séquence d'écritures
     * véhicule à la fois.
     */
    private fun applyLocked(profile: DrivingProfile, autoStart: Boolean, onComplete: ((Boolean) -> Unit)?) {
            var ok = true

            // Mode de conduite (rapide — binder call)
            val dmOk = MG4Hardware.setDriveMode(profile.driveMode)
            AppLogger.i(TAG, "  DriveMode=${profile.driveMode.label} → $dmOk")
            ok = ok && dmOk

            // Niveau de régénération (rapide — binder call)
            val rlOk = MG4Hardware.setRegenLevel(profile.regenLevel)
            AppLogger.i(TAG, "  RegenLevel=${profile.regenLevel.label} → $rlOk")
            ok = ok && rlOk

            // Volant + Sièges chauffants — uniquement SWI133 et SWI68 (SWI69/SWI131 n'ont pas ces équipements)
            if (FirmwareInfo.hasHeatFeatures()) {
                // Chauffages décochés dans le profil = on NE TOUCHE À RIEN (ni allumer, ni éteindre),
                // sinon appliquer un profil écraserait un réglage que le conducteur vient de faire.
                if (profile.appliesSteeringHeat) {
                    val shOk = MG4Hardware.setSteeringHeat(profile.steeringHeat)
                    AppLogger.i(TAG, "  SteeringHeat=${profile.steeringHeat} → $shOk")
                } else {
                    AppLogger.i(TAG, "  SteeringHeat non pris en compte par ce profil — inchangé")
                }
                if (profile.appliesSeatHeat) {
                    val slOk = MG4Hardware.setSeatHeatLeft(profile.seatHeatLeft)
                    AppLogger.i(TAG, "  SeatHeatLeft=${profile.seatHeatLeft} → $slOk")
                    val srOk = MG4Hardware.setSeatHeatRight(profile.seatHeatRight)
                    AppLogger.i(TAG, "  SeatHeatRight=${profile.seatHeatRight} → $srOk")
                } else {
                    AppLogger.i(TAG, "  SeatHeat non pris en compte par ce profil — inchangé")
                }
            }

            AppLogger.i(TAG, "Profil '${profile.name}' Katman1 terminé — ok=$ok")
            onComplete?.invoke(ok)

            // ADAS (Katman4) — appliqué dès que le service est prêt
            MG4Hardware.whenKatman4Ready {
                AppLogger.i(TAG, "  Application ADAS pour profil '${profile.name}'")
                if (FirmwareInfo.getGeneration() == FirmwareInfo.Gen.SWI132) {
                    // ── SWI132 ──────────────────────────────────────────────────────────
                    //
                    // SWI132 utilise CarVehicleSettingClient pour l'ADAS (ACC/TJA)
                    // mais le binder direct IVehicleSettingService pour les alertes sonores.
                    // → setOverspeedAlarm() et setSpeedLimitTone() envoient les TX binder corrects
                    //   (0x128 et 0x12a) sans passer par VehicleSettingManager.

                    // TSR (SLIF) via VSM — appliqué en premier comme SWI133
                    val tsrOk = MG4Hardware.setTsrMode(profile.tsrEnabled)
                    AppLogger.i(TAG, "  TsrEnabled=${profile.tsrEnabled} → $tsrOk")

                    // Alertes sonores via VSM
                    // Le firmware SWI132 remet overspeed et speedTone à ON ~400ms après l'activation
                    // du TSR (même comportement que SWI133). setTsrMode() retourne immédiatement sans
                    // attendre cette réinitialisation. Si on écrit les alertes trop tôt, le firmware
                    // les écrase ensuite. On attend 450ms pour laisser le firmware terminer sa
                    // réinitialisation avant d'appliquer les valeurs du profil.
                    if (profile.tsrEnabled) {
                        try { Thread.sleep(450) } catch (_: InterruptedException) {}
                    }
                    // Délai de 150ms entre les deux écritures : le middleware traite les propriétés
                    // dans une file avec debounce — deux écritures trop rapides font que seule la
                    // dernière est validée. 150ms garantit que la première est traitée.
                    val oaOk = MG4Hardware.setOverspeedAlarm(profile.overspeedAlarm)
                    AppLogger.i(TAG, "  OverspeedAlarm=${profile.overspeedAlarm} → $oaOk")
                    try { Thread.sleep(150) } catch (_: InterruptedException) {}
                    val stOk = MG4Hardware.setSpeedLimitTone(profile.speedLimitTone)
                    AppLogger.i(TAG, "  SpeedLimitTone=${profile.speedLimitTone} → $stOk")

                    // Mode ACC/TJA via CarVehicleSettingClient (setAccTjaState).
                    // SHWA (ancien codage limiteur) n'est plus un mode ACC/TJA → ramené à Off ;
                    // le limiteur est géré séparément via setSasMode ci-dessous.
                    val cruiseMode = if (profile.swi68AdasMode == Swi68Mode.SHWA) Swi68Mode.OFF else profile.swi68AdasMode
                    val adOk = MG4Hardware.setAccTjaMode(cruiseMode)
                    AppLogger.i(TAG, "  AdasMode=0x${cruiseMode.toString(16)} → $adOk")
                    // Limiteur de vitesse (SAS) — appliqué uniquement si le profil l'a configuré.
                    // Profils créés avant cette fonction (swi132LimiterConfigured=false) → non touché.
                    if (profile.swi132LimiterConfigured) {
                        MG4Hardware.setSpeedLimiterMode(profile.swi132SasMode)
                        AppLogger.i(TAG, "  SasMode=${profile.swi132SasMode} (0=Off 2=Manuel 3=Intelligent)")
                    }
                    applyAeb(profile.aebEnabled, profile.aebMode, profile.aebSensitivity)
                    applySafety(profile)

                    // Économie d'énergie — via CarVehicleSettingClient (setEnduranceMode), même path que SWI69
                    val esOk = MG4Hardware.setEnergySavingMode(profile.energySaving)
                    AppLogger.i(TAG, "  EnergySaving=${profile.energySaving} → $esOk")

                } else if (FirmwareInfo.isVsmBased()) {
                    // ── SWI68/SWI69/SWI131/SWI165 ──────────────────────────────────────
                    //
                    // Le TSR est appliqué EN PREMIER : setTsrMode() bloque 400 ms en interne
                    // et restaure soundWarning depuis les préférences. On l'appelle en premier
                    // pour pouvoir ensuite écraser l'alerte sonore avec la valeur du profil.
                    val tsrOk = MG4Hardware.setTsrMode(profile.tsrEnabled)
                    AppLogger.i(TAG, "  TsrEnabled=${profile.tsrEnabled} → $tsrOk")

                    // Alerte sonore — appliquée APRÈS le TSR pour écraser sa restauration interne
                    val swOk = MG4Hardware.setSoundWarning(profile.soundWarning)
                    AppLogger.i(TAG, "  SoundWarning=${profile.soundWarning} → $swOk")

                    // Mode ACC/TJA — SHWA (ancien codage limiteur) ramené à Off (limiteur géré à part)
                    val cruiseMode = if (profile.swi68AdasMode == Swi68Mode.SHWA) Swi68Mode.OFF else profile.swi68AdasMode
                    val adOk = MG4Hardware.setAccTjaMode(cruiseMode)
                    AppLogger.i(TAG, "  AdasMode=0x${cruiseMode.toString(16)} → $adOk")
                    // Limiteur de vitesse — appliqué uniquement si le profil l'a configuré.
                    // (SWI69/SWI131 → setSasMode ; SWI68/SWI165 → setSpeedAsstMode, dispatch interne)
                    if (profile.swi132LimiterConfigured) {
                        MG4Hardware.setSpeedLimiterMode(profile.swi132SasMode)
                        AppLogger.i(TAG, "  LimiterMode=${profile.swi132SasMode} (0=Off 2=Manuel 3=Intelligent)")
                    }
                    applyAeb(profile.aebEnabled, profile.aebMode, profile.aebSensitivity)
                    applySafety(profile)

                    // Économie d'énergie — firmwares VSM hors SWI132 (SWI68/SWI69/SWI131/SWI165)
                    val esOk = MG4Hardware.setEnergySavingMode(profile.energySaving)
                    AppLogger.i(TAG, "  EnergySaving=${profile.energySaving} → $esOk")
                } else {
                    // ── SWI133/UNKNOWN ──────────────────────────────────────────────────
                    //
                    // Même logique : activer le TSR ré-active OVERSPEED_ALARM et SPEED_LIMIT_TONE.
                    // setTsrMode() restaure depuis les préférences — on l'appelle en premier
                    // puis on écrase avec les valeurs du profil.
                    if (FirmwareInfo.getGeneration() == FirmwareInfo.Gen.SWI133) {
                        val tsrOk = MG4Hardware.setTsrMode(profile.tsrEnabled)
                        AppLogger.i(TAG, "  TsrEnabled=${profile.tsrEnabled} → $tsrOk")
                    }

                    // Alertes vitesse — appliquées APRÈS le TSR
                    // Délai de 150ms entre les deux écritures : le middleware véhicule (VPM) traite
                    // les propriétés dans une file avec debounce — deux écritures trop rapides font
                    // que seule la dernière est validée. 150ms garantit que la première est traitée.
                    val oaOk = MG4Hardware.setOverspeedAlarm(profile.overspeedAlarm)
                    AppLogger.i(TAG, "  OverspeedAlarm=${profile.overspeedAlarm} → $oaOk")
                    try { Thread.sleep(150) } catch (_: InterruptedException) {}
                    val stOk = MG4Hardware.setSpeedLimitTone(profile.speedLimitTone)
                    AppLogger.i(TAG, "  SpeedLimitTone=${profile.speedLimitTone} → $stOk")

                    val adOk = MG4Hardware.setMixedIntelligentDrive(profile.adasMode)
                    AppLogger.i(TAG, "  AdasMode=${profile.adasMode} → $adOk")
                    applyAeb(profile.aebEnabled, profile.aebMode, profile.aebSensitivity)
                    applySafety(profile)

                    // Économie d'énergie — SWI133 via VPM
                    if (FirmwareInfo.getGeneration() == FirmwareInfo.Gen.SWI133) {
                        val esOk = MG4Hardware.setEnergySavingMode(profile.energySaving)
                        AppLogger.i(TAG, "  EnergySaving=${profile.energySaving} → $esOk")
                    }
                }
                // ELK — commun à tous les firmwares connus
                applyElk(profile.elkMode, profile.elkSensitivity, profile.lasAudibleWarning, profile.lasVibrationReminder)

                // ── Passe de vérification ADAS (auto-démarrage uniquement) ────────────
                // Au démarrage à froid, le firmware peut ré-asserter certains réglages APRÈS
                // notre écriture (alertes survitesse/ton ~400ms après le SLIF/TSR ; ELK remis à
                // sa valeur par défaut). On relit après stabilisation et on réécrit en cas d'écart.
                // Concerne SWI132/SWI133 (2 alertes distinctes + ELK).
                val gen = FirmwareInfo.getGeneration()
                if (autoStart && (gen == FirmwareInfo.Gen.SWI133 || gen == FirmwareInfo.Gen.SWI132)) {
                    verifyAdasWithRetry(profile)
                }
            }
    }

    /**
     * Passe de vérification post-application (auto-démarrage, SWI132/SWI133). Attend la fin de
     * la fenêtre de ré-assertion firmware puis relit/réécrit en cas d'écart :
     *   - alertes sonores survitesse + ton
     *   - ELK (assistant de sortie de voie) — le firmware le remet à sa valeur par défaut au boot
     */
    private fun verifyAdasWithRetry(profile: DrivingProfile) {
        AppLogger.i(TAG, "  [VERIFY] Vérification ADAS (auto-démarrage) — stabilisation 500ms")
        try { Thread.sleep(500) } catch (_: InterruptedException) {}
        verifyOneAlert("OverspeedAlarm", profile.overspeedAlarm,
            { MG4Hardware.isOverspeedAlarmOn() }, { MG4Hardware.setOverspeedAlarm(it) })
        verifyOneAlert("SpeedLimitTone", profile.speedLimitTone,
            { MG4Hardware.isSpeedLimitToneOn() }, { MG4Hardware.setSpeedLimitTone(it) })
        verifyElk(profile)
    }

    /**
     * Vérifie le mode ELK (assistant de sortie de voie) après stabilisation et le réécrit s'il a
     * dérivé (réactivation auto par le firmware). Ne fait rien si le profil ne configure pas l'ELK
     * (elkMode=0). Réapplique la config ELK complète (mode + sensibilité + sonore/vibration SWI132).
     */
    private fun verifyElk(profile: DrivingProfile) {
        if (profile.elkMode == 0) return   // ELK non configuré dans ce profil → ne pas toucher
        repeat(3) { i ->
            val actual = MG4Hardware.getElkMode()
            if (actual == profile.elkMode) {
                AppLogger.i(TAG, "  [VERIFY] ElkMode conforme (lu=$actual" +
                    if (i > 0) ", après $i correction(s))" else ")")
                return
            }
            AppLogger.w(TAG, "  [VERIFY] ElkMode ÉCART (lu=$actual, attendu=${profile.elkMode}) → réécriture (tentative ${i + 1}/3)")
            applyElk(profile.elkMode, profile.elkSensitivity, profile.lasAudibleWarning, profile.lasVibrationReminder)
            try { Thread.sleep(300) } catch (_: InterruptedException) {}
        }
        val finalVal = MG4Hardware.getElkMode()
        if (finalVal == profile.elkMode)
            AppLogger.i(TAG, "  [VERIFY] ElkMode finalement conforme (lu=$finalVal)")
        else
            AppLogger.w(TAG, "  [VERIFY] ElkMode ÉCHEC après 3 tentatives (lu=$finalVal, attendu=${profile.elkMode})")
    }

    /**
     * Relit une alerte ; si elle diffère de la valeur voulue, la réécrit et réessaie.
     * Jusqu'à 3 tentatives espacées de 300ms pour couvrir un écrasement firmware tardif.
     */
    private fun verifyOneAlert(name: String, desired: Boolean, read: () -> Boolean, write: (Boolean) -> Boolean) {
        repeat(3) { i ->
            val actual = read()
            if (actual == desired) {
                AppLogger.i(TAG, "  [VERIFY] $name conforme (lu=$actual" +
                    if (i > 0) ", après $i correction(s))" else ")")
                return
            }
            AppLogger.w(TAG, "  [VERIFY] $name ÉCART (lu=$actual, attendu=$desired) → réécriture (tentative ${i + 1}/3)")
            write(desired)
            try { Thread.sleep(300) } catch (_: InterruptedException) {}
        }
        val finalVal = read()
        if (finalVal == desired)
            AppLogger.i(TAG, "  [VERIFY] $name finalement conforme (lu=$finalVal)")
        else
            AppLogger.w(TAG, "  [VERIFY] $name ÉCHEC après 3 tentatives (lu=$finalVal, attendu=$desired)")
    }

    /**
     * Applique les réglages ELK (assistant de sortie de voie) du profil — tous firmwares.
     * Si elkMode=0 (valeur par défaut — profil créé avant l'ajout de l'ELK),
     * on ne touche pas aux réglages ELK pour éviter une modification involontaire.
     */
    /**
     * ESC, avertissement de somnolence et sensibilité de son alerte.
     *
     * Les valeurs viennent des ACCESSEURS du profil, pas des champs bruts : un profil enregistré
     * avant la fonctionnalité a ses trois champs à null et doit recevoir les défauts que la
     * voiture rétablit d'elle-même au démarrage (ESC ON, somnolence ON, sensibilité Standard).
     * Lire les champs directement appliquerait `false`, donc un ESC désactivé — l'inverse.
     *
     * La sensibilité est écrite avant le commutateur : sur un système éteint, régler la
     * sensibilité d'abord évite une alerte au niveau précédent entre les deux appels.
     */
    private fun applySafety(profile: DrivingProfile) {
        if (!MG4Hardware.hasDrowsinessAndEsc()) return

        // ⚠️ On lit les champs BRUTS (nullables), pas les accesseurs.
        //
        // Les accesseurs rendent « ON » pour un profil antérieur à la fonctionnalité, ce qui
        // paraissait sûr — forcer un organe de sécurité à ON ne peut pas nuire. C'est faux ici :
        // l'écriture ESC est une BASCULE pilotée par une relecture. Si la propriété rend une
        // valeur fausse au démarrage (0 alors que l'ESC est en réalité actif), viser « ON »
        // fait écrire 1, donc INVERSE l'état et désactive l'ESC.
        //
        // Un profil qui n'a jamais configuré ces réglages ne doit donc rien écrire du tout —
        // même principe que le garde-fou de [applyAeb], qui existait déjà et que j'avais omis.
        if (profile.escEnabled == null &&
            profile.drowsinessEnabled == null &&
            profile.drowsinessSensitivity == null) {
            AppLogger.i(TAG, "  ESC/somnolence — non configurés dans ce profil, skip " +
                "(évite toute écriture involontaire sur un organe de sécurité)")
            return
        }

        // ⚠️ Hors thread principal OBLIGATOIREMENT : whenKatman4Ready republie ses écouteurs
        // via Handler(Looper.getMainLooper()), donc ce code s'exécute sur le thread UI quand le
        // service était déjà prêt — et setEsc attend ~1 s pour confirmer l'état. Sans ce
        // basculement, l'écran gèlerait, et seulement dans certains cas de timing.
        applierScope.launch {
            profile.drowsinessSensitivity?.let {
                AppLogger.i(TAG, "  DrowsinessSensitivity=$it → ${MG4Hardware.setDrowsinessSensitivity(it)}")
            }
            profile.drowsinessEnabled?.let {
                AppLogger.i(TAG, "  Drowsiness=$it → ${MG4Hardware.setDrowsiness(it)}")
            }
            profile.escEnabled?.let {
                AppLogger.i(TAG, "  Esc=$it → ${MG4Hardware.setEsc(it)}")
            }
        }
    }

    private fun applyElk(elkMode: Int, elkSensitivity: Int, lasAudibleWarning: Boolean = true, lasVibrationReminder: Boolean = true) {
        if (elkMode == 0) {
            AppLogger.i(TAG, "  ELK — valeurs par défaut, skip (évite modification involontaire)")
            return
        }
        val modeOk = MG4Hardware.setElkMode(elkMode)
        AppLogger.i(TAG, "  ElkMode=$elkMode → $modeOk")
        if (elkMode != MG4Hardware.ElkMode.OFF && elkSensitivity > 0) {
            val senOk = MG4Hardware.setElkSensitivity(elkSensitivity)
            AppLogger.i(TAG, "  ElkSensitivity=$elkSensitivity → $senOk")
        }
        // SWI132 : Alerte sonore + Vibration appliquées après délai
        // Le firmware peut réinitialiser ces valeurs lors du changement de mode ELK.
        // 300ms garantit que le firmware a terminé sa réinitialisation interne.
        if (FirmwareInfo.getGeneration() == FirmwareInfo.Gen.SWI132 && elkMode != MG4Hardware.ElkMode.OFF) {
            try { Thread.sleep(300) } catch (_: InterruptedException) {}
            val soundOk = MG4Hardware.setLasWarningSound(lasAudibleWarning)
            AppLogger.i(TAG, "  LasAudibleWarning=$lasAudibleWarning → $soundOk")
            val vibrOk = MG4Hardware.setLasWarningVibration(lasVibrationReminder)
            AppLogger.i(TAG, "  LasVibrationReminder=$lasVibrationReminder → $vibrOk")
        }
    }

    /**
     * Applique les réglages AEB du profil.
     * Si aebEnabled=false ET aebMode=1 ET aebSensitivity=0 (valeurs par défaut),
     * on ne touche pas à l'état AEB de la voiture pour éviter une désactivation involontaire.
     */
    private fun applyAeb(aebEnabled: Boolean, aebMode: Int, aebSensitivity: Int = 0) {
        val isDefault = !aebEnabled && aebMode == 1 && aebSensitivity == 0
        if (isDefault) {
            AppLogger.i(TAG, "  AEB — valeurs par défaut, skip (évite désactivation involontaire)")
            return
        }
        val aebOk = MG4Hardware.setAebEnabled(aebEnabled)
        AppLogger.i(TAG, "  AebEnabled=$aebEnabled → $aebOk")
        if (aebEnabled) {
            val aebModeOk = MG4Hardware.setAebMode(aebMode)
            AppLogger.i(TAG, "  AebMode=$aebMode → $aebModeOk")
            if (aebSensitivity > 0) {
                val senOk = MG4Hardware.setAebSensitivity(aebSensitivity)
                AppLogger.i(TAG, "  AebSensitivity=$aebSensitivity → $senOk")
            }
        }
    }
}
