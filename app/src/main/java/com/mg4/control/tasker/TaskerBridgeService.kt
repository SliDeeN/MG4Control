package com.mg4.control.tasker

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.IBinder
import com.mg4.hardware.AppLogger
import com.mg4.hardware.MG4Hardware
import com.mg4.hardware.VehicleWriteGate
import com.mg4.control.profile.ProfileApplier
import com.mg4.control.profile.ProfileManager

/**
 * Profile bridge for MG4Tasker (see [ITaskerBridge]).
 *
 * MG4Tasker is an independent system app: it reads and writes the vehicle itself through
 * the shared MG4Hardware layer. The one thing it cannot do alone is apply an MG4Control
 * *profile* — those live in MG4Control — so this service exposes exactly that: list the
 * profiles and apply one. There is no vehicle read and no raw property write here.
 *
 * Guarded by the signature permission com.mg4.control.permission.TASKER_BRIDGE: only an app
 * signed with the same platform key can bind.
 */
class TaskerBridgeService : Service() {

    companion object {
        private const val TAG = "MG4_TASKER_BRIDGE"

        /** Signature permission required to bind this service. */
        const val PERMISSION_BRIDGE = "com.mg4.control.permission.TASKER_BRIDGE"

        // Result keys (shared with MG4Tasker's BridgeContract).
        const val KEY_OK      = "ok"
        const val KEY_VERDICT = "verdict"
        const val KEY_DETAIL  = "detail"

        const val VERDICT_ALLOWED       = "ALLOWED"
        const val VERDICT_MOVING        = "REFUSED_MOVING"
        const val VERDICT_UNKNOWN_SPEED = "REFUSED_UNKNOWN_SPEED"
        const val VERDICT_UNSUPPORTED   = "UNSUPPORTED"
        const val VERDICT_ERROR         = "ERROR"

        /**
         * Translates the gate decision into a verdict string. Pure and exhaustive: if
         * [VehicleWriteGate.Decision] gains a case, compilation breaks here rather than
         * sending an invented verdict to the caller.
         */
        fun verdictOf(decision: VehicleWriteGate.Decision): String = when (decision) {
            VehicleWriteGate.Decision.ALLOWED               -> VERDICT_ALLOWED
            VehicleWriteGate.Decision.REFUSED_MOVING        -> VERDICT_MOVING
            VehicleWriteGate.Decision.REFUSED_UNKNOWN_SPEED -> VERDICT_UNKNOWN_SPEED
        }
    }

    private val profileManager by lazy { ProfileManager(applicationContext) }

    override fun onBind(intent: Intent?): IBinder = binder

    private val binder = object : ITaskerBridge.Stub() {

        override fun listProfiles(): Bundle {
            val profiles = profileManager.getAll()
            return Bundle().apply {
                putStringArray("ids", profiles.map { it.id }.toTypedArray())
                putStringArray("names", profiles.map { it.name }.toTypedArray())
                profileManager.getDefaultId()?.let { putString("defaultId", it) }
            }
        }

        override fun applyProfile(profileId: String?): Bundle {
            val profile = profileId?.let { profileManager.getById(it) }
                ?: return result(false, VERDICT_UNSUPPORTED, "profile not found: $profileId")

            // The gate is re-evaluated setting-by-setting inside ProfileApplier; the current
            // verdict is returned here so the caller knows whether to expect partial
            // refusals. Application itself is asynchronous.
            val verdict = verdictOf(VehicleWriteGate.decide(MG4Hardware.getVehicleSpeedKmh()))
            ProfileApplier.apply(profile, autoStart = true)
            AppLogger.i(TAG, "applyProfile(${profile.name}) verdict=$verdict")
            return result(true, verdict, profile.name)
        }
    }

    private fun result(ok: Boolean, verdict: String, detail: String? = null) = Bundle().apply {
        putBoolean(KEY_OK, ok)
        putString(KEY_VERDICT, verdict)
        detail?.let { putString(KEY_DETAIL, it) }
    }
}
