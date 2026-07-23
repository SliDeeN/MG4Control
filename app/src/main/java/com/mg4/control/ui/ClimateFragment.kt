package com.mg4.control.ui

import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.Switch
import androidx.fragment.app.Fragment
import com.mg4.control.R
import com.mg4.hardware.MG4Hardware
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ClimateFragment : Fragment() {

    private lateinit var switchSteering: Switch
    private lateinit var seatLeftButtons: List<Button>
    private lateinit var seatRightButtons: List<Button>

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_climate, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        switchSteering   = view.findViewById(R.id.switch_steering_heat)
        seatLeftButtons  = listOf(R.id.btn_seat_left_0,  R.id.btn_seat_left_1,  R.id.btn_seat_left_2,  R.id.btn_seat_left_3 ).map { view.findViewById(it) }
        seatRightButtons = listOf(R.id.btn_seat_right_0, R.id.btn_seat_right_1, R.id.btn_seat_right_2, R.id.btn_seat_right_3).map { view.findViewById(it) }

        // isPressed guard prevents the listener firing on programmatic setChecked()
        switchSteering.setOnCheckedChangeListener { _, checked ->
            if (switchSteering.isPressed) {
                CoroutineScope(Dispatchers.IO).launch { MG4Hardware.setSteeringHeat(checked) }
            }
        }

        setupSeatButtons(seatLeftButtons)  { level -> CoroutineScope(Dispatchers.IO).launch { MG4Hardware.setSeatHeatLeft(level) } }
        setupSeatButtons(seatRightButtons) { level -> CoroutineScope(Dispatchers.IO).launch { MG4Hardware.setSeatHeatRight(level) } }
    }

    override fun onResume() {
        super.onResume()
        refreshState()
    }

    private fun refreshState() {
        CoroutineScope(Dispatchers.IO).launch {
            val steeringOn = MG4Hardware.isSteeringHeatOn()
            val leftLevel  = MG4Hardware.getSeatHeatLeft()
            val rightLevel = MG4Hardware.getSeatHeatRight()
            // getSeatHeatLeft returns -1 (coerced to 0) when HVAC not ready;
            // use -1 raw to detect not-ready state
            val ready = MG4Hardware.getIntPropertyHvac(MG4Hardware.PROP_SEAT_HEAT_L, 0x75) >= 0
            withContext(Dispatchers.Main) {
                if (!isAdded) return@withContext
                if (ready) {
                    switchSteering.isChecked = steeringOn
                    applySeatUI(seatLeftButtons, leftLevel)
                    applySeatUI(seatRightButtons, rightLevel)
                } else {
                    view?.postDelayed({ if (isAdded) refreshState() }, 3_000)
                }
            }
        }
    }

    private fun setupSeatButtons(buttons: List<Button>, onLevel: (Int) -> Unit) {
        buttons.forEachIndexed { index, btn ->
            btn.setOnClickListener {
                applySeatUI(buttons, index)
                onLevel(index)
            }
        }
    }

    private fun applySeatUI(buttons: List<Button>, activeIndex: Int) {
        val active   = requireContext().getColor(R.color.accent_regen)
        val inactive = requireContext().getColor(R.color.bg_button)
        buttons.forEachIndexed { i, btn ->
            btn.backgroundTintList = ColorStateList.valueOf(if (i == activeIndex) active else inactive)
        }
    }
}
