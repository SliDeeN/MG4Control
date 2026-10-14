package com.mg4.control.util

import android.content.res.Configuration
import androidx.appcompat.app.AppCompatDelegate

/**
 * Mode jour/nuit à donner à une fenêtre affichée par le SERVICE, pour qu'elle suive le thème de
 * l'application — la partie sans contexte, donc testable.
 *
 * Le thème choisi dans l'application passe par `AppCompatDelegate.setDefaultNightMode`, qui ne
 * touche que ses ACTIVITÉS. Une fenêtre du service gardait le mode du système, et sur SWI133
 * celui-ci reste figé sur « nuit » même quand le launcher passe en clair (voir [ThemeHelper]) :
 * les popups étaient donc toujours sombres.
 */
object PopupUiMode {

    /**
     * [uiMode] du système, avec ses deux bits jour/nuit remplacés par le choix de l'application
     * ([nightMode], une constante `AppCompatDelegate.MODE_NIGHT_*`). Tout le reste — le type
     * d'appareil — est conservé. Quand l'application elle-même suit le système, rien ne change.
     */
    fun apply(nightMode: Int, uiMode: Int): Int {
        val nuit = when (nightMode) {
            AppCompatDelegate.MODE_NIGHT_YES -> Configuration.UI_MODE_NIGHT_YES
            AppCompatDelegate.MODE_NIGHT_NO  -> Configuration.UI_MODE_NIGHT_NO
            else                             -> return uiMode
        }
        return (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or nuit
    }
}
