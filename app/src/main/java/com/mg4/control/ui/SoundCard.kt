package com.mg4.control.ui

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.slider.Slider
import com.mg4.control.R
import com.mg4.control.automation.SoundAutomation
import com.mg4.control.automation.SoundAutomationSettings
import com.mg4.control.automation.SoundImport
import com.mg4.control.hardware.MG4Hardware
import com.mg4.control.model.SoundAutomationRules
import com.mg4.control.model.SoundEvent
import com.mg4.control.model.SoundSlot
import com.mg4.control.profile.ProfileManager
import java.text.NumberFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Carte « Sons » de l'onglet Automatisation (issue #161, maquette du 2026-10-23) : les deux sons
 * côte à côte — fichier, Importer / Changer, Écouter / Arrêter, Retirer, volume de la voiture
 * pendant le son, événements (un événement pris par l'autre son est grisé) —, puis les profils.
 * L'interrupteur et le dépliage restent à [AutomationFragment], comme pour les autres cartes.
 */
class SoundCard {

    private class Emplacement(racine: View) {
        val titre: TextView = racine.findViewById(R.id.sound_slot_title)
        val duree: TextView = racine.findViewById(R.id.sound_slot_duration)
        val fichier: TextView = racine.findViewById(R.id.sound_slot_file)
        val importer: MaterialButton = racine.findViewById(R.id.btn_sound_import)
        val ecouter: MaterialButton = racine.findViewById(R.id.btn_sound_play)
        val retirer: MaterialButton = racine.findViewById(R.id.btn_sound_remove)
        val volume: TextView = racine.findViewById(R.id.sound_slot_volume_value)
        val curseur: Slider = racine.findViewById(R.id.slider_sound_volume)
        val evenements: LinearLayout = racine.findViewById(R.id.sound_slot_events)
    }

    private var fragment: Fragment? = null
    private var contenu: View? = null
    private var emplacements: List<Emplacement> = emptyList()
    private var profils: LinearLayout? = null
    private var max = 0

    fun bind(f: Fragment, view: View, actif: Boolean) {
        fragment = f
        contenu = view.findViewById(R.id.row_sound_config)
        profils = view.findViewById(R.id.sound_profiles)
        emplacements = listOf(R.id.sound_slot_1, R.id.sound_slot_2).map { Emplacement(view.findViewById(it)) }
        emplacements.forEachIndexed { i, e -> brancher(f, i, e) }
        peindre()
        griser(actif)
        SoundAutomation.surChangement = { peindre() }
        // Le curseur prend l'échelle de la voiture.
        f.viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val m = MG4Hardware.getMediaVolumeMax()
            withContext(Dispatchers.Main) {
                if (!f.isAdded) return@withContext
                max = m.coerceAtLeast(0)
                peindre()
            }
        }
    }

    /** La vue part : plus personne à prévenir. */
    fun detacher() {
        SoundAutomation.surChangement = null
        fragment = null
    }

    /** Option coupée : réglages lisibles mais grisés, comme sur les autres cartes. */
    fun griser(actif: Boolean) {
        contenu?.alpha = if (actif) 1f else 0.4f
    }

    private fun brancher(f: Fragment, i: Int, e: Emplacement) {
        val ctx = f.requireContext()
        e.titre.text = ctx.getString(R.string.sound_slot, i + 1)
        e.importer.setOnClickListener { importer(f, i) }
        e.ecouter.setOnClickListener {
            if (SoundAutomation.enLecture == i) SoundAutomation.arreter() else SoundAutomation.ecouter(ctx, i)
        }
        e.retirer.setOnClickListener {
            if (SoundAutomation.enLecture == i) SoundAutomation.arreter()
            f.viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                SoundImport.retirer(ctx, i)
                withContext(Dispatchers.Main) { if (f.isAdded) peindre() }
            }
        }
        e.curseur.addOnChangeListener { _, valeur, parUtilisateur ->
            e.volume.text = ctx.getString(R.string.door_volume_level_value, valeur.toInt(), e.curseur.valueTo.toInt())
            if (parUtilisateur) {
                SoundAutomationSettings.saveSlot(ctx, i, SoundAutomationSettings.slot(ctx, i).copy(volume = valeur.toInt()))
            }
        }
    }

    /** Tout ce qui dépend des réglages : fichiers, boutons, volumes, événements, profils. */
    private fun peindre() {
        val f = fragment ?: return
        if (!f.isAdded) return
        val ctx = f.requireContext()
        val sons = SoundAutomationSettings.slots(ctx)
        emplacements.forEachIndexed { i, e ->
            val s = sons[i]
            e.fichier.text = s.title?.takeIf { s.hasFile } ?: ctx.getString(R.string.sound_no_file)
            e.fichier.setTextColor(ctx.getColor(if (s.hasFile) R.color.text_primary else R.color.text_secondary))
            e.duree.text = if (s.hasFile) ctx.getString(R.string.sound_duration, secondes(s.durationMs)) else ""
            e.importer.text = ctx.getString(if (s.hasFile) R.string.sound_change else R.string.sound_import)
            e.ecouter.visibility = if (s.hasFile) View.VISIBLE else View.INVISIBLE
            e.retirer.visibility = if (s.hasFile) View.VISIBLE else View.INVISIBLE
            e.ecouter.text = ctx.getString(if (SoundAutomation.enLecture == i) R.string.sound_stop else R.string.sound_play)
            if (max > 0) {
                e.curseur.valueTo = max.toFloat()
                e.curseur.value = SoundAutomationRules.volumeFor(s, max).toFloat()
            }
            e.volume.text = ctx.getString(R.string.door_volume_level_value, e.curseur.value.toInt(), e.curseur.valueTo.toInt())
            evenements(f, i, e, sons)
        }
        peindreProfils(f)
    }

    /** Les événements du son [i], deux par ligne ; ceux de l'autre son grisés et nommés. */
    private fun evenements(f: Fragment, i: Int, e: Emplacement, sons: List<SoundSlot>) {
        val ctx = f.requireContext()
        val dispo = SoundEvent.entries.filter { it != SoundEvent.FRONT_DOOR || MG4Hardware.hasDoorDetection() }
        e.evenements.removeAllViews()
        grille(e.evenements, dispo.size, COLONNES_EVENEMENTS) { rang, bouton ->
            val ev = dispo[rang]
            val proprio = SoundAutomationRules.owner(ev, sons)
            val pris = proprio != null && proprio != i
            bouton.text = if (pris) ctx.getString(R.string.sound_event_taken, ctx.getString(libelle(ev)), proprio!! + 1)
                else ctx.getString(libelle(ev))
            peindreCase(bouton, coche = proprio == i, grise = pris)
            bouton.setOnClickListener {
                val avant = SoundAutomationSettings.slots(ctx)
                val apres = SoundAutomationRules.toggle(avant, i, ev)
                if (apres == avant) return@setOnClickListener
                SoundAutomationSettings.saveSlot(ctx, i, apres[i])
                peindre()
            }
        }
    }

    private fun peindreProfils(f: Fragment) {
        val ctx = f.requireContext()
        val boite = profils ?: return
        val liste = runCatching { ProfileManager(ctx).getAll() }.getOrDefault(emptyList())
        val choisis = SoundAutomationSettings.profiles(ctx)
        boite.removeAllViews()
        grille(boite, liste.size + 1, COLONNES_PROFILS) { rang, bouton ->
            if (rang == 0) {
                bouton.text = ctx.getString(R.string.sound_profiles_all)
                peindreCase(bouton, coche = choisis.isEmpty(), grise = false)
                bouton.setOnClickListener { SoundAutomationSettings.setProfiles(ctx, emptySet()); peindreProfils(f) }
            } else {
                val p = liste[rang - 1]
                bouton.text = p.name
                peindreCase(bouton, coche = p.id in choisis, grise = false)
                bouton.setOnClickListener {
                    val nouveaux = if (p.id in choisis) choisis - p.id else choisis + p.id
                    SoundAutomationSettings.setProfiles(ctx, nouveaux)
                    peindreProfils(f)
                }
            }
        }
    }

    /** Pose [nombre] cases en lignes de [colonnes] ; une dernière ligne incomplète garde la largeur des autres. */
    private fun grille(boite: LinearLayout, nombre: Int, colonnes: Int, remplir: (Int, MaterialButton) -> Unit) {
        val ctx = boite.context
        val gonfleur = LayoutInflater.from(ctx)
        val ecart = AppDialog.dp(ctx, 8f)
        (0 until nombre).chunked(colonnes).forEachIndexed { n, rangs ->
            val ligne = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT).also { if (n > 0) it.topMargin = ecart }
            }
            repeat(colonnes) { c ->
                val bouton = gonfleur.inflate(R.layout.item_sound_choice, ligne, false) as MaterialButton
                (bouton.layoutParams as LinearLayout.LayoutParams).marginStart = if (c > 0) ecart else 0
                val rang = rangs.getOrNull(c)
                if (rang == null) bouton.visibility = View.INVISIBLE else remplir(rang, bouton)
                ligne.addView(bouton)
            }
            boite.addView(ligne)
        }
    }

    private fun peindreCase(b: MaterialButton, coche: Boolean, grise: Boolean) {
        val ctx = b.context
        b.backgroundTintList = ColorStateList.valueOf(ctx.getColor(if (coche) R.color.dash_accent_dim else R.color.dash_btn))
        b.strokeColor = ColorStateList.valueOf(ctx.getColor(if (coche) R.color.dash_accent else R.color.dash_border))
        b.setTextColor(ctx.getColor(if (coche) R.color.text_primary else R.color.text_secondary))
        b.alpha = if (grise) 0.45f else 1f
    }

    private fun libelle(e: SoundEvent): Int = when (e) {
        SoundEvent.APP_START -> R.string.sound_event_app_start
        SoundEvent.READY_ON -> R.string.sound_event_ready_on
        SoundEvent.READY_OFF -> R.string.sound_event_ready_off
        SoundEvent.PARK -> R.string.sound_event_park
        SoundEvent.DRIVE -> R.string.sound_event_drive
        SoundEvent.FRONT_DOOR -> R.string.sound_event_door
        SoundEvent.PROFILE -> R.string.sound_event_profile
    }

    /** « 3,2 s » dans la langue de l'appli. */
    private fun secondes(ms: Long): String {
        val ctx = fragment?.context ?: return ""
        val nf = NumberFormat.getNumberInstance(ctx.resources.configuration.locales[0]).apply {
            minimumFractionDigits = 1
            maximumFractionDigits = 1
        }
        return ctx.getString(R.string.sound_seconds, nf.format(ms / 1000.0))
    }

    // ── Import ───────────────────────────────────────────────────────────────

    private fun importer(f: Fragment, i: Int) {
        val ctx = f.requireContext()
        Toast.makeText(ctx, R.string.sound_import_searching, Toast.LENGTH_SHORT).show()
        f.viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val r = SoundImport.chercher()
            withContext(Dispatchers.Main) {
                if (!f.isAdded) return@withContext
                when {
                    r.cles == 0 -> AppDialog.confirmer(ctx, ctx.getString(R.string.sound_import_title, i + 1),
                        ctx.getString(R.string.sound_import_no_usb), oui = ctx.getString(android.R.string.ok))
                    r.fichiers.isEmpty() -> AppDialog.confirmer(ctx, ctx.getString(R.string.sound_import_title, i + 1),
                        ctx.getString(R.string.sound_import_none), oui = ctx.getString(android.R.string.ok))
                    else -> choisir(f, i, r)
                }
            }
        }
    }

    private fun choisir(f: Fragment, i: Int, r: SoundImport.Resultat) {
        val ctx = f.requireContext()
        val choix = r.fichiers.map {
            AppDialog.Choix(
                libelle = it.file.name,
                pastille = secondes(it.durationMs),
                fondPastille = ctx.getColor(R.color.dash_accent_dim),
                textePastille = ctx.getColor(R.color.dash_accent),
            )
        }
        val sousTitre = if (r.tropLongs > 0) ctx.getString(R.string.sound_import_subtitle_skipped, r.tropLongs)
            else ctx.getString(R.string.sound_import_subtitle)
        AppDialog.choisir(ctx, ctx.getString(R.string.sound_import_title, i + 1), choix,
            annuler = ctx.getString(android.R.string.cancel), sousTitre = sousTitre, icone = R.drawable.ic_source_usb) { rang ->
            val choisi = r.fichiers[rang]
            if (SoundAutomation.enLecture == i) SoundAutomation.arreter()
            f.viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                val s = SoundImport.importer(ctx, i, choisi)
                withContext(Dispatchers.Main) {
                    if (!f.isAdded) return@withContext
                    Toast.makeText(ctx, if (s != null) ctx.getString(R.string.sound_import_done, choisi.file.name)
                        else ctx.getString(R.string.sound_import_failed), Toast.LENGTH_LONG).show()
                    peindre()
                }
            }
        }
    }

    private companion object {
        const val COLONNES_EVENEMENTS = 2
        const val COLONNES_PROFILS = 4
    }
}
