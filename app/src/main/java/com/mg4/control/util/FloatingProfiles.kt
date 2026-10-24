package com.mg4.control.util

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import com.mg4.control.debug.AppLogger
import com.mg4.control.model.DrivingProfile
import com.mg4.control.model.FloatingButton
import com.mg4.control.model.FloatingButtons
import com.mg4.control.model.FloatingSet
import com.mg4.control.model.FloatingSets
import com.mg4.control.profile.ActiveProfile
import com.mg4.control.profile.ProfileManager
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Boutons flottants par profil (2026-10-24, proposition B) : quel profil a ses bulles à l'écran,
 * et l'enregistrement de ses bulles dans le profil.
 *
 * L'écran montre les bulles du profil en cours — sauf pendant que la page des boutons flottants
 * est ouverte : il montre alors celles du profil qu'elle règle ([editingId]), pour les placer.
 *
 * Une frappe dans le titre, un curseur de taille ou une bulle déplacée produisent des dizaines
 * d'enregistrements ; réécrire à chaque fois tous les profils (et leur sauvegarde) ferait ramer
 * l'écran. Les bulles sont donc gardées en mémoire puis écrites en une fois, [ECRITURE_MS] après
 * le dernier changement, hors du fil principal ; les lectures voient tout de suite la nouvelle
 * version.
 */
object FloatingProfiles {

    private const val TAG = "MG4_FLOAT"
    /** Même fichier que les autres réglages. */
    private const val PREFS = "mg4_settings"
    private const val KEY_MIGRATED = "floating_per_profile_migrated"
    private const val ECRITURE_MS = 300L

    /** Profil que règle la page des boutons flottants tant qu'elle est à l'écran ; `null` sinon. */
    @Volatile var editingId: String? = null

    /** Bulles enregistrées, pas encore écrites dans leur profil (par identifiant de profil). */
    private val enAttente = ConcurrentHashMap<String, List<FloatingButton>>()
    private val ecrivain: Handler by lazy { Handler(HandlerThread("mg4-float-save").also { it.start() }.looper) }
    @Volatile private var appli: Context? = null
    private val ecrire = Runnable { flush() }

    /** Profil dont les bulles sont à l'écran ([FloatingSets.target]) ; `null` sans aucun profil. */
    fun target(ctx: Context): DrivingProfile? = FloatingSets.target(
        ProfileManager(ctx).getAll(), editingId, ActiveProfile.id(ctx), ProfileManager.defaultIdOf(ctx))

    /** Bulles du profil visé, ou `null` sans aucun profil : les réglages communs valent alors. */
    fun buttons(ctx: Context): List<FloatingButton>? {
        val p = target(ctx) ?: return null
        return enAttente[p.id] ?: FloatingSets.buttonsOf(p)
    }

    /** Bulles de [profile], celles pas encore écrites comprises. */
    fun buttonsOf(profile: DrivingProfile): List<FloatingButton> =
        enAttente[profile.id] ?: FloatingSets.buttonsOf(profile)

    fun isEnabled(ctx: Context): Boolean? = target(ctx)?.let(FloatingSets::enabledOf)

    /** [list] pour le profil visé, écrite un instant plus tard. `false` sans aucun profil. */
    fun save(ctx: Context, list: List<FloatingButton>): Boolean {
        val id = target(ctx)?.id ?: return false
        appli = ctx.applicationContext
        enAttente[id] = list.take(FloatingButtons.MAX)
        ecrivain.removeCallbacks(ecrire)
        ecrivain.postDelayed(ecrire, ECRITURE_MS)
        return true
    }

    /** « Afficher les boutons flottants » du profil visé, écrit tout de suite. `false` sans aucun profil. */
    fun setEnabled(ctx: Context, on: Boolean): Boolean {
        val id = target(ctx)?.id ?: return false
        flush(ctx)
        ProfileManager(ctx).updateAll { if (it.id == id) FloatingSets.withEnabled(it, on) else it }
        return true
    }

    /** Écrit tout de suite ce qui attend. */
    fun flush(ctx: Context? = appli) {
        val c = ctx ?: return
        if (enAttente.isEmpty()) return
        val lot = HashMap(enAttente)
        ProfileManager(c).updateAll { p -> lot[p.id]?.let { FloatingSets.withButtons(p, it) } ?: p }
        // Ne retire que ce qui a été écrit : un changement arrivé entre-temps reste à écrire.
        lot.forEach { (id, liste) -> enAttente.remove(id, liste) }
    }

    /**
     * Les bulles de [fromId] remplacent celles de [toIds] (identifiants neufs). Retourne le nombre
     * de profils modifiés.
     */
    fun copyTo(ctx: Context, fromId: String, toIds: Collection<String>): Int {
        flush(ctx)
        val pm = ProfileManager(ctx)
        val source = pm.getById(fromId) ?: return 0
        val bulles = FloatingSets.buttonsOf(source)
        var copies = 0
        pm.updateAll { p ->
            if (p.id != fromId && p.id in toIds) {
                copies++
                FloatingSets.withButtons(p, FloatingSets.copyOf(bulles) { UUID.randomUUID().toString() })
            } else p
        }
        return copies
    }

    /**
     * Une fois, à la mise à jour qui l'introduit : chaque profil existant reçoit les bulles et
     * l'interrupteur communs d'avant, pour que rien ne change à l'écran. Un profil créé ensuite n'a
     * aucune bulle.
     */
    fun migrateIfNeeded(ctx: Context) {
        runCatching {
            val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (prefs.getBoolean(KEY_MIGRATED, false)) return
            val communes = FloatingSet(
                enabled = FloatingButtonSettings.legacyEnabled(ctx),
                buttons = FloatingButtonSettings.legacyButtons(ctx),
            )
            val repris = ProfileManager(ctx).updateAll { if (it.floating == null) it.copy(floating = communes) else it }
            prefs.edit().putBoolean(KEY_MIGRATED, true).apply()
            if (repris) AppLogger.i(TAG, "boutons flottants par profil : ${communes.buttons.orEmpty().size} bulle(s) " +
                "commune(s) reprise(s) par les profils existants")
        }.onFailure { AppLogger.w(TAG, "boutons flottants par profil : reprise des bulles communes — ${it.message}") }
    }
}
