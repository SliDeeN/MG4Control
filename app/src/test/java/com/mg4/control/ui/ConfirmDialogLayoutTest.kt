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
 * La mise en page des confirmations de l'application tient ce que [AppDialog.confirmer] lui prête.
 * Toutes les confirmations simples passent par elle : une vue introuvable ou d'un autre type les
 * casserait toutes d'un coup, et ne se verrait qu'au moment de supprimer un profil ou d'éteindre
 * la voiture.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ConfirmDialogLayoutTest {

    private val themed: Context = ContextThemeWrapper(
        ApplicationProvider.getApplicationContext<Context>(), R.style.Theme_MG4Control)

    @Test
    fun `chaque vue cherchee par le code existe et a le bon type`() {
        val vue = LayoutInflater.from(themed).inflate(R.layout.dialog_confirm, null)
        assertNotNull(vue.findViewById<View>(R.id.confirm_dialog_badge))
        assertNotNull(vue.findViewById<ImageView>(R.id.confirm_dialog_icon))
        assertNotNull(vue.findViewById<TextView>(R.id.confirm_dialog_title))
        assertNotNull(vue.findViewById<View>(R.id.confirm_dialog_scroll))
        assertNotNull(vue.findViewById<TextView>(R.id.confirm_dialog_message))
        assertNotNull(vue.findViewById<TextView>(R.id.confirm_dialog_no))
        assertNotNull(vue.findViewById<TextView>(R.id.confirm_dialog_yes))
    }
}
