package com.mg4.control.ui

import android.content.Context
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.mg4.control.R
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * La fenêtre « Firmware non reconnu » se gonfle, et sa mise en page tient ce que
 * [com.mg4.control.MainActivity] lui prête. Elle ne s'ouvre que sur une voiture dont le firmware
 * est inconnu : une erreur ici ne se verrait jamais sur celle du développeur.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UnknownFirmwareDialogLayoutTest {

    private val themed: Context = ContextThemeWrapper(
        ApplicationProvider.getApplicationContext<Context>(), R.style.Theme_MG4Control)

    @Test
    fun `chaque vue cherchee par le code existe et a le bon type`() {
        val vue = LayoutInflater.from(themed).inflate(R.layout.dialog_unknown_firmware, null)
        assertNotNull(vue.findViewById<ImageView>(R.id.iv_fw_unknown_icon))
        assertNotNull(vue.findViewById<TextView>(R.id.tv_fw_unknown_title))
        assertNotNull(vue.findViewById<TextView>(R.id.tv_fw_detected_badge))
        assertNotNull(vue.findViewById<TextView>(R.id.tv_fw_unknown_body))
        assertNotNull(vue.findViewById<TextView>(R.id.tv_fw_unknown_hint))
        assertNotNull(vue.findViewById<View>(R.id.btn_fw_close_app))
        assertNotNull(vue.findViewById<View>(R.id.btn_fw_continue))
    }
}
