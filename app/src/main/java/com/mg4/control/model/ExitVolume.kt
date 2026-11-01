package com.mg4.control.model

/**
 * Baisse du volume en quittant la voiture — la règle, sans Android.
 *
 * Elle ne fait que BAISSER : un volume déjà au niveau visé, ou en dessous, est laissé tel quel, et
 * il n'y a alors rien à rendre en refermant. Avant le 2026-10-30, l'appli écrivait le niveau visé
 * dans tous les cas : un volume à 4 avec une cible à 6 MONTAIT à 6 à l'ouverture de la porte, puis
 * redescendait à 4 en refermant (vu dans le fork sensoyg). Un volume illisible (-1) est baissé
 * quand même, sans restauration possible : c'était déjà le cas.
 */
object ExitVolume {

    fun shouldLower(current: Int, target: Int): Boolean = current !in 0..target
}
