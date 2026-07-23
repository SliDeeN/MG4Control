package com.mg4.control.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.mg4.control.R
import com.mg4.hardware.MG4Hardware
import com.mg4.hardware.model.DriveMode
import com.mg4.hardware.model.RegenLevel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DriveRegenFragment : Fragment() {

    private val driveModeButtons = mutableMapOf<DriveMode, Button>()
    private val regenButtons     = mutableMapOf<RegenLevel, Button>()

    private val driveModeColors by lazy {
        mapOf(
            DriveMode.ECO    to requireContext().getColor(R.color.accent_eco),
            DriveMode.NORMAL to requireContext().getColor(R.color.accent_normal),
            DriveMode.SPORT  to requireContext().getColor(R.color.accent_sport),
            DriveMode.SNOW   to requireContext().getColor(R.color.accent_snow),
            DriveMode.CUSTOM to requireContext().getColor(R.color.accent_custom)
        )
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_drive_regen, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        driveModeButtons[DriveMode.ECO]    = view.findViewById(R.id.btn_eco)
        driveModeButtons[DriveMode.NORMAL] = view.findViewById(R.id.btn_normal)
        driveModeButtons[DriveMode.SPORT]  = view.findViewById(R.id.btn_sport)
        driveModeButtons[DriveMode.SNOW]   = view.findViewById(R.id.btn_snow)
        driveModeButtons[DriveMode.CUSTOM] = view.findViewById(R.id.btn_custom)

        regenButtons[RegenLevel.OFF]       = view.findViewById(R.id.btn_regen_off)
        regenButtons[RegenLevel.LOW]       = view.findViewById(R.id.btn_regen_low)
        regenButtons[RegenLevel.MEDIUM]    = view.findViewById(R.id.btn_regen_medium)
        regenButtons[RegenLevel.HIGH]      = view.findViewById(R.id.btn_regen_high)
        regenButtons[RegenLevel.ADAPTIVE]  = view.findViewById(R.id.btn_regen_adaptive)
        regenButtons[RegenLevel.ONE_PEDAL] = view.findViewById(R.id.btn_regen_one_pedal)

        driveModeButtons.forEach { (mode, btn) ->
            btn.setOnClickListener {
                applyDriveModeUI(mode)
                CoroutineScope(Dispatchers.IO).launch { MG4Hardware.setDriveMode(mode) }
                Toast.makeText(context, "Mode : ${mode.label}", Toast.LENGTH_SHORT).show()
            }
        }

        regenButtons.forEach { (level, btn) ->
            btn.setOnClickListener {
                applyRegenUI(level)
                CoroutineScope(Dispatchers.IO).launch { MG4Hardware.setRegenLevel(level) }
                Toast.makeText(context, "Regen : ${level.label}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshState()
    }

    private fun refreshState() {
        CoroutineScope(Dispatchers.IO).launch {
            val mode  = MG4Hardware.getDriveMode()
            val regen = MG4Hardware.getRegenLevel()
            withContext(Dispatchers.Main) {
                if (!isAdded) return@withContext
                mode?.let  { applyDriveModeUI(it) }
                regen?.let { applyRegenUI(it) }
                if (mode == null && regen == null) {
                    view?.postDelayed({ if (isAdded) refreshState() }, 3_000)
                }
            }
        }
    }

    private fun applyDriveModeUI(mode: DriveMode) {
        val inactive = requireContext().getColor(R.color.bg_button)
        driveModeButtons.forEach { (m, btn) ->
            btn.backgroundTintList = android.content.res.ColorStateList.valueOf(
                if (m == mode) driveModeColors[mode] ?: inactive else inactive
            )
        }
        // SNOW : regen forcée faible par le véhicule → griser les boutons regen
        setRegenEnabled(mode != DriveMode.SNOW)
    }

    private fun applyRegenUI(level: RegenLevel) {
        val active   = requireContext().getColor(R.color.accent_regen)
        val inactive = requireContext().getColor(R.color.bg_button)
        regenButtons.forEach { (l, btn) ->
            btn.backgroundTintList = android.content.res.ColorStateList.valueOf(
                if (l == level) active else inactive
            )
        }
    }

    /** Active ou grise les boutons de régénération. */
    private fun setRegenEnabled(enabled: Boolean) {
        regenButtons.values.forEach { btn ->
            btn.isEnabled = enabled
            btn.alpha = if (enabled) 1f else 0.35f
        }
    }
}
