package com.mg4.control.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.mg4.control.R
import com.mg4.control.hardware.MG4Hardware
import com.mg4.control.hardware.MG4Hardware.AebMode
import com.mg4.control.hardware.MG4Hardware.AebSensitivity
import com.mg4.control.hardware.MG4Hardware.ElkMode
import com.mg4.control.hardware.MG4Hardware.ElkSensitivity
import com.mg4.control.hardware.MG4Hardware.Swi68Mode
import com.mg4.control.model.DriveMode
import com.mg4.control.model.DrivingProfile
import com.mg4.control.model.RegenLevel
import com.mg4.control.profile.ProfileApplier
import com.mg4.control.profile.ProfileManager
import com.mg4.control.util.FirmwareInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ProfileFragment : Fragment() {

    private lateinit var manager: ProfileManager
    private lateinit var adapter: ProfileAdapter

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_profile, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        manager = ProfileManager(requireContext())

        adapter = ProfileAdapter(
            mutableListOf(),
            manager.getDefaultId(),
            onApply = { profile ->
                ProfileApplier.apply(profile) { ok ->
                    requireActivity().runOnUiThread {
                        val msg = if (ok) getString(R.string.profile_applied, profile.name)
                                  else "Profil appliqué (vérifier les logs)"
                        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                    }
                }
            },
            onSetDefault = { profile ->
                manager.setDefault(profile.id)
                refreshList()
                Toast.makeText(context, "Profil par défaut : ${profile.name}", Toast.LENGTH_SHORT).show()
            },
            onEdit = { profile ->
                openEditor(existing = profile, data = profile)
            },
            onDelete = { profile ->
                AppDialog.confirmer(
                    requireContext(),
                    titre = getString(R.string.profile_delete_title, profile.name),
                    ton = AppDialog.Ton.DANGER,
                    icone = R.drawable.ic_dialog_trash,
                    oui = getString(R.string.profile_delete),
                    non = getString(R.string.profile_cancel),
                    onOui = {
                        manager.delete(profile.id)
                        refreshList()
                    },
                )
            }
        )

        view.findViewById<RecyclerView>(R.id.recycler_profiles).apply {
            layoutManager = LinearLayoutManager(context)
            adapter = this@ProfileFragment.adapter
        }

        // ── Bouton Fermer ─────────────────────────────────────────────────────
        view.findViewById<MaterialButton>(R.id.btn_close_profiles).setOnClickListener {
            findNavController().popBackStack(R.id.dashboardFragment, false)
        }

        view.findViewById<View>(R.id.btn_add_profile).setOnClickListener {
            if (manager.getAll().size >= ProfileManager.MAX_PROFILES) {
                Toast.makeText(context, getString(R.string.profile_max_reached, ProfileManager.MAX_PROFILES), Toast.LENGTH_SHORT).show()
            } else {
                openNewProfileEditor()
            }
        }

        refreshList()
    }

    override fun onResume() {
        super.onResume()
        refreshList()
    }

    private fun refreshList() {
        adapter.update(manager.getAll(), manager.getDefaultId())
    }

    // -------------------------------------------------------------------------
    // Nouveau profil : lit l'état hardware courant puis ouvre l'éditeur pré-rempli
    // -------------------------------------------------------------------------

    private fun openNewProfileEditor() {
        CoroutineScope(Dispatchers.IO).launch {
            val hasHeat  = FirmwareInfo.hasHeatFeatures()
            val isSWI132 = FirmwareInfo.getGeneration() == FirmwareInfo.Gen.SWI132
            val prefill = if (FirmwareInfo.isVsmBased()) {
                // SWI68/SWI69/SWI131/SWI132/SWI165 : ADAS ACC/TJA — sièges/volant uniquement sur SWI68/SWI165
                val elkMode = MG4Hardware.getElkMode().let {
                    if (it < 1) (if (isSWI132) ElkMode.ALERT else ElkMode.EMERGENCY) else it
                }
                val elkSen  = MG4Hardware.getElkSensitivity().let { if (it < 1) ElkSensitivity.STANDARD else it }
                DrivingProfile(
                    name          = "",
                    driveMode     = MG4Hardware.getDriveMode()  ?: DriveMode.NORMAL,
                    regenLevel    = MG4Hardware.getRegenLevel() ?: RegenLevel.MEDIUM,
                    steeringHeat  = if (hasHeat) MG4Hardware.isSteeringHeatOn() else false,
                    seatHeatLeft  = if (hasHeat) MG4Hardware.getSeatHeatLeft().coerceAtLeast(0) else 0,
                    seatHeatRight = if (hasHeat) MG4Hardware.getSeatHeatRight().coerceAtLeast(0) else 0,
                    // SWI132 : deux alertes indépendantes comme SWI133 (pas de soundWarning VSM)
                    overspeedAlarm = if (isSWI132) MG4Hardware.isOverspeedAlarmOn() else false,
                    speedLimitTone = if (isSWI132) MG4Hardware.isSpeedLimitToneOn() else false,
                    soundWarning   = if (!isSWI132) MG4Hardware.isSoundWarningOn() else false,
                    // Mode ACC/TJA — SHWA (ancien codage limiteur) ramené à Off (limiteur géré à part)
                    swi68AdasMode  = MG4Hardware.getAccTjaMode().let {
                        if (it < 0 || it == Swi68Mode.SHWA) Swi68Mode.OFF else it
                    },
                    // Limiteur de vitesse — capturé pour tous les firmwares VSM (SWI68/69/131/132/165)
                    swi132LimiterConfigured = true,
                    swi132SasMode  = MG4Hardware.getSpeedLimiterMode().let { if (it < 0) 0 else it },
                    aebEnabled     = MG4Hardware.isAebEnabled(),
                    aebMode        = MG4Hardware.getAebMode().let { if (it < 1) AebMode.ALARM else it },
                    aebSensitivity = MG4Hardware.getAebSensitivity().let { if (it < 1) AebSensitivity.STANDARD else it },
                    elkMode        = elkMode,
                    elkSensitivity = elkSen,
                    lasAudibleWarning    = if (isSWI132) (MG4Hardware.getLasWarningSound() == 1) else true,
                    lasVibrationReminder = if (isSWI132) (MG4Hardware.getLasWarningVibration() == 1) else true,
                    energySaving   = MG4Hardware.isEnergySavingOn(),
                    tsrEnabled     = MG4Hardware.isTsrOn()
                )
            } else {
                // SWI133/UNKNOWN : ADAS mixte, sièges et volant chauffants
                val elkMode = MG4Hardware.getElkMode().let { if (it < 1) ElkMode.EMERGENCY else it }
                val elkSen  = MG4Hardware.getElkSensitivity().let { if (it < 1) ElkSensitivity.STANDARD else it }
                val aebSen  = MG4Hardware.getAebSensitivity().let { if (it < 1) AebSensitivity.STANDARD else it }
                DrivingProfile(
                    name           = "",
                    driveMode      = MG4Hardware.getDriveMode()  ?: DriveMode.NORMAL,
                    regenLevel     = MG4Hardware.getRegenLevel() ?: RegenLevel.MEDIUM,
                    steeringHeat   = MG4Hardware.isSteeringHeatOn(),
                    seatHeatLeft   = MG4Hardware.getSeatHeatLeft().coerceAtLeast(0),
                    seatHeatRight  = MG4Hardware.getSeatHeatRight().coerceAtLeast(0),
                    overspeedAlarm = MG4Hardware.isOverspeedAlarmOn(),
                    speedLimitTone = MG4Hardware.isSpeedLimitToneOn(),
                    adasMode       = MG4Hardware.getMixedIntelligentDrive().coerceAtLeast(0),
                    aebEnabled     = MG4Hardware.isAebEnabled(),
                    aebMode        = MG4Hardware.getAebMode().let { if (it < 1) AebMode.ALARM else it },
                    aebSensitivity = aebSen,
                    elkMode        = elkMode,
                    elkSensitivity = elkSen,
                    energySaving   = if (FirmwareInfo.getGeneration() != FirmwareInfo.Gen.UNKNOWN) MG4Hardware.isEnergySavingOn() else false,
                    tsrEnabled     = if (FirmwareInfo.getGeneration() == FirmwareInfo.Gen.SWI133) MG4Hardware.isTsrOn() else false
                )
            }
            withContext(Dispatchers.Main) {
                if (isAdded) openEditor(existing = null, data = prefill)
            }
        }
    }

    // -------------------------------------------------------------------------
    // Ouverture de l'editeur plein ecran
    // -------------------------------------------------------------------------

    /**
     * Ouvre [ProfileEditFragment]. `existing` = profil edite (null en creation),
     * `data` = valeurs a afficher (profil existant, ou pre-remplissage lu sur la voiture).
     */
    private fun openEditor(existing: DrivingProfile?, data: DrivingProfile) {
        ProfileEditFragment.pendingExisting = existing
        ProfileEditFragment.pendingData = data
        findNavController().navigate(R.id.profileEditFragment)
    }
}
