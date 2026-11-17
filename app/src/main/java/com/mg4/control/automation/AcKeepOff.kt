package com.mg4.control.automation

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import com.mg4.control.debug.AppLogger
import com.mg4.control.hardware.MG4Hardware
import com.mg4.control.util.GarageMode

/**
 * Garder l'A/C coupé quand la température change (issue #208, Réglages › Avancé, désactivé par
 * défaut). La voiture rallume l'A/C dès qu'on touche à la consigne, même en mode manuel avec
 * l'A/C coupé — sur tous les firmwares. MG4Control le recoupe :
 *
 *  1. **Ses propres changements** (Tableau de bord, pop-up HVAC, raccourcis, automatisations,
 *     profils, pré-climatisation) : l'A/C est lu juste avant l'écriture de la consigne ; s'il
 *     était coupé, il est relu [VERIF_MS] après le DERNIER changement (appuis + + + groupés) et
 *     recoupé si la voiture l'a rallumé.
 *  2. **Les commandes de la voiture** (écran clim d'origine, voix) : un relevé par seconde. Un A/C
 *     qui passe de coupé à allumé dans la seconde et demie d'un changement de consigne est recoupé
 *     — une seule fois par changement : si la voiture insiste, on la laisse faire. Limite connue :
 *     un appui sur A/C juste après avoir changé la température serait annulé.
 *
 * Jamais en AUTO (l'A/C est à lui) ni dégivrage avant allumé (il force l'A/C pour désembuer), ni
 * clim éteinte, ni en Mode Garage. Option coupée : rien ne tourne, rien ne change.
 */
object AcKeepOff {

    private const val TAG = "MG4_CLIM"
    private const val PREFS = "mg4_settings"
    const val KEY = "hvac_keep_ac_off"

    /** Relecture après le dernier changement de consigne fait par MG4Control. */
    private const val VERIF_MS = 700L
    /** Relevé de l'état de la voiture (volet 2). */
    private const val RELEVE_MS = 1_000L
    /** Fenêtre entre un changement de consigne et un A/C rallumé. */
    private const val FENETRE_MS = 1_500L
    /** Ce que MG4Control écrit lui-même n'est pas pris pour une commande de la voiture. */
    private const val ECRITURE_PROPRE_MS = 3_000L

    private val fil by lazy { HandlerThread("mg4-ackeep").also { it.start() } }
    private val h by lazy { Handler(fil.looper) }

    @Volatile private var appContext: Context? = null
    @Volatile private var derniereEcriture = 0L
    @Volatile private var aRecouper = false
    private var enMarche = false
    private var avant: MG4Hardware.EtatAc? = null
    private var consigneAvant: Int? = null
    private var consigneChangeeA = 0L
    private var corrige = false

    fun isOn(ctx: Context?): Boolean =
        ctx?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.getBoolean(KEY, false) ?: false

    fun setOn(ctx: Context, on: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, on).apply()
        AppLogger.i(TAG, "garder l'A/C coupé quand la température change : ${if (on) "activé" else "désactivé"}")
        demarrer(ctx)
    }

    /** Démarrage du service ou changement de l'option : lance (ou laisse s'arrêter) le relevé. */
    fun demarrer(ctx: Context) {
        appContext = ctx.applicationContext
        h.post {
            if (enMarche || !isOn(appContext)) return@post
            enMarche = true
            avant = null
            h.post(releve)
        }
    }

    /** Toute écriture clim de MG4Control (A/C, consigne) : le relevé de la voiture l'ignore. */
    fun noteEcriture() {
        derniereEcriture = SystemClock.elapsedRealtime()
    }

    /**
     * Volet 1 — appelé par [MG4Hardware.setClimateTemp] juste AVANT d'écrire la consigne, avec
     * l'état lu à cet instant.
     */
    fun avantConsigne(etat: MG4Hardware.EtatAc?) {
        if (!isOn(appContext)) return
        noteEcriture()
        if (etat != null && concerne(etat) && etat.ac == false) aRecouper = true
        if (!aRecouper) return
        h.removeCallbacks(verifier)
        h.postDelayed(verifier, VERIF_MS)
    }

    private val verifier = Runnable {
        aRecouper = false
        val e = MG4Hardware.etatAc() ?: return@Runnable
        if (!concerne(e) || e.ac != true) return@Runnable
        AppLogger.i(TAG, "A/C rallumé par la voiture après un changement de température → recoupé")
        MG4Hardware.setClimateAc(false)
    }

    /** Volet 2 — un relevé par seconde, tant que l'option est active. */
    private val releve = object : Runnable {
        override fun run() {
            val ctx = appContext
            if (!isOn(ctx)) { enMarche = false; avant = null; return }
            try { observer(ctx) } catch (e: Exception) { AppLogger.w(TAG, "garde A/C : ${e.message}") }
            h.postDelayed(this, RELEVE_MS)
        }
    }

    private fun observer(ctx: Context?) {
        if (ctx != null && GarageMode.isOn(ctx)) { avant = null; return }
        val e = MG4Hardware.etatAc()
        val maintenant = SystemClock.elapsedRealtime()
        if (e == null || e.power != true) { avant = null; consigneAvant = null; return }
        val t = MG4Hardware.consigneLue()
        if (t != null && consigneAvant != null && t != consigneAvant) {
            consigneChangeeA = maintenant
            corrige = false
        }
        if (t != null) consigneAvant = t
        val p = avant
        avant = e
        if (p == null || p.ac != false || e.ac != true) return
        if (!concerne(e) || corrige) return
        if (maintenant - consigneChangeeA > FENETRE_MS) return
        if (maintenant - derniereEcriture < ECRITURE_PROPRE_MS) return
        corrige = true
        AppLogger.i(TAG, "A/C rallumé après un changement de température sur l'écran de la voiture → recoupé")
        MG4Hardware.setClimateAc(false)
        avant = MG4Hardware.etatAc()
    }

    /** Clim allumée, en manuel, sans dégivrage avant : le seul cas où l'on touche à l'A/C. */
    private fun concerne(e: MG4Hardware.EtatAc): Boolean =
        e.power == true && e.auto != true && e.degivrage != true
}
