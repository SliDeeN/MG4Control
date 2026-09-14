package com.mg4.control.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Instrumentation
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import com.mg4.control.debug.AppLogger
import com.mg4.control.service.MG4ControlService
import com.mg4.control.service.ProfilePickerOverlay
import com.mg4.control.shortcut.PressType
import com.mg4.control.shortcut.ShortcutAction
import com.mg4.control.util.GarageMode
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Interception des touches volant, AVANT le launcher.
 *
 * Pourquoi ce service existe alors que MG4Control reçoit déjà les touches : le chemin actuel est
 * un *broadcast* (`com.saic.keyevent.hardkey.report`), c'est-à-dire une notification émise APRÈS
 * coup par ce qui a déjà traité la touche. Un BroadcastReceiver ne rend rien au pipeline d'entrée,
 * il ne peut donc pas empêcher le launcher d'agir — d'où le double effet constaté. Seul un service
 * d'accessibilité portant [AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS] voit la touche
 * AVANT l'application au premier plan, et peut la consommer en renvoyant `true` depuis [onKeyEvent].
 *
 * ⚠️ PÉRIMÈTRE DE LA CONSOMMATION, à ne pas élargir à la légère. Trois cas seulement :
 *  • les touches qui portent une action dans [AdvancedShortcuts] POUR LE PROFIL ACTIF, et
 *    uniquement si l'interrupteur des raccourcis avancés est actif — un appui qui s'avère sans
 *    action y est renvoyé au système (voir [rejouer]) ;
 *  • la touche pressée PENDANT un enregistrement, le temps d'un seul appui ;
 *  • le joystick droit (297-301) PENDANT que le popup de profils est affiché — il y sert à
 *    naviguer, et l'avaler est ce qui empêche le volume et la piste de changer en même temps.
 * Tout le reste traverse. Avaler une
 * touche par erreur sur une voiture est autrement plus grave que le désagrément qu'on corrige,
 * d'où ce double verrou et le try/catch qui renvoie false en cas d'imprévu.
 */
class KeyCaptureService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        // Le XML déclare la CAPACITÉ (canRequestFilterKeyEvents) ; le drapeau, lui, doit être
        // posé ici. Déclarer la capacité sans demander le drapeau ne filtre rien du tout.
        serviceInfo = serviceInfo?.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
        }
        AppLogger.i(TAG, "service connecté — filtrage actif ; " +
            "${AdvancedShortcuts.all(this).size} raccourci(s) avancé(s) enregistré(s), " +
            "interrupteur=${AdvancedShortcuts.isEnabled(this)}")
    }

    private val handler = Handler(Looper.getMainLooper())

    /** Minuterie d'appui long en cours, par touche. */
    private val minuterieLongue = mutableMapOf<Int, Runnable>()

    /** Fenêtre de double appui ouverte, par touche : sa présence signale « un simple attend ». */
    private val fenetreDouble = mutableMapOf<Int, Runnable>()

    /** Touches dont l'appui long a déjà agi : leur relâchement ne doit plus rien déclencher. */
    private val longDeclenche = mutableSetOf<Int>()

    /** Touches dont le second appui a déjà agi : idem pour le relâchement qui suit. */
    private val doubleDeclenche = mutableSetOf<Int>()

    /** Touche en cours d'apprentissage : sert à avaler aussi la fin de son appui. */
    private var codeEnregistre: Int? = null

    /** Touches du joystick dont l'appui a servi à naviguer : leur fin d'appui est avalée aussi. */
    private val navigationEnCours = mutableSetOf<Int>()

    /**
     * Touches interceptées pour un raccourci avancé, entre leur DOWN et leur UP.
     *
     * La décision est prise UNE fois, au DOWN, puis tenue jusqu'au UP : elle dépend du profil actif,
     * qui peut changer pendant l'appui (connexion Bluetooth), et un UP dont le DOWN n'a pas été
     * vu par le système — ou l'inverse — laisserait une touche à moitié enfoncée.
     */
    private val touchesPrises = mutableSetOf<Int>()

    /** Dernier DOWN intercepté par touche : modèle de l'événement renvoyé au système. */
    private val modeles = mutableMapOf<Int, KeyEvent>()

    /**
     * Appuis renvoyés au système, repérés par leur `downTime` → code de touche.
     *
     * Un événement injecté ne repasse normalement pas par le filtre d'accessibilité. Mais si ce
     * firmware le faisait, on le réintercepterait et le renverrait sans fin — le volant serait
     * paralysé. Le coût de la précaution est nul.
     */
    private val rejeux = ConcurrentHashMap<Long, Int>()

    /** `Instrumentation.sendKeySync` refuse le thread principal : les renvois ont leur propre fil. */
    private val injecteur = Executors.newSingleThreadExecutor()

    override fun onKeyEvent(event: KeyEvent?): Boolean {
        // Tout est encapsulé : une exception qui remonterait d'ici déciderait à notre place du
        // sort de la touche. On ne laisse jamais une erreur avaler une commande du volant.
        try {
            event ?: return false
            val code = event.keyCode

            // Notre propre renvoi : il appartient au système, on n'y touche pas.
            if (rejeux[event.downTime] == code) return false

            if (event.action == KeyEvent.ACTION_DOWN) {
                AppLogger.i(TAG, "TOUCHE keycode=$code (${KeyEvent.keyCodeToString(code)}) " +
                    "source=${event.source} repeat=${event.repeatCount}")
            }

            // ── Mode enregistrement : on RÉCLAME la touche le temps de l'apprendre ──
            //
            // Sans ça, la touche partait au système pendant qu'on la captait : appuyer sur
            // « Accueil » basculait vers le launcher MG et l'utilisateur quittait l'écran avant
            // d'avoir pu terminer. Autrement dit, on ne pouvait enregistrer que les touches qui
            // ne font rien — l'inverse du besoin.
            //
            // Le risque de rester coincé est nul : l'enregistrement est à USAGE UNIQUE, le
            // listener se détache dès la première touche. Une seule pression est avalée.
            if (listener != null && event.action == KeyEvent.ACTION_DOWN) {
                codeEnregistre = code
                listener?.invoke(code)
                return true
            }
            // Fin de l'appui en cours d'enregistrement : le listener est déjà détaché, mais il
            // reste les répétitions et le UP. Les laisser passer livrerait au système un UP
            // orphelin — voire une action sur la touche qu'on vient justement de capturer.
            if (codeEnregistre == code) {
                if (event.action == KeyEvent.ACTION_UP) codeEnregistre = null
                return true
            }

            // Fin d'un appui qui a navigué dans le popup. Testé AVANT tout le reste : le popup a
            // pu se fermer entre-temps (le clic central choisit un profil et le ferme), et ce UP
            // ne doit partir ni au système ni vers un raccourci avancé de la même touche.
            if (code in navigationEnCours) {
                if (event.action == KeyEvent.ACTION_UP) navigationEnCours.remove(code)
                return true
            }

            // Suite d'un appui intercepté au DOWN : répétitions avalées, UP traité. Testé avant le
            // Mode Garage et l'interrupteur, pour la même raison : un appui commencé se termine
            // chez celui qui l'a commencé.
            if (code in touchesPrises && !(event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0)) {
                if (event.action == KeyEvent.ACTION_UP) {
                    touchesPrises.remove(code)
                    surRelachement(code)
                }
                return true
            }

            // Mode Garage : ne RIEN consommer. C'est ici que se joue le retour des touches au
            // launcher d'origine — un raccourci avancé réclame sa touche en bloc, et seul un
            // `false` rendu ici la laisse repartir vers l'application au premier plan.
            if (GarageMode.isOn(this)) return false

            // ── Popup de profils ouvert : le joystick droit y navigue ──
            //
            // Prioritaire sur les raccourcis avancés, qui ne doivent pas se déclencher pendant
            // qu'on choisit un profil, et indépendant de leur interrupteur. Seul un PREMIER down
            // ouvre la navigation : une répétition sans premier down signale un appui commencé
            // avant l'ouverture (un appui long qui vient d'ouvrir le popup, typiquement), et son
            // relâchement appartient au raccourci qui l'a pris en charge.
            val commande = JoystickFocus.commande(code)
            if (commande != null && event.action == KeyEvent.ACTION_DOWN &&
                event.repeatCount == 0 && ProfilePickerOverlay.isShowing()) {
                navigationEnCours.add(code)
                AppLogger.i(TAG, "touche $code — navigation popup profils → ${commande.name}")
                ProfilePickerOverlay.naviguer(commande)
                return true
            }

            // Tout ce qui n'est pas le PREMIER down d'un appui traverse : répétitions et UP d'un
            // appui que le système a vu commencer lui reviennent.
            if (event.action != KeyEvent.ACTION_DOWN || event.repeatCount != 0) return false
            if (!AdvancedShortcuts.isEnabled(this)) return false

            // Seules les touches qui portent une action pour le profil ACTIF sont interceptées.
            // Tout le reste traverse : c'est ce qui garantit qu'un bug ici ne peut pas paralyser
            // le volant. Exception : le second appui d'un double appui en attente, attendu quoi
            // qu'il arrive au profil entre-temps.
            if (code !in fenetreDouble && !AdvancedShortcuts.isClaimedNow(this, code)) {
                if (AdvancedShortcuts.isClaimed(this, code)) {
                    AppLogger.i(TAG, "touche $code — raccourcis réservés à d'autres profils → laissée au système")
                }
                return false
            }

            touchesPrises.add(code)
            modeles[code] = KeyEvent(event)   // copie : l'événement reçu peut être recyclé
            surAppui(code)
            return true   // touche réclamée : le launcher ne la verra pas
        } catch (e: Exception) {
            AppLogger.w(TAG, "onKeyEvent exception : ${e.message}")
            return false
        }
    }

    /**
     * Début d'un appui.
     *
     * Deux cas : soit il tombe dans la fenêtre ouverte par un appui précédent — c'est un double
     * appui, et il part TOUT DE SUITE — soit c'est un premier appui, et on arme la minuterie de
     * l'appui long.
     */
    private fun surAppui(code: Int) {
        val fenetre = fenetreDouble.remove(code)
        if (fenetre != null) {
            handler.removeCallbacks(fenetre)
            val m = AdvancedShortcuts.resolve(this, code, PressType.DOUBLE)
            if (m != null) {
                annulerLong(code)   // un second appui n'ouvre pas d'appui long
                AppLogger.i(TAG, "touche $code — DOUBLE appui → ${m.action.name}" +
                    (m.profileId?.let { " (profil $it)" } ?: ""))
                doubleDeclenche.add(code)
                declencher(m)
                return
            }
            // Le double appui a disparu pendant la fenêtre (changement de profil) : le premier
            // appui se conclut comme un simple, et celui-ci suit son cours normal.
            fenetre.run()
        }

        // ── Appui long : déclenché au SEUIL, pas au relâchement ──
        //
        // Attendre le relâchement rendait l'action tributaire du moment où l'utilisateur lâche
        // le bouton — donc jamais deux fois pareil, et toujours en retard sur la sensation
        // d'avoir « appuyé longtemps ». La minuterie donne un repère fixe : l'action part à
        // 500 ms précises, la touche est encore enfoncée, et le relâchement ne fait plus rien.
        // Résolue MAINTENANT et non à l'échéance : le profil peut changer pendant les 500 ms
        // (une connexion Bluetooth, par exemple), et l'action doit être celle qui correspondait
        // au moment où l'utilisateur a appuyé.
        val m = AdvancedShortcuts.resolve(this, code, PressType.LONG) ?: return
        val minuterie = Runnable {
            minuterieLongue.remove(code)
            longDeclenche.add(code)
            AppLogger.i(TAG, "touche $code — appui LONG au seuil " +
                "(${AdvancedShortcuts.LONG_PRESS_MS} ms, sans attendre le relâchement) " +
                "→ ${m.action.name}" + (m.profileId?.let { " (profil $it)" } ?: ""))
            declencher(m)
        }
        minuterieLongue[code] = minuterie
        handler.postDelayed(minuterie, AdvancedShortcuts.LONG_PRESS_MS)
    }

    /**
     * Fin d'un appui.
     *
     * Le relâchement ne déclenche plus que l'appui court — et encore, à retardement si la touche
     * porte aussi un double appui. Un appui maintenu sans appui long configuré arrive ici aussi :
     * la voiture ne connaissant que l'appui simple, c'est ainsi qu'elle l'aurait traité.
     */
    private fun surRelachement(code: Int) {
        annulerLong(code)   // relâchée avant le seuil : l'appui long n'aura pas lieu

        // Ces deux cas ont déjà agi pendant que la touche était enfoncée.
        if (doubleDeclenche.remove(code)) return
        if (longDeclenche.remove(code)) return

        val simple = AdvancedShortcuts.resolve(this, code, PressType.SINGLE)
        // Résolu pour le profil ACTIF : un double appui réservé à un autre profil ne se
        // déclencherait pas ici, l'attendre ne ferait que retarder l'appui simple.
        val aDouble = AdvancedShortcuts.resolve(this, code, PressType.DOUBLE) != null

        // Sans double appui sur cette touche, rien à attendre : l'action part immédiatement.
        if (!aDouble) {
            conclureAppuiCourt(code, simple, "appui court")
            return
        }

        // Avec un double appui, il faut s'assurer qu'aucun second appui n'arrive : déclencher le
        // simple tout de suite le ferait partir systématiquement AVANT le double. La fenêtre est
        // ouverte même sans action d'appui court, car c'est elle qui détecte le second appui.
        val fenetre = Runnable {
            fenetreDouble.remove(code)
            conclureAppuiCourt(code, simple,
                "appui court confirmé (aucun second appui en ${AdvancedShortcuts.DOUBLE_TAP_MS} ms)")
        }
        fenetreDouble[code] = fenetre
        handler.postDelayed(fenetre, AdvancedShortcuts.DOUBLE_TAP_MS)
    }

    /** Appui simple établi : son action s'il en a une, sinon la fonction d'origine de la touche. */
    private fun conclureAppuiCourt(code: Int, simple: AdvancedShortcuts.Mapping?, quoi: String) {
        if (simple != null) {
            AppLogger.i(TAG, "touche $code — $quoi → ${simple.action.name}")
            declencher(simple)
        } else {
            AppLogger.i(TAG, "touche $code — $quoi sans action MG4Control → rendu au système")
            rejouer(code)
        }
    }

    /**
     * Renvoie au système un appui simple sur [code], comme si la touche venait d'être pressée.
     *
     * C'est la seule façon de rendre sa fonction d'origine à une touche déjà interceptée : un
     * DOWN consommé ne peut pas être « relâché » vers le launcher. On injecte donc un appui neuf,
     * copié du DOWN d'origine (périphérique, scan code, source) pour que le système le traite
     * exactement comme la vraie touche. Possible parce que l'app tourne en `android.uid.system`,
     * qui détient l'injection d'événements — c'est aussi ce que fait KeyMapper.
     */
    private fun rejouer(code: Int) {
        val modele = modeles[code] ?: return
        val t = SystemClock.uptimeMillis()
        rejeux[t] = code
        handler.postDelayed({ rejeux.remove(t) }, 2_000L)

        fun evenement(action: Int) = KeyEvent(
            t, SystemClock.uptimeMillis(), action, code, 0,
            modele.metaState, modele.deviceId, modele.scanCode, modele.flags, modele.source
        )
        injecteur.execute {
            runCatching {
                val instrumentation = Instrumentation()
                instrumentation.sendKeySync(evenement(KeyEvent.ACTION_DOWN))
                instrumentation.sendKeySync(evenement(KeyEvent.ACTION_UP))
            }.onFailure { AppLogger.w(TAG, "touche $code — renvoi au système impossible : ${it.message}") }
        }
    }

    private fun annulerLong(code: Int) {
        minuterieLongue.remove(code)?.let { handler.removeCallbacks(it) }
    }

    /**
     * Relaie l'action au service principal, qui détient déjà tout le répartiteur et l'état des
     * bascules. On ne réimplémente rien ici — un second chemin d'exécution finirait par diverger.
     */
    private fun declencher(m: AdvancedShortcuts.Mapping) {
        val i = android.content.Intent(this, MG4ControlService::class.java).apply {
            setAction(MG4ControlService.ACTION_ADV_SHORTCUT)
            putExtra(MG4ControlService.EXTRA_ADV_ACTION, m.action.name)
            // Clé de bascule propre aux raccourcis avancés : sans elle, une action à deux états
            // partagerait son état avec le bouton classique du même nom. Le profil en fait
            // partie, sinon deux variantes de la même touche partageraient leur cible.
            putExtra(MG4ControlService.EXTRA_ADV_SLOT,
                AdvancedShortcuts.slotKey(m.keyCode, m.press, m.profileId))
        }
        runCatching { startForegroundService(i) }
            .onFailure { AppLogger.w(TAG, "relais impossible : ${it.message}") }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) { /* inutilisé */ }

    override fun onInterrupt() { /* inutilisé */ }

    override fun onDestroy() {
        // Le service peut être coupé pendant qu'une minuterie court : sans ça, elle déclencherait
        // une action alors que la fonctionnalité vient d'être désactivée.
        handler.removeCallbacksAndMessages(null)
        minuterieLongue.clear()
        fenetreDouble.clear()
        longDeclenche.clear()
        doubleDeclenche.clear()
        navigationEnCours.clear()
        touchesPrises.clear()
        modeles.clear()
        rejeux.clear()
        injecteur.shutdownNow()
        AppLogger.i(TAG, "service déconnecté")
        super.onDestroy()
    }

    companion object {
        private const val TAG = "MG4_KEYCAP"

        /**
         * Destinataire des touches captées, posé par l'écran d'enregistrement.
         *
         * Volontairement statique : un service d'accessibilité est instancié par le système, on
         * ne peut pas lui passer de référence. Toujours le remettre à null en quittant l'écran,
         * sinon on retiendrait un Fragment détruit.
         */
        @Volatile
        var listener: ((Int) -> Unit)? = null

        /**
         * Vrai si l'utilisateur a activé notre service dans les réglages d'accessibilité.
         *
         * Lu dans Settings.Secure plutôt que via AccessibilityManager : on veut savoir si NOTRE
         * composant précis est dans la liste, pas si un service quelconque tourne.
         */
        fun isEnabled(context: Context): Boolean = try {
            val actifs = Settings.Secure.getString(
                context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ).orEmpty()
            val nous = "${context.packageName}/${KeyCaptureService::class.java.name}"
            actifs.split(':').any { it.equals(nous, ignoreCase = true) }
        } catch (e: Exception) {
            AppLogger.w(TAG, "état du service illisible : ${e.message}")
            false
        }
    }
}
