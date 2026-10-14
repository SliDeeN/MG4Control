package com.mg4.control.ui

import android.content.Context
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.mg4.control.R
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * La fenêtre « Restaurer les profils ? » se gonfle, et sa mise en page tient ce que
 * [RestoreProfilesDialog] lui prête : une vue introuvable ou d'un autre type ne se verrait qu'à
 * l'ouverture de l'application après une réinstallation.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RestoreProfilesDialogLayoutTest {

    private val themed: Context = ContextThemeWrapper(
        ApplicationProvider.getApplicationContext<Context>(), R.style.Theme_MG4Control)

    @Test
    fun `chaque vue cherchee par le code existe et a le bon type`() {
        val vue = LayoutInflater.from(themed).inflate(R.layout.dialog_restore_profiles, null)
        assertNotNull(vue.findViewById<ImageView>(R.id.restore_icon))
        assertNotNull(vue.findViewById<TextView>(R.id.restore_message))
        assertNotNull(vue.findViewById<View>(R.id.restore_btn_ignore))
        assertNotNull(vue.findViewById<View>(R.id.restore_btn_confirm))
        assertNotNull(vue.findViewById<ImageView>(R.id.restore_confirm_icon))

        val rangee = vue.findViewById<LinearLayout>(R.id.restore_profiles)
        val carte = LayoutInflater.from(themed).inflate(R.layout.item_dialog_profile, rangee, false)
        assertNotNull(carte.findViewById<TextView>(R.id.dialog_profile_mode))
        assertNotNull(carte.findViewById<TextView>(R.id.dialog_profile_name))
    }
}
