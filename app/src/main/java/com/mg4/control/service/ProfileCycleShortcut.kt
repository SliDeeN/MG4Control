package com.mg4.control.service

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.mg4.control.R
import com.mg4.control.debug.AppLogger
import com.mg4.control.hardware.VehicleWriteGate
import com.mg4.control.profile.ActiveProfile
import com.mg4.control.profile.ProfileApplier
import com.mg4.control.profile.ProfileManager
import com.mg4.control.shortcut.ProfileCycle
import com.mg4.control.util.LocaleHelper

/**
 * Raccourci « Cycle de profils » : chaque appui vise le profil suivant du cycle composé dans
 * l'onglet Raccourcis, l'annonce à l'écran, et l'applique après un court délai sans nouvel appui.
 *
 * Le délai ([ProfileCycle.APPLY_DELAY_MS]) est ce qui permet de SAUTER un profil : appliquer un
 * profil écrit une série de réglages sur la voiture, et deux appuis rapprochés appliqueraient
 * sinon le profil intermédiaire au passage. L'annonce dit où l'on en est pendant ce temps.
 *
 * La règle elle-même — quel profil vient ensuite — est dans [ProfileCycle], sans Android.
 */
object ProfileCycleShortcut {

    private const val TAG = "MG4_SVC"

    private val main = Handler(Looper.getMainLooper())

    // Ces trois champs ne se lisent et ne s'écrivent que sur le thread principal.

    /** Profil visé, tant qu'il attend d'être appliqué. */
    private var cible: String? = null
    private var application: Runnable? = null
    private var message: Toast? = null

    fun press(context: Context) {
        val app = context.applicationContext
        main.post { appui(app) }
    }

    private fun appui(app: Context) {
        // Sécurité conduite : si elle refuse, RIEN ne bouge — ni le profil visé, ni le profil
        // actif. Laisser faire appliquerait la moitié du profil (le confort passe, la conduite
        // non) et ferait avancer le cycle sur un profil qui n'est pas en place. C'est la
        // sécurité qui affiche son message habituel.
        if (!VehicleWriteGate.allow("cycle de profils")) return

        val profils = ProfileManager(app).getAll()
        val cycle   = ProfileCycle.order(app, profils.map { it.id })
        val vise    = ProfileCycle.target(cycle, ActiveProfile.id(app), cible)
        val profil  = profils.firstOrNull { it.id == vise }
        if (profil == null) {
            AppLogger.i(TAG, "SHORTCUT cycle de profils — aucun profil à appliquer")
            annoncer(app, R.string.shortcuts_no_profiles)
            return
        }
        AppLogger.i(TAG, "SHORTCUT cycle de profils → '${profil.name}' " +
            "(${cycle.indexOf(profil.id) + 1}/${cycle.size})")
        cible = profil.id
        annoncer(app, R.string.profile_cycle_target, profil.name)

        application?.let(main::removeCallbacks)
        application = Runnable { appliquer(app, profil.id) }
            .also { main.postDelayed(it, ProfileCycle.APPLY_DELAY_MS) }
    }

    private fun appliquer(app: Context, id: String) {
        cible = null
        application = null
        // Relu plutôt que gardé depuis l'appui : le profil a pu être modifié entre-temps.
        val profil = ProfileManager(app).getById(id) ?: return
        ProfileApplier.apply(profil) { ok ->
            main.post {
                // Un nouvel appui est arrivé pendant l'application : son annonce fait foi, et
                // « appliqué » par-dessus dirait le contraire de ce qui va se passer.
                if (cible != null) return@post
                annoncer(app,
                    if (ok) R.string.profile_cycle_applied else R.string.profile_cycle_incomplete,
                    profil.name)
            }
        }
    }

    /** Un seul message à la fois : le nouveau REMPLACE l'ancien au lieu d'attendre son tour. */
    private fun annoncer(app: Context, texte: Int, vararg args: Any) {
        // Langue choisie dans l'application : le contexte du service garde celle du système.
        val loc = LocaleHelper.applyLocale(app)
        message?.cancel()
        message = runCatching {
            Toast.makeText(loc, loc.getString(texte, *args), Toast.LENGTH_SHORT).also { it.show() }
        }.getOrNull()
    }
}
